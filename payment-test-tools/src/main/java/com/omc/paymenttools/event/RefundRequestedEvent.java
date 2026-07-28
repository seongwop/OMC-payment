package com.omc.paymenttools.event;

import java.util.UUID;

public record RefundRequestedEvent(
        String eventId,
        UUID orderId,
        UUID userId,
        String reason
) {
}
