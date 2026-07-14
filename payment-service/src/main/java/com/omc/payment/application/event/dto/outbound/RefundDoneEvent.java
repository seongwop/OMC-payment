package com.omc.payment.application.event.dto.outbound;

import java.util.UUID;

public record RefundDoneEvent(
        String eventId,
        UUID orderId,
        UUID userId,
        UUID couponId,
        Long amount,
        String refundReason
) {
}
