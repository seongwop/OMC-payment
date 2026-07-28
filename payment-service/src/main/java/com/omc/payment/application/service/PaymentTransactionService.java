package com.omc.payment.application.service;

import com.omc.common.exception.BusinessException;
import com.omc.payment.domain.entity.Payment;
import com.omc.payment.domain.entity.PaymentStatusHistory;
import com.omc.payment.domain.enums.*;
import com.omc.payment.domain.exception.PaymentErrorCode;
import com.omc.payment.domain.exception.RetryablePaymentException;
import com.omc.payment.domain.repository.PaymentRepository;
import com.omc.payment.domain.repository.PaymentStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentTransactionService {

    private final PaymentRepository paymentRepository;
    private final PaymentOutboxService paymentOutboxService;
    private final PaymentStatusHistoryRepository paymentStatusHistoryRepository;

    @Transactional(readOnly = true)
    public Payment findByOrderId(UUID orderId){
        return paymentRepository.findByOrderId(orderId).orElse(null);
    }

    @Transactional(readOnly = true)
    public Payment findById(UUID paymentId) {
        return paymentRepository.findById(paymentId).orElse(null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment createDropPayment(
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
        return paymentRepository.save(
                Payment.create(
                        orderId,
                        dropId,
                        null,
                        null,
                        productId,
                        couponId,
                        userId,
                        SalesType.DROP,
                        originalAmount,
                        discountAmount,
                        finalAmount,
                        Provider.TOSS,
                        providerPaymentId,
                        PaymentMethod.CARD
                )
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment createBillingPayment(
            UUID orderId,
            UUID entryId,
            UUID raffleId,
            UUID productId,
            UUID couponId,
            UUID userId,
            Long originalAmount,
            Long discountAmount,
            Long finalAmount
    ) {
        return paymentRepository.save(
                Payment.create(
                        orderId,
                        null,
                        raffleId,
                        entryId,
                        productId,
                        couponId,
                        userId,
                        SalesType.RAFFLE,
                        originalAmount,
                        discountAmount,
                        finalAmount,
                        Provider.TOSS,
                        null,
                        PaymentMethod.CARD
                )
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment markConfirming(UUID paymentId) {
        Payment payment = getPayment(paymentId);
        // 이미 승인 처리 중이면 중복 PG 호출을 막고 재시도
        // 처리 결과부터 확정
        if (payment.getPaymentStatus() == PaymentStatus.CONFIRMING) {
            throw new RetryablePaymentException(
                    PaymentErrorCode.PAYMENT_ALREADY_EXISTS,
                    "이미 결제 승인 처리가 진행 중입니다."
            );
        }

        if (payment.getPaymentStatus() != PaymentStatus.READY) {
            return payment;
        }
        PaymentStatus previousStatus = payment.getPaymentStatus();
        payment.startConfirming();
        saveStatusHistory(payment, previousStatus, "결제 승인 처리 시작");
        return payment;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markReadyForRetry(UUID paymentId) {
        Payment payment = getPayment(paymentId);
        if (payment.getPaymentStatus() != PaymentStatus.CONFIRMING) {
            return;
        }
        PaymentStatus previousStatus = payment.getPaymentStatus();
        payment.markReadyForRetry();
        saveStatusHistory(payment, previousStatus, "PG 호출 전 동시 요청 제한으로 승인 보류");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment approveAndSaveOutbox(UUID paymentId, String providerPaymentId) {
        Payment payment = getPayment(paymentId);
        // 종료 상태 시 Outbox 중복 저장 방지
        if (payment.getPaymentStatus() == PaymentStatus.PAID
                || payment.getPaymentStatus() == PaymentStatus.FAILED
                || payment.getPaymentStatus() == PaymentStatus.CANCELED
                || payment.getPaymentStatus() == PaymentStatus.RECOVERY_FAILED) {
            return payment;
        }
        PaymentStatus previousStatus = payment.getPaymentStatus();
        payment.approve(providerPaymentId);
        paymentOutboxService.savePaymentCompleted(payment);
        saveStatusHistory(payment, previousStatus, "PG 승인 성공");
        return payment;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment failAndSaveOutbox(UUID paymentId, String failureCode, String failureMessage) {
        Payment payment = getPayment(paymentId);

        if (payment.getPaymentStatus() == PaymentStatus.PAID
                || payment.getPaymentStatus() == PaymentStatus.FAILED
                || payment.getPaymentStatus() == PaymentStatus.CANCELED
                || payment.getPaymentStatus() == PaymentStatus.RECOVERY_FAILED) {
            return payment;
        }
        PaymentStatus previousStatus = payment.getPaymentStatus();
        payment.fail(failureCode, failureMessage);
        paymentOutboxService.savePaymentFailed(payment);
        saveStatusHistory(payment, previousStatus, resolveFailureReason(failureCode, failureMessage));
        return payment;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment markConfirmUnknown(UUID paymentId) {
        Payment payment = getPayment(paymentId);

        if (payment.getPaymentStatus() == PaymentStatus.CONFIRM_UNKNOWN
                || payment.getPaymentStatus() == PaymentStatus.CANCEL_UNKNOWN
                || payment.getPaymentStatus() == PaymentStatus.PAID
                || payment.getPaymentStatus() == PaymentStatus.FAILED
                || payment.getPaymentStatus() == PaymentStatus.CANCELED
                || payment.getPaymentStatus() == PaymentStatus.RECOVERY_FAILED) {
            return payment;
        }
        PaymentStatus previousStatus = payment.getPaymentStatus();
        payment.markConfirmUnknown();
        saveStatusHistory(payment, previousStatus, "PG 승인 결과 미확정");
        return payment;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment markCancelUnknown(UUID paymentId, CancellationCode cancellationCode, String reason) {
        Payment payment = getPayment(paymentId);

        if (payment.getPaymentStatus() == PaymentStatus.CANCEL_UNKNOWN
                || payment.getPaymentStatus() == PaymentStatus.CANCELED
                || payment.getPaymentStatus() == PaymentStatus.FAILED
                || payment.getPaymentStatus() == PaymentStatus.RECOVERY_FAILED) {
            return payment;
        }

        if (payment.getPaymentStatus() == PaymentStatus.CONFIRMING) {
            throw new RetryablePaymentException(
                    PaymentErrorCode.PAYMENT_ALREADY_EXISTS,
                    "이미 결제 승인 처리가 진행 중입니다."
            );
        }
        PaymentStatus previousStatus = payment.getPaymentStatus();
        payment.markCancelUnknown(cancellationCode, reason);
        saveStatusHistory(payment, previousStatus, resolveCancelUnknownReason(reason));
        return payment;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment cancelAndSaveOutbox(
            UUID paymentId,
            String providerCancellationId,
            CancellationCode cancellationCode,
            String reason
    ) {
        Payment payment = getPayment(paymentId);

        if (payment.getPaymentStatus() == PaymentStatus.CONFIRMING) {
            throw new RetryablePaymentException(
                    PaymentErrorCode.PAYMENT_ALREADY_EXISTS,
                    "이미 결제 승인 처리가 진행 중입니다."
            );
        }

        if (payment.getPaymentStatus() == PaymentStatus.CANCELED
                || payment.getPaymentStatus() == PaymentStatus.FAILED
                || payment.getPaymentStatus() == PaymentStatus.RECOVERY_FAILED) {
            return payment;
        }
        PaymentStatus previousStatus = payment.getPaymentStatus();
        payment.cancel(providerCancellationId, cancellationCode, reason);
        paymentOutboxService.saveRefundDone(payment);
        saveStatusHistory(payment, previousStatus, resolveCancelReason(reason));
        return payment;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment markUnknownRecoveryRetry(UUID paymentId, int maxRetryCount) {
        Payment payment = getPayment(paymentId);
        PaymentStatus previousStatus = payment.getPaymentStatus();
        payment.markUnknownRecoveryRetry(maxRetryCount);
        saveStatusHistory(payment, previousStatus, "미확정 결제 재조회 최대 횟수 초과");
        return payment;
    }

    // 결제가 변경된 경우 append-only 적재
    private void saveStatusHistory(Payment payment, PaymentStatus previousStatus, String reason) {
        PaymentStatus currentStatus = payment.getPaymentStatus();
        if (previousStatus == currentStatus) {
            return;
        }
        paymentStatusHistoryRepository.save(
                PaymentStatusHistory.create(
                        payment.getPaymentId(),
                        payment.getOrderId(),
                        previousStatus,
                        currentStatus,
                        reason
                )
        );
    }

    private String resolveFailureReason(String failureCode, String failureMessage) {
        if (failureMessage == null || failureMessage.isBlank()) {
            return "결제 실패: " + failureCode;
        }
        return "결제 실패: " + failureMessage;
    }

    private String resolveCancelUnknownReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "PG 취소 결과 미확정";
        }
        return "PG 취소 결과 미확정: " + reason;
    }

    private String resolveCancelReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "결제 취소";
        }
        return "결제 취소: " + reason;
    }

    private Payment getPayment(UUID paymentId) {
        return paymentRepository.findById(paymentId).orElseThrow(
                () -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND)
        );
    }
}
