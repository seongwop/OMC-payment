package com.omc.payment.application.service;

import com.omc.common.exception.BusinessException;
import com.omc.common.exception.CommonErrorCode;
import com.omc.common.response.ApiResponse;
import com.omc.payment.application.port.out.PaymentGatewayCommand;
import com.omc.payment.application.port.out.PaymentGatewayPort;
import com.omc.payment.application.port.out.PaymentGatewayResult;
import com.omc.payment.domain.entity.Payment;
import com.omc.payment.domain.enums.CancellationCode;
import com.omc.payment.domain.enums.PaymentStatus;
import com.omc.payment.domain.exception.NonRetryablePaymentException;
import com.omc.payment.domain.exception.PaymentCompensatableException;
import com.omc.payment.domain.exception.PaymentErrorCode;
import com.omc.payment.domain.exception.PaymentGatewayCapacityExceededException;
import com.omc.payment.domain.exception.PaymentGatewayConnectionException;
import com.omc.payment.domain.exception.PaymentGatewayRequestException;
import com.omc.payment.domain.exception.RetryablePaymentException;
import com.omc.payment.infrastructure.client.CouponReserveRequest;
import com.omc.payment.infrastructure.client.CouponServiceClient;
import com.omc.payment.infrastructure.client.UserCouponResponse;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentCoreService {

    private final PaymentGatewayPort paymentGatewayPort;
    private final CouponServiceClient couponServiceClient;
    private final PaymentIdempotencyService paymentIdempotencyService;
    private final PaymentTransactionService paymentTransactionService;

    public Payment confirmPayment(
            UUID orderId,
            UUID dropId,
            UUID productId,
            UUID couponId,
            UUID userId,
            Long originalAmount,
            Long discountAmount,
            Long finalAmount,
            String providerPaymentId
    ) {
        // 멱등성 방어 로직
        Payment existingPayment = paymentTransactionService.findByOrderId(orderId);
        Payment payment = existingPayment == null
                ? paymentTransactionService.createDropPayment(
                        orderId,
                        dropId,
                        productId,
                        couponId,
                        userId,
                        originalAmount,
                        discountAmount,
                        finalAmount,
                        providerPaymentId
                )
                : resolvePayment(existingPayment);

        // READY는 후속 처리를 그대로 진행하고 그 외 상태는 그대로 반환 및 PG 호출 방지
        // READY 상태만 아래 로직을 타게됨
        if (payment.getPaymentStatus() != PaymentStatus.READY) {
            return payment;
        }

        try {
            validatePaymentAmounts(originalAmount, discountAmount, finalAmount);
            validateDropPayment(dropId, productId);
            reserveAndValidateCoupon(couponId, orderId, userId, originalAmount, discountAmount);

            Payment confirmingPayment = paymentTransactionService.markConfirming(payment.getPaymentId());
            // 새로운 트랜잭션에 의해 변경될 수 있는 상태 재검증
            if (confirmingPayment.getPaymentStatus() != PaymentStatus.CONFIRMING) {
                return confirmingPayment;
            }
            return confirmWithGateway(
                    confirmingPayment,
                    orderId,
                    finalAmount,
                    resolveProviderPaymentId(confirmingPayment,  providerPaymentId));
        } catch (PaymentCompensatableException e) {
            return paymentTransactionService.failAndSaveOutbox(
                    payment.getPaymentId(),
                    e.getErrorCode().getCode(),
                    e.getMessage()
            );
        }
    }

    public Payment confirmBillingPayment(
            UUID orderId,
            UUID entryId,
            UUID raffleId,
            UUID productId,
            UUID couponId,
            UUID userId,
            String billingKeyId,
            String customerKey,
            Long originalAmount,
            Long discountAmount,
            Long finalAmount
    ) {
        Payment existingPayment = paymentTransactionService.findByOrderId(orderId);
        Payment payment = existingPayment == null
                ? paymentTransactionService.createBillingPayment(
                        orderId,
                        entryId,
                        raffleId,
                        productId,
                        couponId,
                        userId,
                        originalAmount,
                        discountAmount,
                        finalAmount
                )
                : resolvePayment(existingPayment);

        if (payment.getPaymentStatus() != PaymentStatus.READY) {
            return payment;
        }

        String resolvedCustomerKey = customerKey == null || customerKey.isBlank()
                ? UUID.randomUUID().toString()
                : customerKey;

        try {
            validatePaymentAmounts(originalAmount, discountAmount, finalAmount);
            validateRafflePayment(raffleId, entryId, productId, billingKeyId);
            reserveAndValidateCoupon(couponId, orderId, userId, originalAmount, discountAmount);

            Payment confirmingPayment = paymentTransactionService.markConfirming(payment.getPaymentId());
            if (confirmingPayment.getPaymentStatus() != PaymentStatus.CONFIRMING) {
                return confirmingPayment;
            }
            return confirmBillingWithGateway(confirmingPayment, billingKeyId, resolvedCustomerKey, orderId, finalAmount);
        } catch (PaymentCompensatableException e) {
            return paymentTransactionService.failAndSaveOutbox(
                    payment.getPaymentId(),
                    e.getErrorCode().getCode(),
                    e.getMessage()
            );
        }
    }

    // 처리 중인 상태만 재처리로 넘기고 나머지는 그대로 반환
    private Payment resolvePayment(Payment payment) {
        return switch (payment.getPaymentStatus()) {
            case READY, PAID, FAILED, CANCELED, CONFIRM_UNKNOWN, CANCEL_UNKNOWN, RECOVERY_FAILED -> payment;
            case CONFIRMING -> throw new RetryablePaymentException(
                    PaymentErrorCode.PAYMENT_ALREADY_EXISTS,
                    "이미 결제 승인 처리가 진행 중입니다."
            );
        };
    }

    // READY 상태일 경우 조회한 결제에 저장된 값을 그대로 사용
    private String resolveProviderPaymentId(Payment payment, String providerPaymentId) {
        String savedProviderPaymentId = payment.getProviderPaymentId();
        if (savedProviderPaymentId != null && savedProviderPaymentId.isBlank()) {
            return savedProviderPaymentId;
        }
        return providerPaymentId;
    }

    /*
    * 외부 동기 호출 API 전용
    * */
    public Payment cancelPaymentByPaymentId(
            UUID paymentId,
            UUID userId,
            String userRole,
            CancellationCode cancellationCode,
            String reason
    ) {
        Payment payment = paymentTransactionService.findById(paymentId);
        if (payment == null) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND);
        }
        if (payment.getPaymentStatus() == PaymentStatus.CANCELED
                || payment.getPaymentStatus() == PaymentStatus.FAILED) {
            return payment;
        }

        CancellationCode resolvedCancellationCode = resolveCancellationCode(payment, userId, userRole);
        if (isAlreadyCancelled(payment)) {
            return payment;
        }
        validateCancelablePayment(payment);
        String resolvedReason = reason == null || reason.isBlank()
                ? resolveCancellationReason(cancellationCode)
                : reason;

        try {
            String providerCancellationId = cancelWithGateway(payment, resolvedReason);
            return paymentTransactionService.cancelAndSaveOutbox(
                    payment.getPaymentId(),
                    providerCancellationId,
                    resolvedCancellationCode,
                    resolvedReason
            );
        } catch (PaymentGatewayConnectionException e) {
            return paymentTransactionService.markCancelUnknown(payment.getPaymentId(), resolvedCancellationCode, resolvedReason);
        }
    }

    /*
     * 이벤트 비동기 호출 전용
     * */
    public void cancelPaymentByOrderId(UUID orderId, CancellationCode cancellationCode, String reason) {
        Payment payment = paymentTransactionService.findByOrderId(orderId);
        if (payment == null) {
            throw new NonRetryablePaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND);
        }

        if (isAlreadyCancelled(payment)) {
            return;
        }
        validateCancelablePayment(payment);

        String resolvedReason = reason == null || reason.isBlank()
                ? resolveCancellationReason(cancellationCode)
                : reason;

        try {
            String providerCancellationId = cancelWithGateway(payment, resolvedReason);
            paymentTransactionService.cancelAndSaveOutbox(
                    payment.getPaymentId(),
                    providerCancellationId,
                    cancellationCode,
                    resolvedReason
            );
        } catch (PaymentGatewayConnectionException e) {
            paymentTransactionService.markCancelUnknown(payment.getPaymentId(), cancellationCode, resolvedReason);
        }
    }

    private boolean isAlreadyCancelled(Payment payment) {
        return payment.getPaymentStatus() == PaymentStatus.CANCELED
                || payment.getPaymentStatus() == PaymentStatus.FAILED;
    }

    // 처리 중인 결제는 재시도
    private void validateCancelablePayment(Payment payment) {
        if (payment.getPaymentStatus() == PaymentStatus.CONFIRMING) {
            throw new RetryablePaymentException(
                    PaymentErrorCode.PAYMENT_ALREADY_EXISTS,
                    "이미 결제 승인 처리가 진행 중입니다."
            );
        }
    }

    private CancellationCode resolveCancellationCode(Payment payment, UUID userId, String userRole) {
        return switch (userRole) {
            case "ADMIN" -> CancellationCode.ADMIN_CANCEL;
            case "USER" -> {
                if (userId == null || !userId.equals(payment.getUserId())) {
                    throw new BusinessException(CommonErrorCode.ACCESS_DENIED);
                }
                yield CancellationCode.USER_CANCEL;
            }
            default -> throw new BusinessException(CommonErrorCode.ACCESS_DENIED);
        };
    }

    private String resolveCancellationReason(CancellationCode cancellationCode) {
        return cancellationCode == null ? "결제 취소" : cancellationCode.name();
    }

    // confirmPayment PG 연동 로직 분리
    private Payment confirmWithGateway(
            Payment payment,
            UUID orderId,
            Long finalAmount,
            String providerPaymentId
    ) {
        try {
            PaymentGatewayResult.Confirm result = paymentGatewayPort.confirmPayment(
                    new PaymentGatewayCommand.Confirm(
                            providerPaymentId,
                            orderId.toString(),
                            finalAmount,
                            paymentIdempotencyService.confirmKey(orderId)
                    )
            );
            if (result.providerPaymentId() == null || result.providerPaymentId().isBlank()) {
                throw new PaymentGatewayConnectionException("PG 결제 승인 응답에 결제 ID가 없습니다.");
            }
            return paymentTransactionService.approveAndSaveOutbox(payment.getPaymentId(), result.providerPaymentId());
        } catch (PaymentGatewayCapacityExceededException e) {
            paymentTransactionService.markReadyForRetry(payment.getPaymentId());
            throw new RetryablePaymentException(
                    PaymentErrorCode.PAYMENT_GATEWAY_CONNECTION_FAILED,
                    "PG 호출 용량이 확보되지 않아 결제 승인을 재시도합니다."
            );
        } catch (PaymentGatewayRequestException e) {
            /* FAILED 처리 */
            return paymentTransactionService.failAndSaveOutbox(payment.getPaymentId(), e.getProviderErrorCode(), e.getMessage());
        } catch (PaymentGatewayConnectionException e) {
            /* UNKNOWN 처리, 추후 재처리 필요 */
            return paymentTransactionService.markConfirmUnknown(payment.getPaymentId());
        }
    }

    // confirmBillingPayment PG 연동 로직 분리
    private Payment confirmBillingWithGateway(
            Payment payment,
            String billingKeyId,
            String customerKey,
            UUID orderId,
            Long finalAmount
    ) {
        try {
            String resolvedCustomerKey = customerKey == null || customerKey.isBlank()
                    ? UUID.randomUUID().toString()
                    : customerKey;

            PaymentGatewayResult.Confirm result = paymentGatewayPort.confirmBillingPayment(
                    new PaymentGatewayCommand.ConfirmBilling(
                            billingKeyId,
                            resolvedCustomerKey,
                            orderId.toString(),
                            "래플 자동 결제",
                            finalAmount,
                            paymentIdempotencyService.confirmKey(orderId)
                    )
            );
            if (result.providerPaymentId() == null || result.providerPaymentId().isBlank()) {
                throw new PaymentGatewayConnectionException("PG 결제 승인 응답에 결제 ID가 없습니다.");
            }
            return paymentTransactionService.approveAndSaveOutbox(payment.getPaymentId(), result.providerPaymentId());
        } catch (PaymentGatewayCapacityExceededException e) {
            paymentTransactionService.markReadyForRetry(payment.getPaymentId());
            throw new RetryablePaymentException(
                    PaymentErrorCode.PAYMENT_GATEWAY_CONNECTION_FAILED,
                    "PG 호출 용량이 확보되지 않아 결제 승인을 재시도합니다."
            );
        } catch (PaymentGatewayRequestException e) {
            return paymentTransactionService.failAndSaveOutbox(payment.getPaymentId(), e.getProviderErrorCode(), e.getMessage());
        } catch (PaymentGatewayConnectionException e) {
            return paymentTransactionService.markConfirmUnknown(payment.getPaymentId());
        }
    }

    // cancelPayment PG 연동 로직 분리
    private String cancelWithGateway(Payment payment, String cancelReason) {
        try {
            PaymentGatewayResult.Cancel result = paymentGatewayPort.cancelPayment(
                    new PaymentGatewayCommand.Cancel(
                            payment.getProviderPaymentId(),
                            cancelReason,
                            payment.getFinalAmount(),
                            paymentIdempotencyService.cancelKey(payment.getOrderId())
                    )
            );
            return result.providerCancellationId();
        } catch (PaymentGatewayRequestException e) {
            throw new RetryablePaymentException(PaymentErrorCode.PAYMENT_GATEWAY_REQUEST_FAILED, e.getMessage());
        } catch (PaymentGatewayConnectionException e) {
            throw e;
        }
    }

    // PG 연동 전 검증
    private void validatePaymentAmounts(Long originalAmount, Long discountAmount, Long finalAmount) {
        if (originalAmount == null || finalAmount == null) {
            throw new PaymentCompensatableException(
                    PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH,
                    "결제 금액은 필수입니다."
            );
        }
        long resolvedDiscountAmount = discountAmount == null ? 0L : discountAmount;
        if (originalAmount < 0 || resolvedDiscountAmount < 0 || finalAmount < 0) {
            throw new PaymentCompensatableException(
                    PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH,
                    "결제 금액은 0 이상이어야 합니다."
            );
        }
        if (resolvedDiscountAmount > originalAmount || originalAmount - resolvedDiscountAmount != finalAmount) {
            throw new PaymentCompensatableException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
    }

    private void validateDropPayment(UUID dropId, UUID productId) {
        if (dropId == null) {
            throw new PaymentCompensatableException(PaymentErrorCode.PAYMENT_FAILED, "드롭 ID는 필수입니다.");
        }
        if (productId == null) {
            throw new PaymentCompensatableException(PaymentErrorCode.PAYMENT_FAILED, "상품 ID는 필수입니다.");
        }
    }

    private void validateRafflePayment(
            UUID raffleId,
            UUID entryId,
            UUID productId,
            String billingKeyId
    ) {
        if (raffleId == null) {
            throw new PaymentCompensatableException(PaymentErrorCode.PAYMENT_FAILED, "래플 ID는 필수입니다.");
        }
        if (entryId == null) {
            throw new PaymentCompensatableException(PaymentErrorCode.PAYMENT_FAILED, "래플 응모 ID는 필수입니다.");
        }
        if (productId == null) {
            throw new PaymentCompensatableException(PaymentErrorCode.PAYMENT_FAILED, "상품 ID는 필수입니다.");
        }
        if (billingKeyId == null || billingKeyId.isBlank()) {
            throw new PaymentCompensatableException(
                    PaymentErrorCode.PAYMENT_FAILED,
                    "자동결제를 위한 billingKey가 없습니다."
            );
        }
    }

    // 쿠폰이 있으면 결제 직전에 선점하고 응답으로 상태와 할인 금액을 확인
    private void reserveAndValidateCoupon(
            UUID couponId,
            UUID orderId,
            UUID userId,
            Long originalAmount,
            Long discountAmount
    ) {
        long resolvedDiscountAmount = discountAmount == null ? 0L : discountAmount;

        if (couponId == null) {
            if (resolvedDiscountAmount != 0L) {
                throw new PaymentCompensatableException(
                        PaymentErrorCode.PAYMENT_INVALID_COUPON,
                        "쿠폰 없이 할인 금액을 적용할 수 없습니다."
                );
            }
            return;
        }

        UserCouponResponse coupon = reserveCoupon(couponId, orderId, userId);
        if (!"RESERVED".equals(coupon.status())) {
            throw new PaymentCompensatableException(
                    PaymentErrorCode.PAYMENT_INVALID_COUPON,
                    "쿠폰 상태가 RESERVED가 아닙니다."
            );
        }

        long expectedDiscountAmount = calculateCouponDiscountAmount(coupon, originalAmount);
        if (expectedDiscountAmount != resolvedDiscountAmount) {
            throw new PaymentCompensatableException(
                    PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH,
                    "쿠폰 할인 금액이 일치하지 않습니다."
            );
        }
    }

    // coupon-service에서 쿠폰을 선점하고 응답을 결제 검증에 사용
    private UserCouponResponse reserveCoupon(UUID couponId, UUID orderId, UUID userId) {
        try {
            CouponReserveRequest request = new CouponReserveRequest(couponId, orderId, userId);
            ApiResponse<UserCouponResponse> response = couponServiceClient.reserveCoupon(request);
            if (response == null || response.getData() == null) {
                throw new RetryablePaymentException(CommonErrorCode.REMOTE_RESPONSE_PARSE_ERROR, "쿠폰 서비스 응답이 비어 있습니다.");
            }
            return response.getData();
        } catch (FeignException e) {
            throw new RetryablePaymentException(CommonErrorCode.REMOTE_CALL_FAILED, "쿠폰 서비스 호출에 실패했습니다.");
        }
    }

    // 쿠폰 타입에 맞춰 실제 할인 금액을 다시 계산
    private long calculateCouponDiscountAmount(UserCouponResponse coupon, Long originalAmount) {
        BigDecimal originalAmountValue = BigDecimal.valueOf(originalAmount);
        if (coupon.discountValue() == null) {
            throw new PaymentCompensatableException(
                    PaymentErrorCode.PAYMENT_INVALID_COUPON,
                    "쿠폰 할인 값은 필수입니다."
            );
        }

        if ("AMOUNT".equals(coupon.discountType())) {
            return coupon.discountValue().setScale(0, RoundingMode.DOWN).longValue();
        }

        // 소수점 버림 정책 적용
        if ("RATE".equals(coupon.discountType())) {
            BigDecimal calculated = originalAmountValue
                    .multiply(coupon.discountValue())
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN);

            // 최대 할인 금액 적용
            if (coupon.maxDiscountAmount() != null) {
                BigDecimal maxDiscount = coupon.maxDiscountAmount().setScale(0, RoundingMode.DOWN);
                calculated = calculated.min(maxDiscount);
            }

            return calculated.longValue();
        }

        throw new PaymentCompensatableException(
                PaymentErrorCode.PAYMENT_INVALID_COUPON,
                "지원하지 않는 쿠폰 할인 타입입니다."
        );
    }
}
