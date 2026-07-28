package com.omc.paymenttools.driver.dto;

import com.omc.paymenttools.event.OrderCreatedEvent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.UUID;

public record OrderCreatedRequest(
        String eventId,
        @NotNull(message = "주문 ID는 필수입니다.") UUID orderId,
        @NotNull(message = "사용자 ID는 필수입니다.") UUID userId,
        @NotBlank(message = "주문 유형은 필수입니다.") String orderType,
        UUID dropId,
        UUID productId,
        UUID raffleId,
        UUID entryId,
        @NotNull(message = "원 결제 금액은 필수입니다.")
        @PositiveOrZero(message = "원 결제 금액은 0 이상이어야 합니다.") Long originalAmount,
        @NotNull(message = "할인 금액은 필수입니다.")
        @PositiveOrZero(message = "할인 금액은 0 이상이어야 합니다.") Long discountAmount,
        @NotNull(message = "최종 결제 금액은 필수입니다.")
        @PositiveOrZero(message = "최종 결제 금액은 0 이상이어야 합니다.") Long finalAmount,
        UUID couponId,
        String billingKeyId,
        String providerPaymentId
) {

    // 결제 서비스의 주문 생성 이벤트 계약으로 변환
    public OrderCreatedEvent toEvent(String resolvedEventId) {
        return new OrderCreatedEvent(
                resolvedEventId,
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
                providerPaymentId
        );
    }
}
