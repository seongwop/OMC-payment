package com.omc.payment.application.service;

import com.omc.payment.domain.entity.PaymentOutboxEvent;
import com.omc.payment.application.processor.PaymentOutboxTransactionProcessor;
import com.omc.payment.domain.enums.OutboxAggregateType;
import com.omc.payment.domain.enums.OutboxEventStatus;
import com.omc.payment.domain.repository.PaymentOutboxEventRepository;
import com.omc.payment.infrastructure.config.KafkaTopics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("결제 Outbox 발행 서비스 테스트")
class PaymentOutboxPublishServiceTest {

    @Mock private PaymentOutboxEventRepository paymentOutboxEventRepository;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;

    private PaymentOutboxPublishService paymentOutboxPublishService;

    @BeforeEach
    void setUp() {
        PaymentOutboxTransactionProcessor paymentOutboxTransactionProcessor =
                new PaymentOutboxTransactionProcessor(paymentOutboxEventRepository);
        paymentOutboxPublishService = new PaymentOutboxPublishService(
                paymentOutboxTransactionProcessor,
                kafkaTemplate
        );
    }

    @Test
    @DisplayName("Outbox 이벤트 claim 성공 후 Kafka 발행에 성공하면 PUBLISHED로 변경한다")
    void publish_success() {
        UUID eventId = UUID.randomUUID();
        PaymentOutboxEvent outboxEvent = createOutboxEvent(eventId);

        when(paymentOutboxEventRepository.claimForPublishing(
                eq(eventId),
                eq(OutboxEventStatus.PUBLISHING),
                eq(List.of(OutboxEventStatus.INIT, OutboxEventStatus.FAILED)),
                eq(3)
        )).thenReturn(1);
        when(paymentOutboxEventRepository.findById(eventId)).thenReturn(Optional.of(outboxEvent));
        when(kafkaTemplate.send(
                outboxEvent.getEventType(),
                outboxEvent.getAggregateId().toString(),
                outboxEvent.getPayload()
        )).thenReturn(CompletableFuture.<SendResult<String, Object>>completedFuture(null));

        paymentOutboxPublishService.publish(eventId, 3);

        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
    }

    @Test
    @DisplayName("Outbox 이벤트 claim에 실패하면 Kafka 발행을 하지 않는다")
    void publish_claimFailed() {
        UUID eventId = UUID.randomUUID();

        when(paymentOutboxEventRepository.claimForPublishing(
                eq(eventId),
                eq(OutboxEventStatus.PUBLISHING),
                eq(List.of(OutboxEventStatus.INIT, OutboxEventStatus.FAILED)),
                eq(3)
        )).thenReturn(0);

        paymentOutboxPublishService.publish(eventId, 3);

        verify(paymentOutboxEventRepository, never()).findById(eventId);
        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    @DisplayName("Kafka 발행에 실패하면 FAILED로 변경하고 재시도 횟수를 증가시킨다")
    void publish_failed() {
        UUID eventId = UUID.randomUUID();
        PaymentOutboxEvent outboxEvent = createOutboxEvent(eventId);
        CompletableFuture<SendResult<String, Object>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("카프카 발행 실패"));

        when(paymentOutboxEventRepository.claimForPublishing(
                eq(eventId),
                eq(OutboxEventStatus.PUBLISHING),
                eq(List.of(OutboxEventStatus.INIT, OutboxEventStatus.FAILED)),
                eq(3)
        )).thenReturn(1);
        when(paymentOutboxEventRepository.findById(eventId)).thenReturn(Optional.of(outboxEvent));
        when(kafkaTemplate.send(
                outboxEvent.getEventType(),
                outboxEvent.getAggregateId().toString(),
                outboxEvent.getPayload()
        )).thenReturn(failedFuture);

        paymentOutboxPublishService.publish(eventId, 3);

        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(outboxEvent.getRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Kafka 발행 실패 후 최대 재시도 횟수에 도달하면 DEAD로 변경한다")
    void publish_failedWithMaxRetryCount_marksDead() {
        UUID eventId = UUID.randomUUID();
        PaymentOutboxEvent outboxEvent = createOutboxEvent(eventId);
        outboxEvent.markFailed(3);
        outboxEvent.markFailed(3);
        CompletableFuture<SendResult<String, Object>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka publish failed"));

        when(paymentOutboxEventRepository.claimForPublishing(
                eq(eventId),
                eq(OutboxEventStatus.PUBLISHING),
                eq(List.of(OutboxEventStatus.INIT, OutboxEventStatus.FAILED)),
                eq(3)
        )).thenReturn(1);
        when(paymentOutboxEventRepository.findById(eventId)).thenReturn(Optional.of(outboxEvent));
        when(kafkaTemplate.send(
                outboxEvent.getEventType(),
                outboxEvent.getAggregateId().toString(),
                outboxEvent.getPayload()
        )).thenReturn(failedFuture);

        paymentOutboxPublishService.publish(eventId, 3);

        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.DEAD);
        assertThat(outboxEvent.getRetryCount()).isEqualTo(3);
    }

    private PaymentOutboxEvent createOutboxEvent(UUID eventId) {
        return PaymentOutboxEvent.create(
                eventId,
                OutboxAggregateType.PAYMENT,
                UUID.randomUUID(),
                KafkaTopics.PAYMENT_COMPLETED,
                "{}"
        );
    }
}
