package com.omc.payment.presentation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omc.common.config.GatewaySecurityAutoConfiguration;
import com.omc.payment.application.service.PaymentService;
import com.omc.payment.domain.enums.PaymentMethod;
import com.omc.payment.domain.enums.PaymentStatus;
import com.omc.payment.domain.enums.SalesType;
import com.omc.payment.infrastructure.config.SecurityConfig;
import com.omc.payment.presentation.dto.request.ConfirmPaymentRequest;
import com.omc.payment.presentation.dto.request.RegisterBillingKeyRequest;
import com.omc.payment.presentation.dto.response.PaymentResponse;
import com.omc.payment.presentation.dto.response.RegisterBillingKeyResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentInternalController.class)
@Import({SecurityConfig.class, GatewaySecurityAutoConfiguration.class})
@TestPropertySource(properties = "gateway.secret=test-gateway-secret")
@DisplayName("결제 내부 컨트롤러 테스트")
class PaymentInternalControllerTest {

    private static final UUID PAYMENT_ID = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID DROP_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private PaymentService paymentService;

    @Nested
    @DisplayName("결제 승인 API")
    class ConfirmPaymentApi {

        @Test
        @DisplayName("유효한 헤더와 요청이면 201을 반환한다")
        void confirmPayment_success() throws Exception {
            given(paymentService.confirmPayment(any(), eq(USER_ID)))
                    .willReturn(paymentResponse(PaymentStatus.PAID));

            String body = objectMapper.writeValueAsString(new ConfirmPaymentRequest(
                    ORDER_ID,
                    DROP_ID,
                    PRODUCT_ID,
                    "결제 승인 아이디",
                    null,
                    10000L,
                    1000L,
                    9000L
            ));

            mockMvc.perform(post("/internal/v1/payments/confirm")
                            .header("X-User-Id", USER_ID.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.paymentId").value(PAYMENT_ID.toString()))
                    .andExpect(jsonPath("$.paymentStatus").value("PAID"));
        }

        @Test
        @DisplayName("사용자 헤더가 없으면 400을 반환한다")
        void confirmPayment_missingUserHeader() throws Exception {
            String body = objectMapper.writeValueAsString(new ConfirmPaymentRequest(
                    ORDER_ID,
                    DROP_ID,
                    PRODUCT_ID,
                    "결제 승인 아이디",
                    null,
                    10000L,
                    1000L,
                    9000L
            ));

            mockMvc.perform(post("/internal/v1/payments/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("COMMON-001"));
        }
    }

    @Nested
    @DisplayName("빌링키 등록 API")
    class RegisterBillingKeyApi {

        @Test
        @DisplayName("빌링키 등록에 성공하면 200을 반환한다")
        void registerBillingKey_success() throws Exception {
            given(paymentService.registerBillingKey(any()))
                    .willReturn(new RegisterBillingKeyResponse("빌링키 아이디"));

            String body = objectMapper.writeValueAsString(new RegisterBillingKeyRequest(
                    "고객키",
                    "인증키"
            ));

            mockMvc.perform(post("/internal/v1/payments/pre-auth")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.billingKeyId").value("빌링키 아이디"));
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
}
