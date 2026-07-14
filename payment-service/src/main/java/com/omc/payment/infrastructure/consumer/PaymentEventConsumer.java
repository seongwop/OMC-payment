package com.omc.payment.infrastructure.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omc.payment.application.event.dto.inbound.OrderCreatedEvent;
import com.omc.payment.application.event.dto.inbound.RefundRequestedEvent;
import com.omc.payment.application.event.dto.inbound.StockFailedEvent;
import com.omc.payment.application.service.PaymentEventService;
import com.omc.payment.domain.exception.NonRetryablePaymentException;
import com.omc.payment.domain.exception.PaymentErrorCode;
import com.omc.payment.infrastructure.config.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private final ObjectMapper objectMapper;
    private final PaymentEventService paymentEventService;
    private final PaymentEventValidator paymentEventValidator;

    @KafkaListener(topics = KafkaTopics.ORDER_CREATED)
    public void consumeOrderCreated(String message, Acknowledgment acknowledgment) {
        OrderCreatedEvent event = readValue(message, OrderCreatedEvent.class, KafkaTopics.ORDER_CREATED);
        paymentEventValidator.validate(event);
        paymentEventService.handleOrderCreated(event);
        acknowledgment.acknowledge();
    }

    @KafkaListener(topics = KafkaTopics.REFUND_REQUESTED)
    public void consumeRefundRequested(String message, Acknowledgment acknowledgment) {
        RefundRequestedEvent event = readValue(message, RefundRequestedEvent.class, KafkaTopics.REFUND_REQUESTED);
        paymentEventValidator.validate(event);
        paymentEventService.handleRefundRequested(event);
        acknowledgment.acknowledge();
    }

    @KafkaListener(topics = KafkaTopics.STOCK_FAILED)
    public void consumeStockFailed(String message, Acknowledgment acknowledgment) {
        StockFailedEvent event = readValue(message, StockFailedEvent.class, KafkaTopics.STOCK_FAILED);
        paymentEventValidator.validate(event);
        paymentEventService.handleStockFailed(event);
        acknowledgment.acknowledge();
    }

    // 이벤트 문자열 역직렬화
    private <T> T readValue(String message, Class<T> targetType, String topic) {
        try {
            return objectMapper.readValue(message, targetType);
        } catch (Exception e) {
            log.error("{} 이벤트 역직렬화에 실패했습니다. payload={}", topic, message, e);
            throw new NonRetryablePaymentException(
                    PaymentErrorCode.PAYMENT_FAILED,
                    topic + " 이벤트 역직렬화에 실패했습니다."
            );
        }
    }
}
