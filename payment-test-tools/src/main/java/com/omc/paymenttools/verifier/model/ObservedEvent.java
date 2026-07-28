package com.omc.paymenttools.verifier.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record ObservedEvent(
        String topic,
        String eventId,
        UUID orderId,
        JsonNode payload,
        Instant observedAt
) {
}
