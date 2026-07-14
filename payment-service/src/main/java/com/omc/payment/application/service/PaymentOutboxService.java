package com.omc.payment.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omc.payment.application.event.dto.outbound.PaymentCompletedEvent;
import com.omc.payment.application.event.dto.outbound.PaymentFailedEvent;
import com.omc.payment.application.event.dto.outbound.RefundDoneEvent;
import com.omc.common.util.UuidV7Generator;
import com.omc.payment.domain.entity.Payment;
import com.omc.payment.domain.entity.PaymentOutboxEvent;
import com.omc.payment.domain.enums.OutboxAggregateType;
import com.omc.payment.domain.repository.PaymentOutboxEventRepository;
import com.omc.payment.infrastructure.config.KafkaTopics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentOutboxService {

    private final PaymentOutboxEventRepository paymentOutboxEventRepository;
    private final ObjectMapper objectMapper;

    public void savePaymentCompleted(Payment payment) {
        UUID eventId = UuidV7Generator.generate();
        PaymentCompletedEvent event = new PaymentCompletedEvent(
                eventId.toString(),
                payment.getSalesType(),
                payment.getDropId(),
                payment.getOrderId(),
                payment.getRaffleId(),
                payment.getEntryId(),
                payment.getProductId(),
                payment.getUserId(),
                payment.getCouponId(),
                payment.getOriginalAmount(),
                payment.getDiscountAmount(),
                payment.getFinalAmount(),
                payment.getPaymentId()
        );
        save(eventId, payment.getPaymentId(), KafkaTopics.PAYMENT_COMPLETED, event);
    }

    public void savePaymentFailed(Payment payment) {
        UUID eventId = UuidV7Generator.generate();
        String failureReason = payment.getFailureMessage() == null || payment.getFailureMessage().isBlank()
                ? payment.getFailureCode()
                : payment.getFailureMessage();

        PaymentFailedEvent event = new PaymentFailedEvent(
                eventId.toString(),
                payment.getSalesType(),
                payment.getDropId(),
                payment.getOrderId(),
                payment.getRaffleId(),
                payment.getEntryId(),
                payment.getProductId(),
                payment.getUserId(),
                payment.getCouponId(),
                failureReason
        );
        save(eventId, payment.getPaymentId(), KafkaTopics.PAYMENT_FAILED, event);
    }

    public void saveRefundDone(Payment payment) {
        UUID eventId = UuidV7Generator.generate();
        String refundReason = payment.getCancelledMessage();

        RefundDoneEvent event = new RefundDoneEvent(
                eventId.toString(),
                payment.getOrderId(),
                payment.getUserId(),
                payment.getCouponId(),
                payment.getFinalAmount(),
                refundReason
        );
        save(eventId, payment.getPaymentId(), KafkaTopics.REFUND_DONE, event);
    }

    private void save(UUID eventId, UUID aggregateId, String eventType, Object payload) {
        String jsonPayload = toJson(payload);
        PaymentOutboxEvent outboxEvent = PaymentOutboxEvent.create(
                eventId,
                OutboxAggregateType.PAYMENT,
                aggregateId,
                eventType,
                jsonPayload
        );
        paymentOutboxEventRepository.save(outboxEvent);
    }

    // 직렬화
    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("결제 아웃박스 이벤트 payload 직렬화에 실패했습니다.", e);
        }
    }
}
