package com.omc.payment.application.service;

import com.omc.payment.application.port.out.PaymentGatewayCommand;
import com.omc.payment.application.port.out.PaymentGatewayPort;
import com.omc.payment.application.port.out.PaymentGatewayResult;
import com.omc.payment.domain.entity.Payment;
import com.omc.payment.domain.enums.CancellationCode;
import com.omc.payment.domain.enums.PaymentStatus;
import com.omc.payment.domain.exception.NonRetryablePaymentException;
import com.omc.payment.domain.exception.PaymentErrorCode;
import com.omc.payment.domain.exception.PaymentGatewayConnectionException;
import com.omc.payment.domain.exception.PaymentGatewayRequestException;
import com.omc.payment.infrastructure.repository.PaymentRecoveryClaimRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentUnknownRecoveryService {

    private final PaymentGatewayPort paymentGatewayPort;
    private final PaymentIdempotencyService paymentIdempotencyService;
    private final PaymentTransactionService paymentTransactionService;
    private final PaymentRecoveryClaimRepository paymentRecoveryClaimRepository;

    @Value("${payment.unknown-recovery.max-retry-count:3}")
    private int maxRetryCount;

    @Value("${payment.unknown-recovery.lease-ms:600000}")
    private long recoveryLeaseMs;

    private final String NETWORK_CANCEL_REASON = "PG 승인 성공 후 서비스 완료 처리 실패로 망 취소합니다.";

    public void recoverPendingPayments(int batchSize) {
        if (batchSize <= 0) {
            log.warn(" batchSize가 올바르지 않습니다. batchSize={}", batchSize);
            return;
        }
        // 다중 인스턴스가 같은 UNKNOWN 결제를 조회하지 않도록 만료 시간이 있는 작업 소유권 선점
        String claimOwner = UUID.randomUUID().toString();
        var paymentIds = paymentRecoveryClaimRepository.claimBatch(
                claimOwner,
                batchSize,
                Duration.ofMillis(Math.max(1, recoveryLeaseMs))
        );
        try {
            for (UUID paymentId : paymentIds) {
                recover(paymentId);
            }
        } finally {
            // 복구 결과와 관계없이 현재 실행에서 획득한 작업 소유권 해제
            paymentRecoveryClaimRepository.releaseClaims(claimOwner);
        }
    }

    public void recover(UUID paymentId){
        Payment payment = paymentTransactionService.findById(paymentId);
        if (payment == null || !isRecoverable(payment)) {
            return;
        }
        if (isBlank(payment.getProviderPaymentId())) {
            markRecoveryRetry(payment, "PG 재조회에 필요한 결제 ID가 없습니다.");
            return;
        }

        PaymentGatewayResult.Payment gatewayPayment = getGatewayPayment(payment);
        if (gatewayPayment == null || gatewayPayment.status() == null) {
            markRecoveryRetry(payment, "PG 결제 상태 재조회에 실패했습니다.");
            return;
        }
        if (payment.getPaymentStatus() == PaymentStatus.CONFIRM_UNKNOWN) {
            recoverConfirmUnknown(payment, gatewayPayment);
            return;
        }
        recoverCancelUnknown(payment, gatewayPayment);
    }

    // PG 연동 조회 시도 & 조회 실패 시 추후 재조회
    private PaymentGatewayResult.Payment getGatewayPayment(Payment payment){
        try {
            return paymentGatewayPort.getPayment(
                    new PaymentGatewayCommand.GetPayment(payment.getProviderPaymentId())
            );
        } catch (PaymentGatewayRequestException | PaymentGatewayConnectionException e) {
            log.warn("PG 결제 상태 재조회에 실패했습니다. paymentId={}, providerPaymentId={}",
                    payment.getPaymentId(), payment.getProviderPaymentId(), e);
            return null;
        }
    }

    private void recoverConfirmUnknown(Payment payment, PaymentGatewayResult.Payment gatewayPayment){
        switch (gatewayPayment.status()) {
            case PAID -> recoverPaidConfirmUnknown(payment, gatewayPayment);
            case FAILED -> paymentTransactionService.failAndSaveOutbox(
                    payment.getPaymentId(),
                    PaymentErrorCode.PAYMENT_FAILED.getCode(),
                    "PG 조회 결과 결제 실패"
            );
            case PENDING -> markRecoveryRetry(payment, "PG 승인 결과가 PENDING 상태입니다.");
            case CANCELED -> markRecoveryRetry(payment, "PG 승인 결과가 CANCELED 상태입니다.");
            case UNKNOWN -> markRecoveryRetry(payment, "PG 승인 상태를 확인할 수 없습니다.");
        }
    }

    // PG 조회 결과가 성공이지만 후속 처리에 실패할 경우 망 취소
    private void recoverPaidConfirmUnknown(Payment payment, PaymentGatewayResult.Payment gatewayPayment){
        String providerPaymentId = resolveProviderPaymentId(payment, gatewayPayment);
        try {
            paymentTransactionService.approveAndSaveOutbox(
                    payment.getPaymentId(),
                    providerPaymentId
            );
        } catch (NonRetryablePaymentException e) {
            cancelPaidPayment(payment, providerPaymentId, e.getMessage());
        }
    }

    // PG 연동 망 취소
    private void cancelPaidPayment(Payment payment, String providerPaymentId, String failureReason) {
        try {
            PaymentGatewayResult.Cancel result = paymentGatewayPort.cancelPayment(
                    new PaymentGatewayCommand.Cancel(
                            providerPaymentId,
                            NETWORK_CANCEL_REASON,
                            payment.getFinalAmount(),
                            paymentIdempotencyService.cancelKey(payment.getOrderId())
                    )
            );
            String providerCancellationId = isBlank(result.providerCancellationId())
                    ? providerPaymentId
                    : result.providerCancellationId();
            paymentTransactionService.cancelAndSaveOutbox(
                    payment.getPaymentId(),
                    providerCancellationId,
                    CancellationCode.NETWORK_CANCEL,
                    NETWORK_CANCEL_REASON
            );
            log.warn("PG 승인 성공 후 내부 완료 보정에 실패해 망 취소를 완료했습니다. paymentId={}, reason={}",
                    payment.getPaymentId(), failureReason);
        } catch (PaymentGatewayConnectionException e) { // 네트워크/타임아웃 시 UNKNOWN 처리
            paymentTransactionService.markCancelUnknown(
                    payment.getPaymentId(),
                    CancellationCode.NETWORK_CANCEL,
                    NETWORK_CANCEL_REASON
            );
            log.warn("PG 승인 성공 후 내부 완료 보정에 실패했지만 망 취소 결과를 확인하지 못했습니다. paymentId={}, reason={}",
                    payment.getPaymentId(), failureReason, e);
        } catch (PaymentGatewayRequestException e) { // 요청 오류 시 재시도 후 격리
            markRecoveryRetry(payment, "PG 망 취소 요청에 실패했습니다.");
        }
    }


    private void recoverCancelUnknown(Payment payment, PaymentGatewayResult.Payment gatewayPayment){
        switch (gatewayPayment.status()) {
            case CANCELED -> paymentTransactionService.cancelAndSaveOutbox(
                    payment.getPaymentId(),
                    resolveProviderCancellationId(payment, gatewayPayment),
                    payment.getCancellationCode(),
                    resolveCancellationReason(payment)
            );
            case PAID -> markRecoveryRetry(payment, "PG 취소 결과가 PAID 상태입니다.");
            case PENDING -> markRecoveryRetry(payment, "PG 취소 결과가 PENDING 상태입니다.");
            case FAILED -> markRecoveryRetry(payment, "PG 취소 결과가 FAILED 상태입니다.");
            case UNKNOWN -> markRecoveryRetry(payment, "PG 취소 상태를 확인할 수 없습니다.");
        }
    }

    // 확정하지 못한 재조회 결과를 기록하고 최대 횟수 초과 시 격리 상태로 전환
    private void markRecoveryRetry(Payment payment, String reason) {
        Payment retryPayment = paymentTransactionService.markUnknownRecoveryRetry(
                payment.getPaymentId(),
                maxRetryCount
        );
        if (retryPayment.getPaymentStatus() == PaymentStatus.RECOVERY_FAILED) {
            log.error("UNKNOWN 결제 후속 재조회 최대 횟수를 초과했습니다. paymentId={}, status={}, retryCount={}, reason={}",
                    retryPayment.getPaymentId(),
                    retryPayment.getPaymentStatus(),
                    retryPayment.getUnknownRecoveryRetryCount(),
                    reason);
            return;
        }
        log.warn("UNKNOWN 결제 후속 재조회를 다음 주기에 다시 시도합니다. paymentId={}, status={}, retryCount={}, reason={}",
                retryPayment.getPaymentId(),
                retryPayment.getPaymentStatus(),
                retryPayment.getUnknownRecoveryRetryCount(),
                reason);
    }

    // PG 조회 응답의 결제 ID가 비어 있으면 기존 저장 값을 사용
    private String resolveProviderPaymentId(Payment payment, PaymentGatewayResult.Payment gatewayPayment) {
        if (!isBlank(gatewayPayment.providerPaymentId())) {
            return gatewayPayment.providerPaymentId();
        }
        return payment.getProviderPaymentId();
    }

    // 취소 트랜잭션 ID가 없으면 기존 취소 ID 또는 결제 ID로 대체
    private String resolveProviderCancellationId(Payment payment, PaymentGatewayResult.Payment gatewayPayment) {
        if (!isBlank(gatewayPayment.providerTransactionId())) {
            return gatewayPayment.providerTransactionId();
        }
        if (!isBlank(payment.getProviderCancellationId())) {
            return payment.getProviderCancellationId();
        }
        return resolveProviderPaymentId(payment, gatewayPayment);
    }

    // 기존 취소 사유가 없으면 취소 코드명으로 환불 사유 보정
    private String resolveCancellationReason(Payment payment) {
        if (!isBlank(payment.getCancelledMessage())) {
            return payment.getCancelledMessage();
        }
        return payment.getCancellationCode().name();
    }

    private boolean isRecoverable(Payment payment) {
        return payment.getPaymentStatus() == PaymentStatus.CONFIRM_UNKNOWN
                || payment.getPaymentStatus() == PaymentStatus.CANCEL_UNKNOWN;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
