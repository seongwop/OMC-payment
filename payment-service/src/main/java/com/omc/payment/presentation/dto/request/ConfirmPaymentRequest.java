package com.omc.payment.presentation.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.UUID;

public record ConfirmPaymentRequest(
        @NotNull UUID orderID,
        UUID dropId,
        UUID productId,
        String providerPaymentId, // 클라이언트 서버가 없으므로 검증하지 않고 테스트를 위해 서버에서 랜덤값 생성
        UUID couponID,
        @NotNull @PositiveOrZero Long originalAmount,
        @NotNull @PositiveOrZero Long discountAmount,
        @NotNull @PositiveOrZero Long finalAmount
) {
}
