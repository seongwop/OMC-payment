package com.omc.payment.domain.entity;

import com.omc.common.entity.BaseEntity;
import com.omc.common.util.UuidV7Generator;
import com.omc.payment.domain.enums.CancellationCode;
import com.omc.payment.domain.enums.PaymentMethod;
import com.omc.payment.domain.enums.PaymentStatus;
import com.omc.payment.domain.enums.Provider;
import com.omc.payment.domain.enums.SalesType;
import com.omc.payment.domain.exception.NonRetryablePaymentException;
import com.omc.payment.domain.exception.PaymentErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "p_payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseEntity {

    @Id
    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;

    @Column(name = "drop_id")
    private UUID dropId;

    @Column(name = "raffle_id")
    private UUID raffleId;

    @Column(name = "entry_id", unique = true)
    private UUID entryId;

    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "coupon_id")
    private UUID couponId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "sales_type", nullable = false)
    @Enumerated(EnumType.STRING)
    private SalesType salesType;

    @PositiveOrZero
    @Column(name = "original_amount", nullable = false)
    private Long originalAmount;

    @PositiveOrZero
    @Column(name = "discount_amount")
    private Long discountAmount;

    @PositiveOrZero
    @Column(name = "final_amount", nullable = false)
    private Long finalAmount;

    @Column(name = "provider", nullable = false)
    @Enumerated(EnumType.STRING)
    private Provider provider;

    @Column(name = "provider_payment_id")
    private String providerPaymentId;

    @Column(name = "provider_cancellation_id")
    private String providerCancellationId;

    @Column(name = "payment_method", nullable = false)
    @Enumerated(EnumType.STRING)
    private PaymentMethod paymentMethod;

    @Column(name = "payment_status", nullable = false)
    @Enumerated(EnumType.STRING)
    private PaymentStatus paymentStatus = PaymentStatus.READY;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_message", columnDefinition = "TEXT")
    private String failureMessage;

    @Column(name = "cancellation_code")
    @Enumerated(EnumType.STRING)
    private CancellationCode cancellationCode;

    @Column(name = "cancelled_message")
    private String cancelledMessage;

    @Column(name = "unknown_recovery_retry_count", nullable = false)
    private int unknownRecoveryRetryCount;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "requested_at")
    private LocalDateTime requestedAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    public static Payment create(
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
            PaymentMethod paymentMethod
    ) {
        /*
        * 필수적인 검증만 진행하고 가능한 비즈니스 실패로 빼서 Outbox 발행까지 진행
        * */
        long resolvedOriginalAmount = originalAmount == null ? 0L : originalAmount;
        long resolvedDiscountAmount = discountAmount == null ? 0L : discountAmount;
        long resolvedFinalAmount = finalAmount == null ? 0L : finalAmount;

        SalesType resolvedSalesType = require(
                salesType,
                PaymentErrorCode.PAYMENT_FAILED,
                "판매 유형은 null일 수 없습니다."
        );
        return Payment.builder()
                .paymentId(UuidV7Generator.generate())
                .orderId(require(orderId, PaymentErrorCode.PAYMENT_FAILED, "주문 ID는 null일 수 없습니다."))
                .dropId(dropId)
                .raffleId(raffleId)
                .entryId(entryId)
                .productId(productId)
                .couponId(couponId)
                .userId(require(userId, PaymentErrorCode.PAYMENT_FAILED, "유저 ID는 null일 수 없습니다."))
                .salesType(resolvedSalesType)
                .originalAmount(resolvedOriginalAmount)
                .discountAmount(resolvedDiscountAmount)
                .finalAmount(resolvedFinalAmount)
                .provider(require(provider, PaymentErrorCode.PAYMENT_FAILED, "결제 제공자는 null일 수 없습니다."))
                .providerPaymentId(providerPaymentId)
                .paymentMethod(require(paymentMethod, PaymentErrorCode.PAYMENT_FAILED, "결제 수단은 null일 수 없습니다."))
                .paymentStatus(PaymentStatus.READY)
                .requestedAt(LocalDateTime.now())
                .build();
    }

    // 결제 생성 전 검증 실패는 Outbox 발행 불가
    // DLT로 바로 발행
    private static <T> T require(T value, PaymentErrorCode errorCode, String message) {
        if (value == null) {
            throw new NonRetryablePaymentException(errorCode, message);
        }
        return value;
    }

    // 결제 승인 요청 이벤트 처리
    public void startConfirming() {
        transitTo(PaymentStatus.CONFIRMING);
    }

    // PG 호출 전에 동시 요청 제한으로 차단된 결제를 재시도 가능한 상태로 복구
    public void markReadyForRetry() {
        transitTo(PaymentStatus.READY);
    }

    // PG 승인 성공 이벤트 반영
    public void approve(String providerPaymentId) {
        if (providerPaymentId == null || providerPaymentId.isBlank()) {
            throw new NonRetryablePaymentException(
                    PaymentErrorCode.PAYMENT_GATEWAY_CONNECTION_FAILED,
                    "PG 결제 ID는 필수입니다."
            );
        }
        transitTo(PaymentStatus.PAID);
        this.providerPaymentId = providerPaymentId;
        this.approvedAt = LocalDateTime.now();
        this.failedAt = null;
        this.failureCode = null;
        this.failureMessage = null;
    }

    // PG 승인 실패 이벤트 반영
    public void fail(String failureCode, String failureMessage) {
        transitTo(PaymentStatus.FAILED);
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.failedAt = LocalDateTime.now();
    }

    // PG 응답 지연 또는 확인 불가 이벤트 반영
    // PG 승인 결과를 확인할 수 없는 상태로 변경
    public void markConfirmUnknown() {
        transitTo(PaymentStatus.CONFIRM_UNKNOWN);
    }

    // PG 취소 결과를 확인할 수 없는 상태로 변경
    public void markCancelUnknown(CancellationCode cancellationCode, String cancelledMessage) {
        transitTo(PaymentStatus.CANCEL_UNKNOWN);
        this.cancellationCode = cancellationCode;
        this.cancelledMessage = cancelledMessage;
    }

    // 결제 취소 또는 환불 이벤트 반영
    public void cancel(
            String providerCancellationId,
            CancellationCode cancellationCode,
            String cancelledMessage
    ) {
        transitTo(PaymentStatus.CANCELED);
        this.providerCancellationId = providerCancellationId;
        this.cancellationCode = cancellationCode;
        this.cancelledMessage = cancelledMessage;
        this.canceledAt = LocalDateTime.now();
    }

    // 재시도 실패 횟수 증가 및 최대 시도 검증
    public void markUnknownRecoveryRetry(int maxRetryCount) {
        if (paymentStatus != PaymentStatus.CONFIRM_UNKNOWN
                && paymentStatus != PaymentStatus.CANCEL_UNKNOWN) {
            return;
        }
        this.unknownRecoveryRetryCount += 1;
        if (unknownRecoveryRetryCount >= Math.max(1, maxRetryCount)) {
            transitTo(PaymentStatus.RECOVERY_FAILED);
        }
    }

    private void transitTo(PaymentStatus targetStatus) {
        if (paymentStatus == targetStatus) {
            return;
        }
        if (!paymentStatus.canChangeTo(targetStatus)) {
            throw new NonRetryablePaymentException(
                    PaymentErrorCode.PAYMENT_INVALID_STATUS,
                    "결제 상태를 " + paymentStatus + "에서 " + targetStatus + "로 변경할 수 없습니다."
            );
        }
        this.paymentStatus = targetStatus;
    }

    @Builder(access = AccessLevel.PRIVATE)
    private Payment(
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
            Long version,
            LocalDateTime requestedAt,
            LocalDateTime approvedAt,
            LocalDateTime failedAt,
            LocalDateTime canceledAt
    ) {
        this.paymentId = paymentId;
        this.orderId = orderId;
        this.dropId = dropId;
        this.raffleId = raffleId;
        this.entryId = entryId;
        this.productId = productId;
        this.couponId = couponId;
        this.userId = userId;
        this.salesType = salesType;
        this.originalAmount = originalAmount;
        this.discountAmount = discountAmount;
        this.finalAmount = finalAmount;
        this.provider = provider;
        this.providerPaymentId = providerPaymentId;
        this.providerCancellationId = providerCancellationId;
        this.paymentMethod = paymentMethod;
        this.paymentStatus = paymentStatus;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.cancellationCode = cancellationCode;
        this.cancelledMessage = cancelledMessage;
        this.version = version;
        this.requestedAt = requestedAt;
        this.approvedAt = approvedAt;
        this.failedAt = failedAt;
        this.canceledAt = canceledAt;
    }
}
