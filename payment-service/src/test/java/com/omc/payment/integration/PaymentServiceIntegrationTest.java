package com.omc.payment.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omc.payment.application.scheduler.PaymentOutboxPublisher;
import com.omc.payment.domain.entity.Payment;
import com.omc.payment.domain.entity.PaymentOutboxEvent;
import com.omc.payment.domain.enums.CancellationCode;
import com.omc.payment.domain.enums.OutboxEventStatus;
import com.omc.payment.domain.enums.PaymentStatus;
import com.omc.payment.domain.repository.PaymentInboxEventRepository;
import com.omc.payment.domain.repository.PaymentOutboxEventRepository;
import com.omc.payment.domain.repository.PaymentRepository;
import com.omc.payment.infrastructure.consumer.PaymentEventConsumer;
import com.omc.payment.infrastructure.client.CouponServiceClient;
import com.omc.payment.infrastructure.config.KafkaTopics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("결제 서비스 통합 테스트")
class PaymentServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test")
            ;

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.2-alpine")
            .withExposedPorts(6379)
            .withTmpFs(Map.of("/data", "rw"));

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("eureka.client.enabled", () -> "false");
        registry.add("spring.cloud.discovery.enabled", () -> "false");
        registry.add("spring.kafka.consumer.group-id", () -> "payment-service-test");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("spring.kafka.admin.auto-create", () -> "false");
        registry.add("gateway.secret", () -> GATEWAY_SECRET);
        registry.add("payment.pg.mode", () -> "test");
    }

    private static final String GATEWAY_SECRET = "test-gateway-secret";
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID OTHER_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000103");
    private static final UUID DROP_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentOutboxEventRepository paymentOutboxEventRepository;
    @Autowired PaymentInboxEventRepository paymentInboxEventRepository;
    @Autowired StringRedisTemplate stringRedisTemplate;

    @SuppressWarnings("rawtypes")
    @MockitoBean KafkaTemplate kafkaTemplate;
    @MockitoBean CouponServiceClient couponServiceClient;
    @MockitoBean PaymentEventConsumer paymentEventConsumer;
    @MockitoBean PaymentOutboxPublisher paymentOutboxPublisher;

    @BeforeEach
    void setUp() {
        paymentOutboxEventRepository.deleteAll();
        paymentInboxEventRepository.deleteAll();
        paymentRepository.deleteAll();
        stringRedisTemplate.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }

    @Nested
    @DisplayName("결제 승인 API")
    class ConfirmPaymentApi {

        @Test
        @DisplayName("드롭 결제를 승인하면 결제와 완료 아웃박스가 저장된다")
        void confirmPayment_success() throws Exception {
            UUID orderId = UUID.randomUUID();

            mockMvc.perform(post("/internal/v1/payments/confirm")
                            .header("X-User-Id", USER_ID.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(confirmPaymentBody(orderId, DROP_ID, PRODUCT_ID, null, 10000L, 0L, 10000L)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                    .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
                    .andExpect(jsonPath("$.paymentStatus").value("PAID"));

            Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
            assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(payment.getProviderPaymentId()).isEqualTo("test-payment-" + orderId);

            PaymentOutboxEvent outboxEvent = onlyOutboxEvent();
            assertThat(outboxEvent.getAggregateId()).isEqualTo(payment.getPaymentId());
            assertThat(outboxEvent.getEventType()).isEqualTo(KafkaTopics.PAYMENT_COMPLETED);
            assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.INIT);
        }

        @Test
        @DisplayName("같은 주문 결제가 이미 있으면 기존 결제를 반환하고 추가 적재하지 않는다")
        void confirmPayment_duplicateOrder_returnsExistingPayment() throws Exception {
            UUID orderId = UUID.randomUUID();

            mockMvc.perform(post("/internal/v1/payments/confirm")
                            .header("X-User-Id", USER_ID.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(confirmPaymentBody(orderId, DROP_ID, PRODUCT_ID, null, 10000L, 0L, 10000L)))
                    .andExpect(status().isCreated());

            assertThat(stringRedisTemplate.opsForValue().get("payment:confirm:" + orderId)).isEqualTo("SUCCEEDED");

            mockMvc.perform(post("/internal/v1/payments/confirm")
                            .header("X-User-Id", USER_ID.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(confirmPaymentBody(orderId, DROP_ID, PRODUCT_ID, null, 10000L, 0L, 10000L)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                    .andExpect(jsonPath("$.paymentStatus").value("PAID"));

            assertThat(paymentRepository.count()).isEqualTo(1);
            assertThat(paymentOutboxEventRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("같은 주문이 처리 중이면 중복 결제를 차단한다")
        void confirmPayment_processingKey_returns409() throws Exception {
            UUID orderId = UUID.randomUUID();
            stringRedisTemplate.opsForValue().set(
                    "payment:confirm:" + orderId,
                    "PROCESSING:다른 요청 토큰",
                    Duration.ofSeconds(30)
            );

            mockMvc.perform(post("/internal/v1/payments/confirm")
                            .header("X-User-Id", USER_ID.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(confirmPaymentBody(orderId, DROP_ID, PRODUCT_ID, null, 10000L, 0L, 10000L)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("PAYMENT-007"));

            assertThat(paymentRepository.count()).isZero();
            assertThat(paymentOutboxEventRepository.count()).isZero();
            assertThat(stringRedisTemplate.opsForValue().get("payment:confirm:" + orderId))
                    .isEqualTo("PROCESSING:다른 요청 토큰");
        }

        @Test
        @DisplayName("결제 금액이 맞지 않으면 실패 아웃박스를 저장하고 실패 결제를 반환한다")
        void confirmPayment_invalidAmount_returnsFailedPayment() throws Exception {
            UUID orderId = UUID.randomUUID();

            mockMvc.perform(post("/internal/v1/payments/confirm")
                            .header("X-User-Id", USER_ID.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(confirmPaymentBody(orderId, DROP_ID, PRODUCT_ID, null, 10000L, 1000L, 10000L)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.paymentStatus").value("FAILED"));

            Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
            assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment.getFailureCode()).isEqualTo("PAYMENT-006");
            assertThat(payment.getFailureMessage()).isEqualTo("결제 금액이 일치하지 않습니다.");

            PaymentOutboxEvent outboxEvent = onlyOutboxEvent();
            assertThat(outboxEvent.getAggregateId()).isEqualTo(payment.getPaymentId());
            assertThat(outboxEvent.getEventType()).isEqualTo(KafkaTopics.PAYMENT_FAILED);
        }

        @Test
        @DisplayName("PG가 결제를 거절하면 실패 결제와 실패 아웃박스를 저장한다")
        void confirmPayment_gatewayRejected_savesFailedPayment() throws Exception {
            UUID orderId = UUID.randomUUID();

            mockMvc.perform(post("/internal/v1/payments/confirm")
                            .header("X-User-Id", USER_ID.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(confirmPaymentBody(
                                    orderId,
                                    DROP_ID,
                                    PRODUCT_ID,
                                    null,
                                    10000L,
                                    0L,
                                    10000L,
                                    "E2E_CARD_LIMIT_EXCEEDED"
                            )))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.paymentStatus").value("FAILED"));

            Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
            assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment.getFailureCode()).isEqualTo("EXCEED_MAX_CARD_LIMIT");

            PaymentOutboxEvent outboxEvent = onlyOutboxEvent();
            assertThat(outboxEvent.getAggregateId()).isEqualTo(payment.getPaymentId());
            assertThat(outboxEvent.getEventType()).isEqualTo(KafkaTopics.PAYMENT_FAILED);
        }

        @Test
        @DisplayName("PG 연결 오류는 알 수 없음 결제를 저장하고 재요청 시 기존 결제를 반환한다")
        void confirmPayment_gatewayConnectionFailure_savesUnknownPayment() throws Exception {
            UUID orderId = UUID.randomUUID();
            String requestBody = confirmPaymentBody(
                    orderId,
                    DROP_ID,
                    PRODUCT_ID,
                    null,
                    10000L,
                    0L,
                    10000L,
                    "E2E_GATEWAY_CONNECTION_ERROR"
            );

            mockMvc.perform(post("/internal/v1/payments/confirm")
                            .header("X-User-Id", USER_ID.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.paymentStatus").value("CONFIRM_UNKNOWN"));

            Payment unknownPayment = paymentRepository.findByOrderId(orderId).orElseThrow();
            assertThat(unknownPayment.getPaymentStatus()).isEqualTo(PaymentStatus.CONFIRM_UNKNOWN);
            assertThat(paymentOutboxEventRepository.count()).isZero();

            mockMvc.perform(post("/internal/v1/payments/confirm")
                            .header("X-User-Id", USER_ID.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.paymentStatus").value("CONFIRM_UNKNOWN"));

            assertThat(paymentRepository.count()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("결제 조회와 취소 API")
    class QueryAndCancelApi {

        @Test
        @DisplayName("사용자는 본인 결제 목록만 조회할 수 있다")
        void getMyPayments_user_success() throws Exception {
            UUID myOrderId = UUID.randomUUID();
            UUID otherOrderId = UUID.randomUUID();
            confirmPayment(myOrderId, USER_ID);
            confirmPayment(otherOrderId, OTHER_USER_ID);

            mockMvc.perform(get("/api/v1/payments/me")
                            .header("X-Gateway-Secret", GATEWAY_SECRET)
                            .header("X-User-Id", USER_ID.toString())
                            .header("X-User-Role", "USER"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.content[0].orderId").value(myOrderId.toString()))
                    .andExpect(jsonPath("$.data.content[0].userId").value(USER_ID.toString()));
        }

        @Test
        @DisplayName("사용자가 본인 결제를 취소하면 취소 상태와 환불 완료 아웃박스가 저장된다")
        void cancelPayment_user_success() throws Exception {
            UUID orderId = UUID.randomUUID();
            confirmPayment(orderId, USER_ID);
            Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();

            mockMvc.perform(post("/api/v1/payments/{paymentId}/cancel", payment.getPaymentId())
                            .header("X-Gateway-Secret", GATEWAY_SECRET)
                            .header("X-User-Id", USER_ID.toString())
                            .header("X-User-Role", "USER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(cancelPaymentBody()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.paymentId").value(payment.getPaymentId().toString()))
                    .andExpect(jsonPath("$.data.paymentStatus").value("CANCELED"));

            Payment canceledPayment = paymentRepository.findById(payment.getPaymentId()).orElseThrow();
            assertThat(canceledPayment.getPaymentStatus()).isEqualTo(PaymentStatus.CANCELED);
            assertThat(canceledPayment.getCancellationCode()).isEqualTo(CancellationCode.USER_CANCEL);
            assertThat(canceledPayment.getProviderCancellationId())
                    .isEqualTo("test-cancel-" + payment.getProviderPaymentId());
            assertThat(paymentOutboxEventRepository.count()).isEqualTo(2);
            assertThat(paymentOutboxEventRepository.findAll())
                    .extracting(PaymentOutboxEvent::getEventType)
                    .contains(KafkaTopics.PAYMENT_COMPLETED, KafkaTopics.REFUND_DONE);
        }

        @Test
        @DisplayName("사용자가 다른 사람 결제를 취소하면 403을 반환한다")
        void cancelPayment_otherUser_returns403() throws Exception {
            UUID orderId = UUID.randomUUID();
            confirmPayment(orderId, USER_ID);
            Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();

            mockMvc.perform(post("/api/v1/payments/{paymentId}/cancel", payment.getPaymentId())
                            .header("X-Gateway-Secret", GATEWAY_SECRET)
                            .header("X-User-Id", OTHER_USER_ID.toString())
                            .header("X-User-Role", "USER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(cancelPaymentBody()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("COMMON-002"));

            Payment unchangedPayment = paymentRepository.findById(payment.getPaymentId()).orElseThrow();
            assertThat(unchangedPayment.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(paymentOutboxEventRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("관리자는 전체 결제 목록을 조회할 수 있다")
        void getPayments_admin_success() throws Exception {
            confirmPayment(UUID.randomUUID(), USER_ID);
            confirmPayment(UUID.randomUUID(), OTHER_USER_ID);

            mockMvc.perform(get("/api/v1/admin/payments")
                            .header("X-Gateway-Secret", GATEWAY_SECRET)
                            .header("X-User-Id", ADMIN_ID.toString())
                            .header("X-User-Role", "ADMIN"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(2));
        }
    }

    @Test
    @DisplayName("쿠폰이 없으면 쿠폰 서비스와 통신하지 않는다")
    void confirmPayment_withoutCoupon_doesNotCallCouponService() throws Exception {
        UUID orderId = UUID.randomUUID();

        confirmPayment(orderId, USER_ID);

        verifyNoInteractions(couponServiceClient);
    }

    private void confirmPayment(UUID orderId, UUID userId) throws Exception {
        mockMvc.perform(post("/internal/v1/payments/confirm")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmPaymentBody(orderId, DROP_ID, PRODUCT_ID, null, 10000L, 0L, 10000L)))
                .andExpect(status().isCreated());
    }

    private PaymentOutboxEvent onlyOutboxEvent() {
        assertThat(paymentOutboxEventRepository.count()).isEqualTo(1);
        return paymentOutboxEventRepository.findAll().getFirst();
    }

    private String confirmPaymentBody(
            UUID orderId,
            UUID dropId,
            UUID productId,
            UUID couponId,
            Long originalAmount,
            Long discountAmount,
            Long finalAmount
    ) throws Exception {
        return confirmPaymentBody(
                orderId,
                dropId,
                productId,
                couponId,
                originalAmount,
                discountAmount,
                finalAmount,
                "테스트 결제 승인 아이디"
        );
    }

    private String confirmPaymentBody(
            UUID orderId,
            UUID dropId,
            UUID productId,
            UUID couponId,
            Long originalAmount,
            Long discountAmount,
            Long finalAmount,
            String providerPaymentId
    ) throws Exception {
        return objectMapper.writeValueAsString(new ConfirmPaymentJson(
                orderId,
                dropId,
                productId,
                providerPaymentId,
                couponId,
                originalAmount,
                discountAmount,
                finalAmount
        ));
    }

    private String cancelPaymentBody() throws Exception {
        return objectMapper.writeValueAsString(new CancelPaymentJson(
                CancellationCode.USER_CANCEL,
                "사용자 요청 취소"
        ));
    }

    private record ConfirmPaymentJson(
            UUID orderID,
            UUID dropId,
            UUID productId,
            String providerPaymentId,
            UUID couponID,
            Long originalAmount,
            Long discountAmount,
            Long finalAmount
    ) {
    }

    private record CancelPaymentJson(
            CancellationCode cancellationCode,
            String cancelReason
    ) {
    }
}
