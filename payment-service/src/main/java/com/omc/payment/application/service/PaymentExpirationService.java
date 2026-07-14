package com.omc.payment.application.service;

import com.omc.payment.domain.entity.Payment;
import com.omc.payment.domain.enums.PaymentStatus;
import com.omc.payment.domain.exception.PaymentErrorCode;
import com.omc.payment.domain.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentExpirationService {

    private final PaymentRepository paymentRepository;
    private final PaymentTransactionService paymentTransactionService;

    private final String READY_EXPIRED_MESSAGE = "결제 승인 요청 TTL이 만료되었습니다.";

    // READY, CONFIRMING 상태로 남아있는 결제 처리
    public void expireIncompletePayments(int batchSize, Duration ttl) {
        if (batchSize <= 0) {
            log.warn("미완료 결제 만료 처리 batchSize가 올바르지 않습니다. batchSize={}", batchSize);
            return;
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            log.warn("미완료 결제 만료 TTL이 올바르지 않습니다. ttl={}", ttl);
            return;
        }
        LocalDateTime expiredBefore = LocalDateTime.now().minus(ttl);
        expireReadyPayments(batchSize, expiredBefore);
        expireConfirmingPayments(batchSize, expiredBefore);
    }

    // PG 호출 전인 READY 상태는 실패로 확정
    private void expireReadyPayments(int batchSize, LocalDateTime expiredBefore) {
        List<Payment> payments = paymentRepository.findByPaymentStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                PaymentStatus.READY,
                expiredBefore,
                PageRequest.of(0, batchSize)
        );
        for (Payment payment : payments) {
            paymentTransactionService.failAndSaveOutbox(
                    payment.getPaymentId(),
                    PaymentErrorCode.PAYMENT_FAILED.getCode(),
                    READY_EXPIRED_MESSAGE
            );
        }
    }

    // PG 승인 요청이 나갔을 수 있는 CONFIRMING 상태는 UNKNOWN 처리
    private void expireConfirmingPayments(int batchSize, LocalDateTime expiredBefore) {
        List<Payment> payments = paymentRepository.findByPaymentStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                PaymentStatus.CONFIRMING,
                expiredBefore,
                PageRequest.of(0, batchSize)
        );
        for (Payment payment : payments) {
            paymentTransactionService.markConfirmUnknown(payment.getPaymentId());
        }
    }
}
