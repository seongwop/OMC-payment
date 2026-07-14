package com.omc.payment.application.service;

import com.omc.payment.application.processor.PaymentOutboxTransactionProcessor;
import com.omc.payment.domain.entity.PaymentOutboxEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentOutboxPublishService {

    private final PaymentOutboxTransactionProcessor paymentOutboxTransactionProcessor;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /*
    * Bulk로 읽은 Outbox 데이터들을 하나씩 발행
    * 스케줄러는 트랜잭션에 포함되지 않기 때문에 객체가 아닌 eventId를 인자로 전달
    * */
    public void publish(UUID eventId, int maxRetryCount) {
        PaymentOutboxEvent outboxEvent = paymentOutboxTransactionProcessor.claimForPublishing(eventId, maxRetryCount);
        if (outboxEvent == null) {
            return;
        }

        try {
            kafkaTemplate.send(
                    outboxEvent.getEventType(),
                    outboxEvent.getAggregateId().toString(),
                    outboxEvent.getPayload()
            ).get(); // 동기 대기

            paymentOutboxTransactionProcessor.markPublished(eventId);
        } catch (InterruptedException e) { // Future.get() 과정에서 스레드 인터럽트 예외
            paymentOutboxTransactionProcessor.markFailed(eventId, maxRetryCount);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            paymentOutboxTransactionProcessor.markFailed(eventId, maxRetryCount);
            log.warn("결제 아웃박스 이벤트 payload 역직렬화 또는 발행에 실패했습니다. eventId={}", eventId, e);
        }
    }
}
