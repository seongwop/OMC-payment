package com.omc.payment.integration;

import com.omc.payment.application.port.out.PaymentGatewayCommand;
import com.omc.payment.application.port.out.PaymentGatewayPort;
import com.omc.payment.application.port.out.PaymentGatewayResult;
import com.omc.payment.application.scheduler.PaymentOutboxPublisher;
import com.omc.payment.application.scheduler.PaymentReconciliationScheduler;
import com.omc.payment.application.service.PaymentUnknownRecoveryService;
import com.omc.payment.domain.entity.Payment;
import com.omc.payment.domain.enums.PaymentGatewayStatus;
import com.omc.payment.domain.enums.PaymentMethod;
import com.omc.payment.domain.enums.PaymentStatus;
import com.omc.payment.domain.enums.Provider;
import com.omc.payment.domain.enums.SalesType;
import com.omc.payment.domain.repository.PaymentOutboxEventRepository;
import com.omc.payment.domain.repository.PaymentRepository;
import com.omc.payment.infrastructure.client.CouponServiceClient;
import com.omc.payment.infrastructure.consumer.PaymentEventConsumer;
import com.omc.payment.infrastructure.repository.PaymentRecoveryClaimRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("UNKNOWN 결제 복구 작업 선점 통합 테스트")
class PaymentRecoveryClaimIntegrationTest {

