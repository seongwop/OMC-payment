package com.omc.payment.infrastructure.adapter;

import com.omc.payment.application.port.out.PaymentGatewayCommand;
import com.omc.payment.application.port.out.PaymentGatewayPort;
import com.omc.payment.application.port.out.PaymentGatewayResult;
import com.omc.payment.domain.enums.PaymentGatewayStatus;
import com.omc.payment.domain.exception.PaymentGatewayConnectionException;
import com.omc.payment.domain.exception.PaymentGatewayRequestException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "payment.pg.mode", havingValue = "test")
public class TestPaymentAdapter implements PaymentGatewayPort {

    private static final String CARD_LIMIT_EXCEEDED = "E2E_CARD_LIMIT_EXCEEDED";
    private static final String GATEWAY_CONNECTION_ERROR = "E2E_GATEWAY_CONNECTION_ERROR";

    /*
    * 외부 PG 호출을 제외한 테스트 환경
    * */

    // E2E 식별자에 따라 PG 실패를 재현하고 그 외 요청은 즉시 승인 처리
    @Override
    public PaymentGatewayResult.Confirm confirmPayment(PaymentGatewayCommand.Confirm command) {
        if (CARD_LIMIT_EXCEEDED.equals(command.providerPaymentId())) {
            throw new PaymentGatewayRequestException(
                    "EXCEED_MAX_CARD_LIMIT",
                    "카드 한도를 초과했습니다"
            );
        }

        if (GATEWAY_CONNECTION_ERROR.equals(command.providerPaymentId())) {
            throw new PaymentGatewayConnectionException(
                    "PG 연결에 실패했습니다",
                    new RuntimeException("PG 연결 오류")
            );
        }

        return new PaymentGatewayResult.Confirm("test-payment-" + command.orderId());
    }

    // 가짜 빌링키 발급 처리
    @Override
    public PaymentGatewayResult.RegisterBillingKey registerBillingKey(PaymentGatewayCommand.RegisterBillingKey command) {
        return new PaymentGatewayResult.RegisterBillingKey("test-billing-" + command.customerKey());
    }

    // 빌링키 자동 결제 즉시 승인 처리
    @Override
    public PaymentGatewayResult.Confirm confirmBillingPayment(PaymentGatewayCommand.ConfirmBilling command) {
        return new PaymentGatewayResult.Confirm("test-billing-payment-" + command.orderId());
    }

    // 테스트 PG 결제 조회 즉시 성공 처리
    @Override
    public PaymentGatewayResult.Payment getPayment(PaymentGatewayCommand.GetPayment command) {
        return new PaymentGatewayResult.Payment(
                command.providerPaymentID(),
                "test-order-id",
                PaymentGatewayStatus.PAID,
                10000L,
                10000L,
                "test-transaction-" + command.providerPaymentID()
        );
    }

    // 결제 취소 즉시 성공 처리
    @Override
    public PaymentGatewayResult.Cancel cancelPayment(PaymentGatewayCommand.Cancel command) {
        return new PaymentGatewayResult.Cancel("test-cancel-" + command.providerPaymentId());
    }
}
