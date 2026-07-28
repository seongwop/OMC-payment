package com.omc.paymenttools.event;

import java.util.UUID;

public record StockFailedEvent(
        String eventId,
        UUID orderId
) {
}
