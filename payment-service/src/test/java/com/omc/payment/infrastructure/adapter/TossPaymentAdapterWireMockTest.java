package com.omc.payment.infrastructure.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.omc.payment.application.port.out.PaymentGatewayCommand;
import com.omc.payment.application.port.out.PaymentGatewayResult;
import com.omc.payment.domain.enums.PaymentGatewayStatus;
import com.omc.payment.domain.exception.PaymentGatewayConnectionException;
import com.omc.payment.domain.exception.PaymentGatewayRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Toss 결제 어댑터 WireMock 테스트")
class TossPaymentAdapterWireMockTest {

    private static final String SECRET_KEY = "test-secret-key";

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private TossPaymentAdapter tossPaymentAdapter;

    @BeforeEach
    void setUp() {
        RestClient restClient = RestClient.builder()
                .baseUrl(wireMock.baseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeaders(headers -> headers.setBasicAuth(SECRET_KEY, ""))
                .build();

        tossPaymentAdapter = new TossPaymentAdapter(restClient, new ObjectMapper());
    }

    @Test
    @DisplayName("일반 결제 승인 요청은 Toss 승인 API를 호출하고 paymentKey를 반환한다")
    void confirmPayment_success() {
        wireMock.stubFor(post(urlEqualTo("/v1/payments/confirm"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo(basicAuthValue()))
                .withHeader("Idempotency-Key", equalTo("payment:confirm:order-id"))
                .withRequestBody(equalToJson("""
                        {
                          "paymentKey": "toss-payment-key",
                          "orderId": "order-id",
                          "amount": 10000
                        }
                        """))
                .willReturn(okJson("""
                        {
                          "paymentKey": "toss-payment-key",
                          "lastTransactionKey": "confirm-transaction-key"
                        }
                        """)));

        PaymentGatewayResult.Confirm result = tossPaymentAdapter.confirmPayment(
                new PaymentGatewayCommand.Confirm(
                        "toss-payment-key",
                        "order-id",
                        10000L,
                        "payment:confirm:order-id"
                )
        );

        assertThat(result.providerPaymentId()).isEqualTo("toss-payment-key");
        wireMock.verify(postRequestedFor(urlEqualTo("/v1/payments/confirm")));
    }

    @Test
    @DisplayName("결제 조회 요청은 Toss 조회 API를 호출하고 PG 결제 상태를 반환한다")
    void getPayment_success() {
        wireMock.stubFor(get(urlEqualTo("/v1/payments/toss-payment-key"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo(basicAuthValue()))
                .willReturn(okJson("""
                        {
                          "paymentKey": "toss-payment-key",
                          "orderId": "order-id",
                          "status": "DONE",
                          "totalAmount": 10000,
                          "balanceAmount": 10000,
                          "lastTransactionKey": "confirm-transaction-key"
                        }
                        """)));

        PaymentGatewayResult.Payment result = tossPaymentAdapter.getPayment(
                new PaymentGatewayCommand.GetPayment("toss-payment-key")
        );

        assertThat(result.providerPaymentId()).isEqualTo("toss-payment-key");
        assertThat(result.orderId()).isEqualTo("order-id");
        assertThat(result.status()).isEqualTo(PaymentGatewayStatus.PAID);
        assertThat(result.totalAmount()).isEqualTo(10000L);
        assertThat(result.cancelableAmount()).isEqualTo(10000L);
        assertThat(result.providerTransactionId()).isEqualTo("confirm-transaction-key");
        wireMock.verify(getRequestedFor(urlEqualTo("/v1/payments/toss-payment-key")));
    }

    @Test
    @DisplayName("Toss 요청 실패 응답은 PG 요청 실패 예외로 변환한다")
    void confirmPayment_tossRequestFailure() {
        wireMock.stubFor(post(urlEqualTo("/v1/payments/confirm"))
                .willReturn(badRequest()
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody("""
                                {
                                  "code": "EXCEED_MAX_CARD_LIMIT",
                                  "message": "카드 한도를 초과했습니다"
                                }
                                """)));

        assertThatThrownBy(() -> tossPaymentAdapter.confirmPayment(
                new PaymentGatewayCommand.Confirm(
                        "toss-payment-key",
                        "order-id",
                        10000L,
                        "payment:confirm:order-id"
                )
        ))
                .isInstanceOf(PaymentGatewayRequestException.class)
                .hasMessage("카드 한도를 초과했습니다")
                .satisfies(exception -> assertThat(
                        ((PaymentGatewayRequestException) exception).getProviderErrorCode()
                ).isEqualTo("EXCEED_MAX_CARD_LIMIT"));
    }

    @Test
    @DisplayName("Toss 통신이 끊기면 PG 연결 실패 예외로 변환한다")
    void confirmPayment_gatewayConnectionFailure() {
        wireMock.stubFor(post(urlEqualTo("/v1/payments/confirm"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> tossPaymentAdapter.confirmPayment(
                new PaymentGatewayCommand.Confirm(
                        "toss-payment-key",
                        "order-id",
                        10000L,
                        "payment:confirm:order-id"
                )
        ))
                .isInstanceOf(PaymentGatewayConnectionException.class)
                .hasMessage("Toss 결제 게이트웨이 통신에 실패했습니다.");
    }

    @Test
    @DisplayName("결제 취소 요청은 Toss 취소 API를 호출하고 lastTransactionKey를 반환한다")
    void cancelPayment_success() {
        wireMock.stubFor(post(urlEqualTo("/v1/payments/toss-payment-key/cancel"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo(basicAuthValue()))
                .withHeader("Idempotency-Key", equalTo("payment:cancel:order-id"))
                .withRequestBody(equalToJson("""
                        {
                          "cancelReason": "재고 차감 실패",
                          "cancelAmount": 10000
                        }
                        """))
                .willReturn(okJson("""
                        {
                          "paymentKey": "toss-payment-key",
                          "lastTransactionKey": "cancel-transaction-key"
                        }
                        """)));

        PaymentGatewayResult.Cancel result = tossPaymentAdapter.cancelPayment(
                new PaymentGatewayCommand.Cancel(
                        "toss-payment-key",
                        "재고 차감 실패",
                        10000L,
                        "payment:cancel:order-id"
                )
        );

        assertThat(result.providerCancellationId()).isEqualTo("cancel-transaction-key");
        wireMock.verify(postRequestedFor(urlEqualTo("/v1/payments/toss-payment-key/cancel")));
    }

    @Test
    @DisplayName("Toss 취소 응답에 lastTransactionKey가 없으면 paymentKey를 취소 식별자로 사용한다")
    void cancelPayment_withoutLastTransactionKey_returnsPaymentKey() {
        wireMock.stubFor(post(urlEqualTo("/v1/payments/toss-payment-key/cancel"))
                .willReturn(okJson("""
                        {
                          "paymentKey": "toss-payment-key"
                        }
                        """)));

        PaymentGatewayResult.Cancel result = tossPaymentAdapter.cancelPayment(
                new PaymentGatewayCommand.Cancel(
                        "toss-payment-key",
                        "사용자 요청 취소",
                        10000L,
                        "payment:cancel:order-id"
                )
        );

        assertThat(result.providerCancellationId()).isEqualTo("toss-payment-key");
    }

    @Test
    @DisplayName("빌링키 발급 요청은 Toss 빌링키를 반환한다")
    void registerBillingKey_success() {
        wireMock.stubFor(post(urlEqualTo("/v1/billing/authorizations/issue"))
                .withRequestBody(equalToJson("""
                        {
                          "authKey": "auth-key",
                          "customerKey": "customer-key"
                        }
                        """))
                .willReturn(okJson("""
                        {
                          "billingKey": "toss-billing-key"
                        }
                        """)));

        PaymentGatewayResult.RegisterBillingKey result = tossPaymentAdapter.registerBillingKey(
                new PaymentGatewayCommand.RegisterBillingKey("customer-key", "auth-key")
        );

        assertThat(result.billingKeyID()).isEqualTo("toss-billing-key");
    }

    @Test
    @DisplayName("빌링키 자동 결제 요청은 Toss 결제 키를 반환한다")
    void confirmBillingPayment_success() {
        wireMock.stubFor(post(urlEqualTo("/v1/billing/toss-billing-key"))
                .withHeader("Idempotency-Key", equalTo("payment:confirm:order-id"))
                .withRequestBody(equalToJson("""
                        {
                          "customerKey": "customer-key",
                          "orderId": "order-id",
                          "orderName": "래플 자동 결제",
                          "amount": 10000
                        }
                        """))
                .willReturn(okJson("""
                        {
                          "paymentKey": "toss-billing-payment-key"
                        }
                        """)));

        PaymentGatewayResult.Confirm result = tossPaymentAdapter.confirmBillingPayment(
                new PaymentGatewayCommand.ConfirmBilling(
                        "toss-billing-key",
                        "customer-key",
                        "order-id",
                        "래플 자동 결제",
                        10000L,
                        "payment:confirm:order-id"
                )
        );

        assertThat(result.providerPaymentId()).isEqualTo("toss-billing-payment-key");
    }

    private String basicAuthValue() {
        String token = Base64.getEncoder()
                .encodeToString((SECRET_KEY + ":").getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }
}
