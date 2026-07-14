package com.omc.payment.application.event.dto.outbound;

import com.omc.payment.domain.enums.SalesType;

import java.util.UUID;

public record PaymentFailedEvent(
        String eventId,
        SalesType salesType,
        UUID dropId,
        UUID orderId,
        UUID raffleId,
        UUID entryId,
        UUID productId,
        UUID userId,
        UUID couponId,
        String failureReason
) {
}
