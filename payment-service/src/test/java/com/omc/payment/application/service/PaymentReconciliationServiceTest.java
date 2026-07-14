package com.omc.payment.application.service;

import com.omc.payment.application.port.out.PaymentGatewayCommand;
import com.omc.payment.application.port.out.PaymentGatewayPort;
import com.omc.payment.application.port.out.PaymentGatewayResult;
import com.omc.payment.domain.entity.Payment;
import com.omc.payment.domain.entity.PaymentReconciliationResult;
import com.omc.payment.domain.enums.PaymentGatewayStatus;
import com.omc.payment.domain.enums.PaymentMethod;
import com.omc.payment.domain.enums.PaymentReconciliationResultType;
import com.omc.payment.domain.enums.Provider;
import com.omc.payment.domain.enums.SalesType;
import com.omc.payment.domain.exception.PaymentGatewayConnectionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("PG-DB 결제 상태 대조 서비스 테스트")
class PaymentReconciliationServiceTest {

    private static final UUID PAYMENT_ID = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final String PROVIDER_PAYMENT_ID = "payment-key";

    @Mock private PaymentGatewayPort paymentGatewayPort;

    private PaymentReconciliationService paymentReconciliationService;

    @BeforeEach
    void setUp() {
        paymentReconciliationService = new PaymentReconciliationService(paymentGatewayPort);
    }

    @Test
    @DisplayName("DB와 PG 상태가 일치하면 대조 결과를 저장하지 않는다")
    void reconcile_matched_returnsEmpty() {
        Payment payment = paidPayment();
        given(paymentGatewayPort.getPayment(any(PaymentGatewayCommand.GetPayment.class)))
                .willReturn(gatewayPayment(PaymentGatewayStatus.PAID, ORDER_ID.toString(), 10000L, 10000L));

        Optional<PaymentReconciliationResult> result = paymentReconciliationService.reconcile(payment);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("DB와 PG 상태가 다르면 상태 불일치 결과를 반환한다")
    void reconcile_statusMismatch_returnsResult() {
        Payment payment = paidPayment();
        given(paymentGatewayPort.getPayment(any(PaymentGatewayCommand.GetPayment.class)))
                .willReturn(gatewayPayment(PaymentGatewayStatus.CANCELED, ORDER_ID.toString(), 10000L, 0L));

        PaymentReconciliationResult result = paymentReconciliationService.reconcile(payment).orElseThrow();

        assertThat(result.getResultType()).isEqualTo(PaymentReconciliationResultType.STATUS_MISMATCH);
        assertThat(result.getDbStatus()).isEqualTo(payment.getPaymentStatus());
        assertThat(result.getPgStatus()).isEqualTo(PaymentGatewayStatus.CANCELED.name());
    }

    @Test
    @DisplayName("DB와 PG 금액이 다르면 금액 불일치 결과를 반환한다")
    void reconcile_amountMismatch_returnsResult() {
        Payment payment = paidPayment();
        given(paymentGatewayPort.getPayment(any(PaymentGatewayCommand.GetPayment.class)))
                .willReturn(gatewayPayment(PaymentGatewayStatus.PAID, ORDER_ID.toString(), 9000L, 9000L));

        PaymentReconciliationResult result = paymentReconciliationService.reconcile(payment).orElseThrow();

        assertThat(result.getResultType()).isEqualTo(PaymentReconciliationResultType.AMOUNT_MISMATCH);
        assertThat(result.getDbAmount()).isEqualTo(10000L);
        assertThat(result.getPgAmount()).isEqualTo(9000L);
    }

    @Test
    @DisplayName("PG 조회에 실패하면 조회 실패 결과를 반환한다")
    void reconcile_gatewayLookupFailed_returnsResult() {
        Payment payment = paidPayment();
        given(paymentGatewayPort.getPayment(any(PaymentGatewayCommand.GetPayment.class)))
                .willThrow(new PaymentGatewayConnectionException("PG 연결 실패"));

        PaymentReconciliationResult result = paymentReconciliationService.reconcile(payment).orElseThrow();

        assertThat(result.getResultType()).isEqualTo(PaymentReconciliationResultType.PG_LOOKUP_FAILED);
        ArgumentCaptor<PaymentGatewayCommand.GetPayment> captor = ArgumentCaptor.forClass(PaymentGatewayCommand.GetPayment.class);
        verify(paymentGatewayPort).getPayment(captor.capture());
        assertThat(captor.getValue().providerPaymentID()).isEqualTo(PROVIDER_PAYMENT_ID);
    }

    @Test
    @DisplayName("PG 상태를 해석할 수 없으면 조회 실패 결과를 반환한다")
    void reconcile_unknownGatewayStatus_returnsLookupFailedResult() {
        Payment payment = paidPayment();
        given(paymentGatewayPort.getPayment(any(PaymentGatewayCommand.GetPayment.class)))
                .willReturn(gatewayPayment(PaymentGatewayStatus.UNKNOWN, ORDER_ID.toString(), 10000L, 10000L));

        PaymentReconciliationResult result = paymentReconciliationService.reconcile(payment).orElseThrow();

        assertThat(result.getResultType()).isEqualTo(PaymentReconciliationResultType.PG_LOOKUP_FAILED);
        assertThat(result.getPgStatus()).isEqualTo(PaymentGatewayStatus.UNKNOWN.name());
    }

    private Payment paidPayment() {
        Payment payment = createPayment();
        payment.startConfirming();
        payment.approve(PROVIDER_PAYMENT_ID);
        return payment;
    }

    private Payment createPayment() {
        Payment payment = Payment.create(
                ORDER_ID,
                UUID.randomUUID(),
                null,
                null,
                PRODUCT_ID,
                null,
                USER_ID,
                SalesType.DROP,
                10000L,
                0L,
                10000L,
                Provider.TOSS,
                PROVIDER_PAYMENT_ID,
                PaymentMethod.CARD
        );
        ReflectionTestUtils.setField(payment, "paymentId", PAYMENT_ID);
        return payment;
    }

    private PaymentGatewayResult.Payment gatewayPayment(
            PaymentGatewayStatus status,
            String orderId,
            Long totalAmount,
            Long cancelableAmount
    ) {
        return new PaymentGatewayResult.Payment(
                PROVIDER_PAYMENT_ID,
                orderId,
                status,
                totalAmount,
                cancelableAmount,
                "transaction-key"
        );
    }
}