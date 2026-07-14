package com.omc.payment.presentation.dto.response;

import com.omc.payment.domain.entity.Payment;
import com.omc.payment.domain.enums.CancellationCode;
import com.omc.payment.domain.enums.PaymentMethod;
import com.omc.payment.domain.enums.PaymentStatus;
import com.omc.payment.domain.enums.Provider;
import com.omc.payment.domain.enums.SalesType;

import java.time.LocalDateTime;
import java.util.UUID;

public record PaymentDetailResponse(
        UUID paymentId,
        UUID orderId,
        UUID dropId,
        UUID raffleId,
        UUID entryId,
        UUID productId,
        UUID couponId,
        UUID userId,
        SalesType salesType,
        Long originalAmount,
        Long discountAmount,
        Long finalAmount,
        Provider provider,
        String providerPaymentId,
        String providerCancellationId,
        PaymentMethod paymentMethod,
        PaymentStatus paymentStatus,
        String failureCode,
        String failureMessage,
        CancellationCode cancellationCode,
        String cancelledMessage,
        LocalDateTime requestedAt,
        LocalDateTime approvedAt,
        LocalDateTime failedAt,
        LocalDateTime canceledAt
) {
    public static PaymentDetailResponse from(Payment payment) {
        return new PaymentDetailResponse(
                payment.getPaymentId(),
                payment.getOrderId(),
                payment.getDropId(),
                payment.getRaffleId(),
                payment.getEntryId(),
                payment.getProductId(),
                payment.getCouponId(),
                payment.getUserId(),
                payment.getSalesType(),
                payment.getOriginalAmount(),
                payment.getDiscountAmount(),
                payment.getFinalAmount(),
                payment.getProvider(),
                payment.getProviderPaymentId(),
                payment.getProviderCancellationId(),
                payment.getPaymentMethod(),
                payment.getPaymentStatus(),
                payment.getFailureCode(),
                payment.getFailureMessage(),
                payment.getCancellationCode(),
                payment.getCancelledMessage(),
                payment.getRequestedAt(),
                payment.getApprovedAt(),
                payment.getFailedAt(),
                payment.getCanceledAt()
        );
    }
}
