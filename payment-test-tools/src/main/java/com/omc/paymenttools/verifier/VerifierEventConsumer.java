package com.omc.paymenttools.verifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omc.paymenttools.config.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "payment-test-tools.verifier",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@RequiredArgsConstructor
public class VerifierEventConsumer {

    private final ObjectMapper objectMapper;
    private final ObservedEventStore observedEventStore;

    // 결제 승인 완료 이벤트 관찰
    @KafkaListener(topics = KafkaTopics.PAYMENT_COMPLETED)
    public void consumePaymentCompleted(String payload) {
        record(KafkaTopics.PAYMENT_COMPLETED, payload);
    }

    // 결제 실패 이벤트 관찰
    @KafkaListener(topics = KafkaTopics.PAYMENT_FAILED)
    public void consumePaymentFailed(String payload) {
        record(KafkaTopics.PAYMENT_FAILED, payload);
    }

    // 환불 완료 이벤트 관찰
    @KafkaListener(topics = KafkaTopics.REFUND_DONE)
    public void consumeRefundDone(String payload) {
        record(KafkaTopics.REFUND_DONE, payload);
    }

    // 발행 이벤트의 eventId와 orderId를 추출하여 검증 저장소에 기록
    private void record(String topic, String payload) {
        try {
            JsonNode event = objectMapper.readTree(payload);
            String eventId = requiredText(event, "eventId");
            UUID orderId = UUID.fromString(requiredText(event, "orderId"));
            observedEventStore.record(topic, eventId, orderId, event);
        } catch (Exception e) {
            log.error("결제 발행 이벤트 기록에 실패했습니다. topic={}, payload={}", topic, payload, e);
        }
    }

    // 정합성 검증에 필요한 필수 이벤트 필드 확인
    private String requiredText(JsonNode event, String fieldName) {
        JsonNode field = event.get(fieldName);
        if (field == null || field.asText().isBlank()) {
            throw new IllegalArgumentException("필수 이벤트 필드가 없습니다: " + fieldName);
        }
        return field.asText();
    }
}
