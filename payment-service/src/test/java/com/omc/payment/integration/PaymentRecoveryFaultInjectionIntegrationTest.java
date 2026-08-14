package com.omc.payment.integration;

import com.omc.payment.application.port.out.PaymentGatewayCommand;
import com.omc.payment.application.port.out.PaymentGatewayPort;
import com.omc.payment.application.port.out.PaymentGatewayResult;
import com.omc.payment.application.scheduler.PaymentOutboxPublisher;
import com.omc.payment.application.service.PaymentTransactionService;
import com.omc.payment.application.service.PaymentUnknownRecoveryService;
import com.omc.payment.domain.entity.Payment;
import com.omc.payment.domain.entity.PaymentOutboxEvent;
import com.omc.payment.domain.enums.CancellationCode;
import com.omc.payment.domain.enums.PaymentGatewayStatus;
import com.omc.payment.domain.enums.PaymentMethod;
import com.omc.payment.domain.enums.PaymentStatus;
import com.omc.payment.domain.enums.Provider;
import com.omc.payment.domain.enums.SalesType;
import com.omc.payment.domain.exception.NonRetryablePaymentException;
import com.omc.payment.domain.exception.PaymentErrorCode;
import com.omc.payment.domain.repository.PaymentOutboxEventRepository;
import com.omc.payment.domain.repository.PaymentRepository;
import com.omc.payment.infrastructure.client.CouponServiceClient;
import com.omc.payment.infrastructure.config.KafkaTopics;
import com.omc.payment.infrastructure.consumer.PaymentEventConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("미확정 결제 내부 보정 실패 복구 통합 테스트")
class PaymentRecoveryFaultInjectionIntegrationTest {

    private static final int PAYMENT_COUNT = 200;
    private static final int RECOVERY_BATCH_SIZE = 100;
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID DROP_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

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
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("spring.kafka.admin.auto-create", () -> "false");
        registry.add("payment.pg.mode", () -> "test");
        registry.add("payment.unknown-recovery.enabled", () -> "false");
        registry.add("payment.expiration.enabled", () -> "false");
        registry.add("payment.reconciliation.enabled", () -> "false");
    }

    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentOutboxEventRepository paymentOutboxEventRepository;
    @Autowired PaymentUnknownRecoveryService paymentUnknownRecoveryService;

    @MockitoBean PaymentGatewayPort paymentGatewayPort;
    @MockitoSpyBean PaymentTransactionService paymentTransactionService;

    @SuppressWarnings("rawtypes")
    @MockitoBean KafkaTemplate kafkaTemplate;
    @MockitoBean CouponServiceClient couponServiceClient;
    @MockitoBean PaymentEventConsumer paymentEventConsumer;
    @MockitoBean PaymentOutboxPublisher paymentOutboxPublisher;

    @BeforeEach
    void setUp() {
        paymentOutboxEventRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    @Test
    @DisplayName("PG 승인 확인 후 내부 보정에 실패한 200건을 망취소하여 전량 CANCELED로 수렴한다")
    void paidLookup_internalCorrectionFailure_networkCancelsAllPayments() {
        paymentRepository.saveAll(createConfirmUnknownPayments(PAYMENT_COUNT));

        // PG 승인은 완료됐지만 내부 상태 보정에 실패한 망취소 시나리오 재현
        when(paymentGatewayPort.getPayment(any(PaymentGatewayCommand.GetPayment.class)))
                .thenAnswer(invocation -> paidGatewayPayment(invocation.getArgument(0)));
        when(paymentGatewayPort.cancelPayment(any(PaymentGatewayCommand.Cancel.class)))
                .thenAnswer(invocation -> successfulNetworkCancel(invocation.getArgument(0)));
        doThrow(new NonRetryablePaymentException(
                PaymentErrorCode.PAYMENT_FAILED,
                "테스트용 내부 승인 보정 실패"
        )).when(paymentTransactionService).approveAndSaveOutbox(any(UUID.class), anyString());

        paymentUnknownRecoveryService.recoverPendingPayments(RECOVERY_BATCH_SIZE);
        paymentUnknownRecoveryService.recoverPendingPayments(RECOVERY_BATCH_SIZE);

        List<Payment> payments = paymentRepository.findAll();
        assertThat(payments).hasSize(PAYMENT_COUNT);
        assertThat(payments)
                .allSatisfy(payment -> {
                    assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.CANCELED);
                    assertThat(payment.getCancellationCode()).isEqualTo(CancellationCode.NETWORK_CANCEL);
                });

        List<PaymentOutboxEvent> outboxEvents = paymentOutboxEventRepository.findAll();
        assertThat(outboxEvents).hasSize(PAYMENT_COUNT);
        assertThat(outboxEvents)
                .extracting(PaymentOutboxEvent::getEventType)
                .containsOnly(KafkaTopics.REFUND_DONE);

        verify(paymentGatewayPort, times(PAYMENT_COUNT))
                .getPayment(any(PaymentGatewayCommand.GetPayment.class));
        verify(paymentTransactionService, times(PAYMENT_COUNT))
                .approveAndSaveOutbox(any(UUID.class), anyString());
        verify(paymentGatewayPort, times(PAYMENT_COUNT))
                .cancelPayment(any(PaymentGatewayCommand.Cancel.class));
    }

    private List<Payment> createConfirmUnknownPayments(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> {
                    UUID orderId = UUID.randomUUID();
                    Payment payment = Payment.create(
                            orderId,
                            DROP_ID,
                            null,
                            null,
                            PRODUCT_ID,
                            null,
                            USER_ID,
                            SalesType.DROP,
                            10_000L,
                            0L,
                            10_000L,
                            Provider.TOSS,
                            "fault-payment-" + orderId,
                            PaymentMethod.CARD
                    );
                    payment.startConfirming();
                    payment.markConfirmUnknown();
                    return payment;
                })
                .toList();
    }

    private PaymentGatewayResult.Payment paidGatewayPayment(PaymentGatewayCommand.GetPayment command) {
        return new PaymentGatewayResult.Payment(
                command.providerPaymentID(),
                null,
                PaymentGatewayStatus.PAID,
                10_000L,
                10_000L,
                command.providerPaymentID()
        );
    }

    private PaymentGatewayResult.Cancel successfulNetworkCancel(PaymentGatewayCommand.Cancel command) {
        return new PaymentGatewayResult.Cancel("cancel-" + command.providerPaymentId());
    }
}
