package com.omc.payment.infrastructure.adapter;

import com.omc.payment.application.port.out.PaymentGatewayCommand;
import com.omc.payment.application.port.out.PaymentGatewayResult;
import com.omc.payment.domain.enums.PaymentGatewayStatus;
import com.omc.payment.domain.exception.PaymentGatewayConnectionException;
import com.omc.payment.domain.exception.PaymentGatewayRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("테스트 결제 어댑터 테스트")
class TestPaymentAdapterTest {

    private final TestPaymentAdapter testPaymentAdapter = new TestPaymentAdapter();

    @Test
    @DisplayName("일반 결제 요청은 즉시 승인한다")
    void confirmPayment_success() {
        PaymentGatewayResult.Confirm result = testPaymentAdapter.confirmPayment(
                command("결제 승인 아이디")
        );

        assertThat(result.providerPaymentId()).isEqualTo("test-payment-주문 아이디");
    }

    @Test
    @DisplayName("결제 조회 요청은 결제 완료 상태를 반환한다")
    void getPayment_success() {
        PaymentGatewayResult.Payment result = testPaymentAdapter.getPayment(
                new PaymentGatewayCommand.GetPayment("test-payment-key")
        );

        assertThat(result.providerPaymentId()).isEqualTo("test-payment-key");
        assertThat(result.orderId()).isEqualTo("test-order-id");
        assertThat(result.status()).isEqualTo(PaymentGatewayStatus.PAID);
        assertThat(result.totalAmount()).isEqualTo(10000L);
        assertThat(result.cancelableAmount()).isEqualTo(10000L);
    }

    @Test
    @DisplayName("카드 한도 초과 식별자는 PG 요청 실패를 발생시킨다")
    void confirmPayment_cardLimitExceeded() {
        assertThatThrownBy(() -> testPaymentAdapter.confirmPayment(
                command("E2E_CARD_LIMIT_EXCEEDED")
        ))
                .isInstanceOf(PaymentGatewayRequestException.class)
                .hasMessage("카드 한도를 초과했습니다")
                .satisfies(exception -> assertThat(
                        ((PaymentGatewayRequestException) exception).getProviderErrorCode()
                ).isEqualTo("EXCEED_MAX_CARD_LIMIT"));
    }

    @Test
    @DisplayName("PG 연결 오류 식별자는 통신 실패를 발생시킨다")
    void confirmPayment_gatewayConnectionError() {
        assertThatThrownBy(() -> testPaymentAdapter.confirmPayment(
                command("E2E_GATEWAY_CONNECTION_ERROR")
        ))
                .isInstanceOf(PaymentGatewayConnectionException.class)
                .hasMessage("PG 연결에 실패했습니다");
    }

    private PaymentGatewayCommand.Confirm command(String providerPaymentId) {
        return new PaymentGatewayCommand.Confirm(
                providerPaymentId,
                "주문 아이디",
                10000L,
                "멱등성 키"
        );
    }
}
