package com.omc.payment.application.service;

import com.omc.payment.application.port.out.PaymentGatewayCommand;
import com.omc.payment.application.port.out.PaymentGatewayPort;
import com.omc.payment.application.port.out.PaymentGatewayResult;
import com.omc.payment.domain.entity.Payment;
import com.omc.payment.domain.entity.PaymentReconciliationResult;
import com.omc.payment.domain.enums.PaymentGatewayStatus;
import com.omc.payment.domain.enums.PaymentReconciliationResultType;
import com.omc.payment.domain.enums.PaymentStatus;
import com.omc.payment.domain.exception.PaymentGatewayConnectionException;
import com.omc.payment.domain.exception.PaymentGatewayRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentReconciliationService {

    private final PaymentGatewayPort paymentGatewayPort;

    /*
    * 배치 데이터를 저장하는 경우
    * PG 조회 실패 / 응답 없음
    * 상태 해석 불가
    * 주문 ID / 금액 / 상태 불일치
    * */
    public Optional<PaymentReconciliationResult> reconcile(Payment payment) {
        if (payment == null || isBlank(payment.getProviderPaymentId())) {
            return Optional.empty();
        }

        PaymentGatewayResult.Payment gatewayPayment;
        try {
            gatewayPayment = paymentGatewayPort.getPayment(
                    new PaymentGatewayCommand.GetPayment(payment.getProviderPaymentId())
            );
        } catch (PaymentGatewayRequestException | PaymentGatewayConnectionException e) {
            log.warn("PG-DB 결제 대조 중 PG 조회에 실패했습니다. paymentId={}, providerPaymentId={}",
                    payment.getPaymentId(), payment.getProviderPaymentId(), e);
            return Optional.of(
                    PaymentReconciliationResult.create(
                            payment,
                            null,
                            null,
                            PaymentReconciliationResultType.PG_LOOKUP_FAILED
                    )
            );
        }

        if (gatewayPayment == null|| gatewayPayment.status() == null) {
            return Optional.of(PaymentReconciliationResult.create(
                    payment,
                    null,
                    null,
                    PaymentReconciliationResultType.PG_LOOKUP_FAILED
            ));
        }
        // PG 상태를 해석하지 못한 경우에는 강한 불일치가 아니라 조회 실패로 기록
        if (gatewayPayment.status() == PaymentGatewayStatus.UNKNOWN) {
            return Optional.of(PaymentReconciliationResult.create(
                    payment,
                    gatewayPayment.status().name(),
                    gatewayPayment.totalAmount(),
                    PaymentReconciliationResultType.PG_LOOKUP_FAILED
            ));
        }
        if (isOrderIdMismatch(payment, gatewayPayment)) {
            return Optional.of(PaymentReconciliationResult.create(
                    payment,
                    gatewayPayment.status().name(),
                    gatewayPayment.totalAmount(),
                    PaymentReconciliationResultType.ORDER_ID_MISMATCH
            ));
        }
        if (isAmountMismatch(payment, gatewayPayment)) {
            return Optional.of(PaymentReconciliationResult.create(
                    payment,
                    gatewayPayment.status().name(),
                    gatewayPayment.totalAmount(),
                    PaymentReconciliationResultType.AMOUNT_MISMATCH
            ));
        }
        if (isStatusMismatch(payment.getPaymentStatus(), gatewayPayment.status())) {
            return Optional.of(PaymentReconciliationResult.create(
                    payment,
                    gatewayPayment.status().name(),
                    gatewayPayment.totalAmount(),
                    PaymentReconciliationResultType.STATUS_MISMATCH
            ));
        }
        return Optional.empty();
    }

    private boolean isOrderIdMismatch(Payment payment, PaymentGatewayResult.Payment gatewayPayment) {
        return !isBlank(gatewayPayment.orderId())
                && !payment.getOrderId().toString().equals(gatewayPayment.orderId());
    }

    private boolean isAmountMismatch(Payment payment, PaymentGatewayResult.Payment gatewayPayment) {
        return gatewayPayment.totalAmount() != null
                && !payment.getFinalAmount().equals(gatewayPayment.totalAmount());
    }

    private boolean isStatusMismatch(
            PaymentStatus dbStatus,
            PaymentGatewayStatus pgStatus
    ) {
        return switch (dbStatus) {
            case PAID -> pgStatus != PaymentGatewayStatus.PAID;
            case CANCELED -> pgStatus != PaymentGatewayStatus.CANCELED;
            /*
            * FAILED의 경우 PG 호출 전 실패도 포함하기 때문에
            * PG의 PENDING/UNKNOWN 상태는 불일치 확정 X
            * */
            case FAILED -> pgStatus ==  PaymentGatewayStatus.PAID
                    || pgStatus == PaymentGatewayStatus.CANCELED;
            case CONFIRM_UNKNOWN, CANCEL_UNKNOWN, RECOVERY_FAILED -> pgStatus ==  PaymentGatewayStatus.PAID
                    || pgStatus == PaymentGatewayStatus.FAILED
                    || pgStatus == PaymentGatewayStatus.CANCELED;
            case READY, CONFIRMING -> false;
        };
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
