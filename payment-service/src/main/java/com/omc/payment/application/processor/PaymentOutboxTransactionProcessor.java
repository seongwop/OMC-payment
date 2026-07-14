package com.omc.payment.application.processor;

import com.omc.payment.domain.entity.PaymentOutboxEvent;
import com.omc.payment.domain.enums.OutboxEventStatus;
import com.omc.payment.domain.repository.PaymentOutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PaymentOutboxTransactionProcessor {

    private final PaymentOutboxEventRepository paymentOutboxEventRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentOutboxEvent claimForPublishing(UUID eventId, int maxRetryCount) {
        // 중복 발행 방지를 위한 상태 선점
        // 다중 인스턴스를 고려하여 DB 원자적 조건 업데이트 수행
        int claimed = paymentOutboxEventRepository.claimForPublishing(
                eventId,
                OutboxEventStatus.PUBLISHING,
                List.of(OutboxEventStatus.INIT, OutboxEventStatus.FAILED),
                maxRetryCount
        );
        // 이미 선점 중
        if (claimed == 0) {
            return null;
        }
        return paymentOutboxEventRepository.findById(eventId).orElse(null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublished(UUID eventId) {
        paymentOutboxEventRepository.findById(eventId)
                .ifPresent(PaymentOutboxEvent::markPublished);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID eventId, int maxRetryCount) {
        paymentOutboxEventRepository.findById(eventId)
                .ifPresent(outboxEvent -> outboxEvent.markFailed(maxRetryCount));
    }
}
