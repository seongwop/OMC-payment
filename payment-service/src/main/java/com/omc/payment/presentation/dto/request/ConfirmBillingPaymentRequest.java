package com.omc.payment.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.UUID;

public record ConfirmBillingPaymentRequest(
        @NotNull UUID orderId,
        @NotNull UUID raffleId,
        @NotNull UUID entryId,
        @NotNull UUID productId,
        UUID couponId,
        @NotNull UUID userId,
        @NotBlank String billingKeyId,
        String customerKey,
        @NotNull @PositiveOrZero Long originalAmount,
        @NotNull @PositiveOrZero Long discountAmount,
        @NotNull @PositiveOrZero Long finalAmount
) {
}