    private static final int PAYMENT_COUNT = 60;
    private static final int BATCH_SIZE = 30;
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000111");
    private static final UUID DROP_ID = UUID.fromString("00000000-0000-0000-0000-000000000211");
    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-000000000311");

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
        registry.add("payment.reconciliation.enabled", () -> "true");
        registry.add("payment.reconciliation.cron", () -> "-");
        registry.add("payment.reconciliation.lock-at-least-for", () -> "PT0S");
        registry.add("payment.reconciliation.lock-at-most-for", () -> "PT10S");
    }

    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentOutboxEventRepository paymentOutboxEventRepository;
    @Autowired PaymentUnknownRecoveryService paymentUnknownRecoveryService;
    @Autowired PaymentRecoveryClaimRepository paymentRecoveryClaimRepository;
    @Autowired PaymentReconciliationScheduler paymentReconciliationScheduler;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean PaymentGatewayPort paymentGatewayPort;
    @MockitoBean JobLauncher jobLauncher;

    @SuppressWarnings("rawtypes")
    @MockitoBean KafkaTemplate kafkaTemplate;
    @MockitoBean CouponServiceClient couponServiceClient;
    @MockitoBean PaymentEventConsumer paymentEventConsumer;
    @MockitoBean PaymentOutboxPublisher paymentOutboxPublisher;

    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        paymentOutboxEventRepository.deleteAll();
        paymentRepository.deleteAll();
        executorService = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executorService.shutdownNow();
        executorService.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("동시에 실행된 복구 작업은 서로 다른 결제만 조회한다")
    void concurrentRecoveryClaimsDisjointPayments() throws Exception {
        paymentRepository.saveAll(createConfirmUnknownPayments(PAYMENT_COUNT));

        // 두 복구 작업과 PG 조회를 의도적으로 겹쳐 작업 선점 경합 재현
        CountDownLatch recoveryStarts = new CountDownLatch(2);
        CountDownLatch startSignal = new CountDownLatch(1);
        CyclicBarrier gatewayPairBarrier = new CyclicBarrier(2);
        AtomicInteger totalGatewayCalls = new AtomicInteger();
        Map<String, AtomicInteger> callsByProviderPaymentId = new ConcurrentHashMap<>();
        when(paymentGatewayPort.getPayment(any(PaymentGatewayCommand.GetPayment.class)))
                .thenAnswer(invocation -> {
                    PaymentGatewayCommand.GetPayment command = invocation.getArgument(0);
                    int callSequence = totalGatewayCalls.incrementAndGet();
                    callsByProviderPaymentId
                            .computeIfAbsent(command.providerPaymentID(), ignored -> new AtomicInteger())
                            .incrementAndGet();
                    gatewayPairBarrier.await(5, TimeUnit.SECONDS);
                    if (callSequence % 2 == 0) {
                        Thread.sleep(100);
                    }
                    return paidGatewayPayment(command);
                });

        Future<?> first = executorService.submit(() -> runRecoveryAfterSignal(recoveryStarts, startSignal));
        Future<?> second = executorService.submit(() -> runRecoveryAfterSignal(recoveryStarts, startSignal));

        assertThat(recoveryStarts.await(5, TimeUnit.SECONDS)).isTrue();
        startSignal.countDown();
        first.get(30, TimeUnit.SECONDS);
        second.get(30, TimeUnit.SECONDS);

        List<Payment> payments = paymentRepository.findAll();
        long paidCount = payments.stream()
                .filter(payment -> payment.getPaymentStatus() == PaymentStatus.PAID)
                .count();
        long unknownCount = payments.stream()
                .filter(payment -> payment.getPaymentStatus() == PaymentStatus.CONFIRM_UNKNOWN)
                .count();
        long duplicatedPaymentCount = callsByProviderPaymentId.values().stream()
                .filter(count -> count.get() > 1)
                .count();

        Integer remainingClaims = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_db.p_payments WHERE recovery_claim_owner IS NOT NULL OR recovery_lease_until IS NOT NULL",
                Integer.class
        );

        System.out.printf(
                "AFTER_UNKNOWN total=%d batch=%d workers=2 pgCalls=%d uniquePgTargets=%d duplicatedTargets=%d paid=%d remainingUnknown=%d outbox=%d remainingClaims=%d%n",
                PAYMENT_COUNT,
                BATCH_SIZE,
                totalGatewayCalls.get(),
                callsByProviderPaymentId.size(),
                duplicatedPaymentCount,
                paidCount,
                unknownCount,
                paymentOutboxEventRepository.count(),
                remainingClaims
        );

        assertThat(payments).hasSize(PAYMENT_COUNT);
        assertThat(paidCount).isEqualTo(PAYMENT_COUNT);
        assertThat(unknownCount).isZero();
        assertThat(totalGatewayCalls.get()).isEqualTo(PAYMENT_COUNT);
        assertThat(callsByProviderPaymentId).hasSize(PAYMENT_COUNT);
        assertThat(duplicatedPaymentCount).isZero();
        assertThat(paymentOutboxEventRepository.count()).isEqualTo(PAYMENT_COUNT);
        assertThat(remainingClaims).isZero();
        verify(paymentGatewayPort, times(PAYMENT_COUNT))
                .getPayment(any(PaymentGatewayCommand.GetPayment.class));
    }

    @Test
    @DisplayName("작업 인스턴스가 중단되면 lease 만료 후 다른 인스턴스가 회수한다")
    void expiredLeaseCanBeReclaimed() {
        Payment payment = paymentRepository.save(createConfirmUnknownPayments(1).getFirst());

        List<UUID> firstClaim = paymentRecoveryClaimRepository.claimBatch(
                "instance-a",
                1,
                Duration.ofMinutes(10)
        );
        List<UUID> blockedClaim = paymentRecoveryClaimRepository.claimBatch(
                "instance-b",
                1,
                Duration.ofMinutes(10)
        );

        assertThat(firstClaim).containsExactly(payment.getPaymentId());
        assertThat(blockedClaim).isEmpty();

        // 작업 인스턴스 중단 상황을 가정해 lease 만료 시각 강제 변경
        jdbcTemplate.update(
                "UPDATE payment_db.p_payments SET recovery_lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE payment_id = ?",
                payment.getPaymentId()
        );

        List<UUID> reclaimed = paymentRecoveryClaimRepository.claimBatch(
                "instance-b",
                1,
                Duration.ofMinutes(10)
        );

        assertThat(reclaimed).containsExactly(payment.getPaymentId());
        assertThat(paymentRecoveryClaimRepository.releaseClaims("instance-b")).isEqualTo(1);
    }

    @Test
    @DisplayName("대사 배치는 여러 인스턴스에서 호출돼도 한 번만 실행된다")
    void reconciliationSchedulerRunsOnceAcrossConcurrentCalls() throws Exception {
        // 첫 번째 배치 실행 중 두 번째 스케줄 호출을 겹쳐 분산 잠금 검증
        CountDownLatch firstJobStarted = new CountDownLatch(1);
        CountDownLatch finishFirstJob = new CountDownLatch(1);
        AtomicInteger launchAttempts = new AtomicInteger();
        when(jobLauncher.run(any(Job.class), any(JobParameters.class)))
                .thenAnswer(invocation -> {
                    launchAttempts.incrementAndGet();
                    firstJobStarted.countDown();
                    assertThat(finishFirstJob.await(5, TimeUnit.SECONDS)).isTrue();
                    return mock(JobExecution.class);
                });

        Future<?> first = executorService.submit(paymentReconciliationScheduler::runPaymentReconciliation);
        assertThat(firstJobStarted.await(5, TimeUnit.SECONDS)).isTrue();

        Future<?> second = executorService.submit(paymentReconciliationScheduler::runPaymentReconciliation);
        second.get(5, TimeUnit.SECONDS);
        finishFirstJob.countDown();
        first.get(5, TimeUnit.SECONDS);

        System.out.printf(
                "AFTER_RECONCILIATION schedulerCalls=2 jobLaunchAttempts=%d%n",
                launchAttempts.get()
        );

        assertThat(launchAttempts.get()).isEqualTo(1);
        verify(jobLauncher, times(1)).run(any(Job.class), any(JobParameters.class));
    }

    private void runRecoveryAfterSignal(CountDownLatch ready, CountDownLatch startSignal) {
        try {
            ready.countDown();
            if (!startSignal.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("복구 작업 시작 신호를 기다리지 못했습니다.");
            }
            paymentUnknownRecoveryService.recoverPendingPayments(BATCH_SIZE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("복구 작업이 중단되었습니다.", e);
        }
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
                            "claim-payment-" + orderId,
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
}
