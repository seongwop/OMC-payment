package com.omc.paymenttools.verifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omc.paymenttools.config.KafkaTopics;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ObservedEventStoreTest {

    private final ObservedEventStore store = new ObservedEventStore();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void duplicateDeliveryWithSameEventIdIsStoredOnce() {
        UUID orderId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        var payload = objectMapper.createObjectNode().put("orderId", orderId.toString());

        store.record(KafkaTopics.PAYMENT_COMPLETED, eventId, orderId, payload);
        store.record(KafkaTopics.PAYMENT_COMPLETED, eventId, orderId, payload);

        assertThat(store.findByOrderId(orderId)).hasSize(1);
    }

    @Test
    void clearRemovesAllObservedEvents() {
        UUID orderId = UUID.randomUUID();
        store.record(
                KafkaTopics.PAYMENT_COMPLETED,
                UUID.randomUUID().toString(),
                orderId,
                objectMapper.createObjectNode()
        );

        store.clear();

        assertThat(store.findByOrderId(orderId)).isEmpty();
    }
}
