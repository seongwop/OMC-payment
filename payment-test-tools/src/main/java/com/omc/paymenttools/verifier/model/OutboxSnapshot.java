package com.omc.paymenttools.verifier.model;

import java.time.LocalDateTime;
import java.util.UUID;

public record OutboxSnapshot(
        UUID eventId,
        String eventType,
        String status,
        int retryCount,
        LocalDateTime createdAt,
        LocalDateTime publishedAt
) {
}
