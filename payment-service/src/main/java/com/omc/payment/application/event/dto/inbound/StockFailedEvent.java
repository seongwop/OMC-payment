package com.omc.payment.application.event.dto.inbound;

import java.util.UUID;

public record StockFailedEvent(
        String eventId,
        UUID orderId
) {
}
