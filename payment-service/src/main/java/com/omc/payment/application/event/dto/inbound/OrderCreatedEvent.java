package com.omc.payment.application.event.dto.inbound;

import java.util.UUID;

public record OrderCreatedEvent(
        String eventId,
        UUID orderId,
        UUID userId,
        String orderType,
        UUID dropId,
        UUID productId,
        UUID raffleId,
        UUID entryId,
        Long originalAmount,
        Long discountAmount,
        Long finalAmount,
        UUID couponId,
        String billingKeyId
) {
}
