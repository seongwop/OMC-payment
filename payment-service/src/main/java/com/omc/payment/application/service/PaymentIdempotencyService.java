package com.omc.payment.application.service;

import com.omc.common.exception.BusinessException;
import com.omc.payment.domain.exception.PaymentErrorCode;
import com.omc.payment.domain.exception.RetryablePaymentException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class PaymentIdempotencyService {

    // 상태를 PROCESSING과 SUCCEEDED로 구분
    private static final String PROCESSING_PREFIX = "PROCESSING:";
    private static final String SUCCEEDED = "SUCCEEDED";

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${payment.idempotency.processing-ttl-seconds:600}")
    private long processingTtlSeconds;

    @Value("${payment.idempotency.success-ttl-seconds:86400}")
    private long successTtlSeconds;

    /*
    * orderID 기준 멱등성 키 생성
    * */
    public String confirmKey(UUID orderId) {
        return "payment:confirm:" + orderId;
    }

    public String cancelKey(UUID orderId) {
        return "payment:cancel:" + orderId;
    }


    public void execute(String idempotencyKey, Runnable action) {
        execute(
                idempotencyKey,
                () -> {
                    action.run();
                    return null;
                },
                () -> null
        );
    }

    public <T> T execute(String idempotencyKey, Supplier<T> action, Supplier<T> alreadySucceeded) {
        if (isSucceeded(idempotencyKey)) {
            return alreadySucceeded.get();
        }

        String processingToken = PROCESSING_PREFIX + UUID.randomUUID();
        // 멱등성 방어 키 생성 : 해당 키가 없을 경우 PROCESSING 선점
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(idempotencyKey, processingToken, Duration.ofSeconds(processingTtlSeconds));

        if (!Boolean.TRUE.equals(acquired)) {
            // 선점에 실패했지만 다른 요청에 의해 성공한 경우 단순 리턴
            if (isSucceeded(idempotencyKey)) {
                return alreadySucceeded.get();
            }
            throw new RetryablePaymentException(PaymentErrorCode.PAYMENT_ALREADY_EXISTS, "동일한 결제 요청이 이미 처리 중입니다.");
        }
        try {
            T result = action.get();

            // DB 저장과 Redis를 트랜잭션으로 묶을 수 없기 때문에
            // 트랜잭션이 커밋된 후에 성공 처리하도록 보장
            markSucceededAfterCommit(idempotencyKey,  processingToken);
            return result;
        } catch (RuntimeException e) {
            // 예외 발생 시 선점 취소
            clearProcessing(idempotencyKey, processingToken);
            throw e;
        }
    }

    private boolean isSucceeded(String idempotencyKey) {
        return SUCCEEDED.equals(stringRedisTemplate.opsForValue().get(idempotencyKey));
    }

    private void markSucceededAfterCommit(String idempotencyKey, String processingToken) {
        // 트랜잭션 내부가 아닐 경우를 위한 방어 로직
        // 훅을 수행할 수 없으므로 성공 처리
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            markSucceeded(idempotencyKey);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 트랜잭션이 커밋된 후 상태 변경 보장
                markSucceeded(idempotencyKey);
            }

            @Override
            public void afterCompletion(int status) {
                // 커밋 실패 시 PROCESSING 삭제
                if (status != STATUS_COMMITTED) {
                    clearProcessing(idempotencyKey, processingToken);
                }
            }
        });
    }

    private void markSucceeded(String idempotencyKey) {
        // TTL을 통해 메모리 누적 방지
        stringRedisTemplate.opsForValue().set(idempotencyKey, SUCCEEDED, Duration.ofSeconds(successTtlSeconds));
    }

    private void clearProcessing(String idempotencyKey, String processingToken) {
        /*
        * 다른 스레드가 이미 같은 키를 새로 잡았을 경우 대비
        * 잡았던 토큰과 현재 값이 같을 때만 삭제 가능
        * */
        String currentValue = stringRedisTemplate.opsForValue().get(idempotencyKey);
        if (Objects.equals(processingToken, currentValue)) {
            stringRedisTemplate.delete(idempotencyKey);
        }
    }
}
