package com.omc.paymenttools.verifier.model;

import java.time.LocalDateTime;
import java.util.UUID;

public record PaymentSnapshot(
        UUID paymentId,
        UUID orderId,
        UUID userId,
        String paymentStatus,
        String providerPaymentId,
        String providerCancellationId,
        int unknownRecoveryRetryCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
