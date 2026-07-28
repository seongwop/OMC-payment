package com.omc.paymenttools.event;

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
        String billingKeyId,
        String providerPaymentId
) {

    public OrderCreatedEvent(
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
        this(
                eventId,
                orderId,
                userId,
                orderType,
                dropId,
                productId,
                raffleId,
                entryId,
                originalAmount,
                discountAmount,
                finalAmount,
                couponId,
                billingKeyId,
                null
        );
    }
}
