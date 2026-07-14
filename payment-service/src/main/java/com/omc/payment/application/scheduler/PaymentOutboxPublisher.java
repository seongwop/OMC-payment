package com.omc.payment.application.scheduler;

import com.omc.payment.application.service.PaymentOutboxPublishService;
import com.omc.payment.domain.entity.PaymentOutboxEvent;
import com.omc.payment.domain.enums.OutboxEventStatus;
import com.omc.payment.domain.repository.PaymentOutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentOutboxPublisher {

    private final PaymentOutboxEventRepository paymentOutboxEventRepository;
    private final PaymentOutboxPublishService paymentOutboxPublishService;

    @Value("${payment.outbox.publisher.max-retry-count:3}")
    private int maxRetryCount;

    @Scheduled(
            initialDelayString = "${payment.outbox.publisher.initial-delay-ms:5000}",
            fixedDelayString = "${payment.outbox.publisher.fixed-delay-ms:5000}"
    )
    public void publishPendingEvents() {
        List<PaymentOutboxEvent> pendingEvents =
                paymentOutboxEventRepository.findTop100ByStatusInAndRetryCountLessThanOrderByCreatedAtAsc(
                        List.of(OutboxEventStatus.INIT, OutboxEventStatus.FAILED),
                        maxRetryCount
                );
        if (pendingEvents.isEmpty()) {
            return;
        }
        log.debug("결제 아웃박스 이벤트 {}건 발행을 시작합니다.", pendingEvents.size());
        for (PaymentOutboxEvent pendingEvent : pendingEvents) {
            paymentOutboxPublishService.publish(pendingEvent.getEventId(), maxRetryCount);
        }
    }
}
