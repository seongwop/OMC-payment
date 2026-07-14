package com.omc.payment.infrastructure.client;

import java.util.UUID;

public record CouponReserveRequest(
        UUID userCouponId,
        UUID orderId,
        UUID userId
) {
}
