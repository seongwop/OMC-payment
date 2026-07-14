package com.omc.payment.application.scheduler;

import com.omc.payment.application.service.PaymentOutboxPublishService;
import com.omc.payment.domain.entity.PaymentOutboxEvent;
import com.omc.payment.domain.enums.OutboxAggregateType;
import com.omc.payment.domain.enums.OutboxEventStatus;
import com.omc.payment.domain.repository.PaymentOutboxEventRepository;
import com.omc.payment.infrastructure.config.KafkaTopics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("결제 아웃박스 발행기 테스트")
class PaymentOutboxPublisherTest {

    @Mock private PaymentOutboxEventRepository paymentOutboxEventRepository;
    @Mock private PaymentOutboxPublishService paymentOutboxPublishService;

    @InjectMocks
    private PaymentOutboxPublisher paymentOutboxPublisher;

    @Test
    @DisplayName("대기 중인 초기 상태와 실패 상태 이벤트를 발행한다")
    void publishPendingEvents_success() {
        PaymentOutboxEvent initEvent = createOutboxEvent();
        PaymentOutboxEvent failedEvent = createOutboxEvent();
        failedEvent.markFailed(3);
        ReflectionTestUtils.setField(paymentOutboxPublisher, "maxRetryCount", 3);

        when(paymentOutboxEventRepository.findTop100ByStatusInAndRetryCountLessThanOrderByCreatedAtAsc(
                List.of(OutboxEventStatus.INIT, OutboxEventStatus.FAILED),
                3
        )).thenReturn(List.of(initEvent, failedEvent));

        paymentOutboxPublisher.publishPendingEvents();

        verify(paymentOutboxPublishService).publish(initEvent.getEventId(), 3);
        verify(paymentOutboxPublishService).publish(failedEvent.getEventId(), 3);
    }

    @Test
    @DisplayName("대기 중인 이벤트가 없으면 아무 작업도 하지 않는다")
    void publishPendingEvents_noEvents() {
        ReflectionTestUtils.setField(paymentOutboxPublisher, "maxRetryCount", 3);
        when(paymentOutboxEventRepository.findTop100ByStatusInAndRetryCountLessThanOrderByCreatedAtAsc(
                List.of(OutboxEventStatus.INIT, OutboxEventStatus.FAILED),
                3
        )).thenReturn(List.of());

        paymentOutboxPublisher.publishPendingEvents();

        verify(paymentOutboxPublishService, never()).publish(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()
        );
    }

    private PaymentOutboxEvent createOutboxEvent() {
        return PaymentOutboxEvent.create(
                UUID.randomUUID(),
                OutboxAggregateType.PAYMENT,
                UUID.randomUUID(),
                KafkaTopics.PAYMENT_COMPLETED,
                "{}"
        );
    }
}
