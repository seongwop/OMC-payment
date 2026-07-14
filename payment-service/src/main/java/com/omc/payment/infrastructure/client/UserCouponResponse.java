package com.omc.payment.infrastructure.client;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// coupon-service 쿠폰 선점 응답
public record UserCouponResponse(
        UUID userCouponId,
        UUID couponId,
        String couponName,
        String discountType,
        BigDecimal discountValue,
        BigDecimal maxDiscountAmount,
        String status,
        LocalDateTime expiredAt
) {
}
