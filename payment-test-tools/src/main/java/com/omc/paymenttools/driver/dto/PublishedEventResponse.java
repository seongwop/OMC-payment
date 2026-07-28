package com.omc.paymenttools.driver.dto;

import java.time.Instant;

public record PublishedEventResponse(
        String topic,
        String eventId,
        String key,
        int partition,
        long offset,
        Instant publishedAt
) {
}
