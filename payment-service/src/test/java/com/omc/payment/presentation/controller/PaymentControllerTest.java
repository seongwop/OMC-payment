package com.omc.payment.presentation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omc.common.config.GatewaySecurityAutoConfiguration;
import com.omc.common.response.PageResponse;
import com.omc.payment.application.service.PaymentService;
import com.omc.payment.domain.enums.CancellationCode;
import com.omc.payment.domain.enums.PaymentMethod;
import com.omc.payment.domain.enums.PaymentStatus;
import com.omc.payment.domain.enums.Provider;
import com.omc.payment.domain.enums.SalesType;
import com.omc.payment.infrastructure.config.SecurityConfig;
import com.omc.payment.presentation.dto.request.CancelPaymentRequest;
import com.omc.payment.presentation.dto.response.PaymentDetailResponse;
import com.omc.payment.presentation.dto.response.PaymentResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@Import({SecurityConfig.class, GatewaySecurityAutoConfiguration.class})
@TestPropertySource(properties = "gateway.secret=test-gateway-secret")
@DisplayName("결제 컨트롤러 테스트")
class PaymentControllerTest {

    private static final String GATEWAY_SECRET = "test-gateway-secret";
    private static final UUID PAYMENT_ID = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID DROP_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private PaymentService paymentService;

    @Nested
    @DisplayName("결제 취소 API")
    class CancelPaymentApi {

        @Test
        @DisplayName("사용자 권한이면 결제 취소에 성공한다")
        void cancelPayment_user_success() throws Exception {
            PaymentResponse response = paymentResponse(PaymentStatus.CANCELED);
            given(paymentService.cancelPayment(eq(PAYMENT_ID), any())).willReturn(response);

            String body = objectMapper.writeValueAsString(
                    new CancelPaymentRequest(CancellationCode.USER_CANCEL, "사용자 요청 취소")
            );

            mockMvc.perform(post("/api/v1/payments/{paymentId}/cancel", PAYMENT_ID)
                            .header("X-Gateway-Secret", GATEWAY_SECRET)
                            .header("X-User-Id", USER_ID.toString())
                            .header("X-User-Role", "USER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.paymentId").value(PAYMENT_ID.toString()))
                    .andExpect(jsonPath("$.data.paymentStatus").value("CANCELED"));
        }

        @Test
        @DisplayName("취소 요청이 유효하지 않으면 400을 반환한다")
        void cancelPayment_invalidRequest() throws Exception {
            String body = """
                    {
                      "cancelReason": ""
                    }
                    """;

            mockMvc.perform(post("/api/v1/payments/{paymentId}/cancel", PAYMENT_ID)
                            .header("X-Gateway-Secret", GATEWAY_SECRET)
                            .header("X-User-Id", USER_ID.toString())
                            .header("X-User-Role", "USER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("COMMON-001"));
        }
    }

    @Nested
    @DisplayName("내 결제 조회 API")
    class GetMyPaymentsApi {

        @Test
        @DisplayName("사용자 권한이면 내 결제 목록을 조회할 수 있다")
        void getMyPayments_user_success() throws Exception {
            PaymentDetailResponse detail = paymentDetailResponse();
            given(paymentService.getMyPayments(any()))
                    .willReturn(new PageResponse<>(
                            new PageImpl<>(
                                    List.of(detail),
                                    PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "requestedAt")),
                                    1
                            )
                    ));

            mockMvc.perform(get("/api/v1/payments/me")
                            .header("X-Gateway-Secret", GATEWAY_SECRET)
                            .header("X-User-Id", USER_ID.toString())
                            .header("X-User-Role", "USER"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].paymentId").value(PAYMENT_ID.toString()))
                    .andExpect(jsonPath("$.data.totalElements").value(1));
        }

        @Test
        @DisplayName("관리자 권한으로 내 결제 조회를 호출하면 403을 반환한다")
        void getMyPayments_admin_forbidden() throws Exception {
            mockMvc.perform(get("/api/v1/payments/me")
                            .header("X-Gateway-Secret", GATEWAY_SECRET)
                            .header("X-User-Id", ADMIN_ID.toString())
                            .header("X-User-Role", "ADMIN"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("전체 결제 조회 API")
    class GetPaymentsApi {

        @Test
        @DisplayName("관리자 권한이면 전체 결제 목록을 조회할 수 있다")
        void getPayments_admin_success() throws Exception {
            PaymentDetailResponse detail = paymentDetailResponse();
            given(paymentService.getPayments(any()))
                    .willReturn(new PageResponse<>(
                            new PageImpl<>(
                                    List.of(detail),
                                    PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "requestedAt")),
                                    1
                            )
                    ));

            mockMvc.perform(get("/api/v1/admin/payments")
                            .header("X-Gateway-Secret", GATEWAY_SECRET)
                            .header("X-User-Id", ADMIN_ID.toString())
                            .header("X-User-Role", "ADMIN"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].orderId").value(ORDER_ID.toString()))
                    .andExpect(jsonPath("$.data.totalElements").value(1));
        }

        @Test
        @DisplayName("사용자 권한으로 전체 결제 조회를 호출하면 403을 반환한다")
        void getPayments_user_forbidden() throws Exception {
            mockMvc.perform(get("/api/v1/admin/payments")
                            .header("X-Gateway-Secret", GATEWAY_SECRET)
                            .header("X-User-Id", USER_ID.toString())
                            .header("X-User-Role", "USER"))
                    .andExpect(status().isForbidden());
        }
    }

    private PaymentResponse paymentResponse(PaymentStatus paymentStatus) {
        return new PaymentResponse(
                PAYMENT_ID,
                ORDER_ID,
                DROP_ID,
                null,
                null,
                PRODUCT_ID,
                USER_ID,
                SalesType.DROP,
                9000L,
                PaymentMethod.CARD,
                paymentStatus,
                LocalDateTime.of(2026, 6, 23, 10, 0),
                LocalDateTime.of(2026, 6, 23, 10, 1)
        );
    }

    private PaymentDetailResponse paymentDetailResponse() {
        return new PaymentDetailResponse(
                PAYMENT_ID,
                ORDER_ID,
                DROP_ID,
                null,
                null,
                PRODUCT_ID,
                null,
                USER_ID,
                SalesType.DROP,
                10000L,
                1000L,
                9000L,
                Provider.TOSS,
                "결제 승인 아이디",
                null,
                PaymentMethod.CARD,
                PaymentStatus.PAID,
                null,
                null,
                null,
                null,
                LocalDateTime.of(2026, 6, 23, 10, 0),
                LocalDateTime.of(2026, 6, 23, 10, 1),
                null,
                null
        );
    }
}
