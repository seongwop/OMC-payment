package com.omc.payment.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omc.payment.application.event.dto.inbound.OrderCreatedEvent;
import com.omc.payment.application.event.dto.inbound.RefundRequestedEvent;
import com.omc.payment.application.event.dto.inbound.StockFailedEvent;
import com.omc.payment.application.scheduler.PaymentOutboxPublisher;
import com.omc.payment.application.service.PaymentEventService;
import com.omc.payment.domain.entity.Payment;
import com.omc.payment.domain.enums.CancellationCode;
import com.omc.payment.domain.enums.PaymentInboxStatus;
import com.omc.payment.domain.enums.PaymentStatus;
import com.omc.payment.domain.repository.PaymentInboxEventRepository;
import com.omc.payment.domain.repository.PaymentOutboxEventRepository;
import com.omc.payment.domain.repository.PaymentRepository;
import com.omc.payment.infrastructure.client.CouponServiceClient;
import com.omc.payment.infrastructure.config.KafkaTopics;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@EmbeddedKafka(
        partitions = 1,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {
                KafkaTopics.ORDER_CREATED,
                KafkaTopics.REFUND_REQUESTED,
                KafkaTopics.STOCK_FAILED,
                KafkaTopics.ORDER_CREATED_DLT,
                KafkaTopics.REFUND_REQUESTED_DLT,
                KafkaTopics.STOCK_FAILED_DLT
        }
)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.kafka.consumer.group-id=payment-service-kafka-integration-test",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.listener.concurrency=1",
        "kafka.topic.default-partitions=1",
        "payment.pg.mode=test",
        "gateway.secret=test-gateway-secret"
})
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("결제 서비스 Kafka 통합 테스트")
class PaymentKafkaIntegrationTest {

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
        registry.add("spring.flyway.default-schema", () -> "payment_db");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "payment_db");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID DROP_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");

    @Autowired ObjectMapper objectMapper;
    @Autowired EmbeddedKafkaBroker embeddedKafkaBroker;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentInboxEventRepository paymentInboxEventRepository;
    @Autowired PaymentOutboxEventRepository paymentOutboxEventRepository;
    @Autowired StringRedisTemplate stringRedisTemplate;

    @MockitoBean CouponServiceClient couponServiceClient;
    @MockitoBean PaymentOutboxPublisher paymentOutboxPublisher;
    @MockitoSpyBean PaymentEventService paymentEventService;

    @BeforeEach
    void setUp() {
        for (MessageListenerContainer container : kafkaListenerEndpointRegistry.getListenerContainers()) {
            ContainerTestUtils.waitForAssignment(container, 1);
        }

        paymentOutboxEventRepository.deleteAll();
        paymentInboxEventRepository.deleteAll();
        paymentRepository.deleteAll();
        stringRedisTemplate.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }

    @Test
    @DisplayName("주문 생성 이벤트를 소비하면 결제와 Inbox 및 Outbox가 저장된다")
    void orderCreated_consumed_savesPaymentInboxAndOutbox() {
        UUID orderId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();

        send(KafkaTopics.ORDER_CREATED, dropOrderCreatedEvent(eventId, orderId));

        await().atMost(10, SECONDS).untilAsserted(() -> {
            Payment payment = paymentRepository.findByOrderId(orderId).orElse(null);
            assertThat(payment).isNotNull();
            assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(payment.getProviderPaymentId()).isEqualTo("test-payment-" + orderId);
            assertThat(paymentInboxEventRepository.existsById(eventId)).isTrue();
            assertThat(paymentOutboxEventRepository.count()).isEqualTo(1);
            assertThat(paymentOutboxEventRepository.findAll().getFirst().getEventType())
                    .isEqualTo(KafkaTopics.PAYMENT_COMPLETED);
            assertSucceededIdempotencyKey(confirmKey(orderId));
        });
    }

    @Test
    @DisplayName("다른 이벤트 아이디로 같은 주문을 소비해도 Redis 멱등성으로 결제는 한 번만 처리된다")
    void sameOrderWithDifferentEventIds_consumed_processesOnce() {
        UUID orderId = UUID.randomUUID();
        String firstEventId = UUID.randomUUID().toString();
        String secondEventId = UUID.randomUUID().toString();

        send(KafkaTopics.ORDER_CREATED, dropOrderCreatedEvent(firstEventId, orderId));

        await().atMost(10, SECONDS).untilAsserted(() -> {
            assertThat(paymentRepository.findByOrderId(orderId)).isPresent();
            assertSucceededIdempotencyKey(confirmKey(orderId));
        });

        send(KafkaTopics.ORDER_CREATED, dropOrderCreatedEvent(secondEventId, orderId));
        verify(paymentEventService, timeout(10000).times(2)).handleOrderCreated(any(OrderCreatedEvent.class));

        await().atMost(10, SECONDS).untilAsserted(() -> {
            assertThat(paymentRepository.count()).isEqualTo(1);
            assertThat(paymentInboxEventRepository.count()).isEqualTo(2);
            assertThat(paymentInboxEventRepository.existsById(firstEventId)).isTrue();
            assertThat(paymentInboxEventRepository.existsById(secondEventId)).isTrue();
            assertThat(paymentOutboxEventRepository.count()).isEqualTo(1);
            assertSucceededIdempotencyKey(confirmKey(orderId));
        });
    }

    @Test
    @DisplayName("환불 요청 이벤트를 소비하면 결제가 취소되고 환불 완료 Outbox가 저장된다")
    void refundRequested_consumed_cancelsPayment() {
        UUID orderId = UUID.randomUUID();
        createPaidPayment(orderId);
        String refundEventId = UUID.randomUUID().toString();

        send(
                KafkaTopics.REFUND_REQUESTED,
                new RefundRequestedEvent(refundEventId, orderId, USER_ID, "구매자 환불 요청")
        );

        await().atMost(10, SECONDS).untilAsserted(() -> {
            Payment payment = paymentRepository.findByOrderId(orderId).orElse(null);
            assertThat(payment).isNotNull();
            assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.CANCELED);
            assertThat(payment.getCancelledMessage()).isEqualTo("구매자 환불 요청");
            assertThat(paymentInboxEventRepository.existsById(refundEventId)).isTrue();
            assertThat(paymentOutboxEventRepository.findAll())
                    .extracting(event -> event.getEventType())
                    .containsExactlyInAnyOrder(KafkaTopics.PAYMENT_COMPLETED, KafkaTopics.REFUND_DONE);
            assertSucceededIdempotencyKey(cancelKey(orderId));
        });
    }

    @Test
    @DisplayName("재고 차감 실패 이벤트를 소비하면 재고 실패 사유로 결제가 취소된다")
    void stockFailed_consumed_cancelsPayment() {
        UUID orderId = UUID.randomUUID();
        createPaidPayment(orderId);
        String stockFailedEventId = UUID.randomUUID().toString();

        send(KafkaTopics.STOCK_FAILED, new StockFailedEvent(stockFailedEventId, orderId));

        await().atMost(10, SECONDS).untilAsserted(() -> {
            Payment payment = paymentRepository.findByOrderId(orderId).orElse(null);
            assertThat(payment).isNotNull();
            assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.CANCELED);
            assertThat(payment.getCancellationCode()).isEqualTo(CancellationCode.STOCK_DEDUCT_FAILED);
            assertThat(paymentInboxEventRepository.existsById(stockFailedEventId)).isTrue();
            assertThat(paymentOutboxEventRepository.findAll())
                    .extracting(event -> event.getEventType())
                    .containsExactlyInAnyOrder(KafkaTopics.PAYMENT_COMPLETED, KafkaTopics.REFUND_DONE);
            assertSucceededIdempotencyKey(cancelKey(orderId));
        });
    }

    @Test
    @DisplayName("취소할 결제가 없는 재고 실패 이벤트는 DLT로 전송된다")
    void stockFailed_withoutPayment_publishesToDlt() throws JsonProcessingException {
        UUID orderId = UUID.randomUUID();
        StockFailedEvent event = new StockFailedEvent(UUID.randomUUID().toString(), orderId);
        String payload = objectMapper.writeValueAsString(event);

        Map<String, Object> consumerProperties = KafkaTestUtils.consumerProps(
                "재고실패-결제없음-DLT-테스트-" + UUID.randomUUID(),
                "true",
                embeddedKafkaBroker
        );
        DefaultKafkaConsumerFactory<String, String> consumerFactory = new DefaultKafkaConsumerFactory<>(
                consumerProperties,
                new StringDeserializer(),
                new StringDeserializer()
        );

        try (Consumer<String, String> consumer = consumerFactory.createConsumer()) {
            embeddedKafkaBroker.consumeFromAnEmbeddedTopic(consumer, KafkaTopics.STOCK_FAILED_DLT);
            kafkaTemplate.send(KafkaTopics.STOCK_FAILED, payload);

            ConsumerRecord<String, String> dltRecord = KafkaTestUtils.getSingleRecord(
                    consumer,
                    KafkaTopics.STOCK_FAILED_DLT,
                    Duration.ofSeconds(10)
            );

            assertThat(dltRecord.value()).isEqualTo(payload);

            await().atMost(10, SECONDS).untilAsserted(() -> {
                assertThat(paymentRepository.findByOrderId(orderId)).isEmpty();
                assertThat(paymentOutboxEventRepository.count()).isZero();
                assertThat(paymentInboxEventRepository.findById(event.eventId()))
                        .get()
                        .extracting(inboxEvent -> inboxEvent.getStatus())
                        .isEqualTo(PaymentInboxStatus.FAILED);
            });
        }
    }
    @Test
    @DisplayName("보상 필수값이 없는 주문 생성 이벤트는 결제를 생성하지 않고 DLT로 전송된다")
    void missingCompensationKey_consumed_publishesToDlt() throws JsonProcessingException {
        UUID orderId = UUID.randomUUID();
        OrderCreatedEvent invalidEvent = new OrderCreatedEvent(
                UUID.randomUUID().toString(),
                orderId,
                USER_ID,
                "DROP",
                null,
                PRODUCT_ID,
                null,
                null,
                10000L,
                0L,
                10000L,
                null,
                null
        );
        String payload = objectMapper.writeValueAsString(invalidEvent);

        Map<String, Object> consumerProperties = KafkaTestUtils.consumerProps(
                "결제-필수값-검증-DLT-테스트-" + UUID.randomUUID(),
                "true",
                embeddedKafkaBroker
        );
        DefaultKafkaConsumerFactory<String, String> consumerFactory = new DefaultKafkaConsumerFactory<>(
                consumerProperties,
                new StringDeserializer(),
                new StringDeserializer()
        );

        try (Consumer<String, String> consumer = consumerFactory.createConsumer()) {
            embeddedKafkaBroker.consumeFromAnEmbeddedTopic(consumer, KafkaTopics.ORDER_CREATED_DLT);
            kafkaTemplate.send(KafkaTopics.ORDER_CREATED, payload);

            ConsumerRecord<String, String> dltRecord = KafkaTestUtils.getSingleRecord(
                    consumer,
                    KafkaTopics.ORDER_CREATED_DLT,
                    Duration.ofSeconds(10)
            );

            assertThat(dltRecord.value()).isEqualTo(payload);

            await().atMost(10, SECONDS).untilAsserted(() -> {
                assertThat(paymentRepository.findByOrderId(orderId)).isEmpty();
                assertThat(paymentInboxEventRepository.existsById(invalidEvent.eventId())).isFalse();
                assertThat(paymentOutboxEventRepository.count()).isZero();
            });
        }
    }

    @Test
    @DisplayName("상품 아이디가 없는 주문 생성 이벤트는 실패 Outbox를 저장하고 정상 소비된다")
    void missingProductId_consumed_savesFailedOutbox() {
        UUID orderId = UUID.randomUUID();
        OrderCreatedEvent invalidEvent = new OrderCreatedEvent(
                UUID.randomUUID().toString(),
                orderId,
                USER_ID,
                "DROP",
                DROP_ID,
                null,
                null,
                null,
                10000L,
                0L,
                10000L,
                null,
                null
        );

        send(KafkaTopics.ORDER_CREATED, invalidEvent);

        await().atMost(10, SECONDS).untilAsserted(() -> {
            Payment payment = paymentRepository.findByOrderId(orderId).orElse(null);
            assertThat(payment).isNotNull();
            assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment.getFailureCode()).isEqualTo("PAYMENT-002");
            assertThat(payment.getFailureMessage()).isEqualTo("상품 ID는 필수입니다.");
            assertThat(paymentInboxEventRepository.existsById(invalidEvent.eventId())).isTrue();
            assertThat(paymentOutboxEventRepository.findAll())
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.getAggregateId()).isEqualTo(payment.getPaymentId());
                        assertThat(event.getEventType()).isEqualTo(KafkaTopics.PAYMENT_FAILED);
                        assertThat(event.getPayload()).contains(orderId.toString());
                        assertThat(event.getPayload()).contains("상품 ID는 필수입니다.");
                    });
            assertSucceededIdempotencyKey(confirmKey(orderId));
        });
    }

    @Test
    @DisplayName("금액 검증 실패는 실패 Outbox를 저장하고 정상 소비된다")
    void amountMismatch_consumed_savesFailedOutbox() {
        UUID orderId = UUID.randomUUID();
        OrderCreatedEvent invalidEvent = new OrderCreatedEvent(
                UUID.randomUUID().toString(),
                orderId,
                USER_ID,
                "DROP",
                DROP_ID,
                PRODUCT_ID,
                null,
                null,
                10000L,
                0L,
                9000L,
                null,
                null
        );

        send(KafkaTopics.ORDER_CREATED, invalidEvent);
        verify(paymentEventService, timeout(10000).times(1))
                .handleOrderCreated(any(OrderCreatedEvent.class));

        await().atMost(10, SECONDS).untilAsserted(() -> {
            Payment payment = paymentRepository.findByOrderId(orderId).orElse(null);
            assertThat(payment).isNotNull();
            assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment.getFailureCode()).isEqualTo("PAYMENT-006");
            assertThat(payment.getFailureMessage()).isEqualTo("결제 금액이 일치하지 않습니다.");
            assertThat(paymentInboxEventRepository.existsById(invalidEvent.eventId())).isTrue();
            assertThat(paymentOutboxEventRepository.findAll())
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.getAggregateId()).isEqualTo(payment.getPaymentId());
                        assertThat(event.getEventType()).isEqualTo(KafkaTopics.PAYMENT_FAILED);
                        assertThat(event.getPayload()).contains(orderId.toString());
                        assertThat(event.getPayload()).contains("결제 금액이 일치하지 않습니다.");
                    });
            assertSucceededIdempotencyKey(confirmKey(orderId));
        });
    }

    @Test
    @DisplayName("결제 금액이 없는 주문 생성 이벤트는 실패 Outbox를 저장하고 정상 소비된다")
    void missingAmount_consumed_savesFailedOutbox() {
        UUID orderId = UUID.randomUUID();
        OrderCreatedEvent invalidEvent = new OrderCreatedEvent(
                UUID.randomUUID().toString(),
                orderId,
                USER_ID,
                "DROP",
                DROP_ID,
                PRODUCT_ID,
                null,
                null,
                null,
                0L,
                null,
                null,
                null
        );

        send(KafkaTopics.ORDER_CREATED, invalidEvent);

        await().atMost(10, SECONDS).untilAsserted(() -> {
            Payment payment = paymentRepository.findByOrderId(orderId).orElse(null);
            assertThat(payment).isNotNull();
            assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(payment.getFailureCode()).isEqualTo("PAYMENT-006");
            assertThat(payment.getFailureMessage()).isEqualTo("결제 금액은 필수입니다.");
            assertThat(payment.getOriginalAmount()).isZero();
            assertThat(payment.getFinalAmount()).isZero();
            assertThat(paymentInboxEventRepository.existsById(invalidEvent.eventId())).isTrue();
            assertThat(paymentOutboxEventRepository.findAll())
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.getAggregateId()).isEqualTo(payment.getPaymentId());
                        assertThat(event.getEventType()).isEqualTo(KafkaTopics.PAYMENT_FAILED);
                        assertThat(event.getPayload()).contains(orderId.toString());
                        assertThat(event.getPayload()).contains("결제 금액은 필수입니다.");
                    });
            assertSucceededIdempotencyKey(confirmKey(orderId));
        });
    }

    private void createPaidPayment(UUID orderId) {
        String eventId = UUID.randomUUID().toString();
        send(KafkaTopics.ORDER_CREATED, dropOrderCreatedEvent(eventId, orderId));

        await().atMost(10, SECONDS).untilAsserted(() ->
                assertThat(paymentRepository.findByOrderId(orderId))
                        .get()
                        .extracting(Payment::getPaymentStatus)
                        .isEqualTo(PaymentStatus.PAID)
        );
    }

    private OrderCreatedEvent dropOrderCreatedEvent(String eventId, UUID orderId) {
        return new OrderCreatedEvent(
                eventId,
                orderId,
                USER_ID,
                "DROP",
                DROP_ID,
                PRODUCT_ID,
                null,
                null,
                10000L,
                0L,
                10000L,
                null,
                null
        );
    }

    private void send(String topic, Object event) {
        try {
            kafkaTemplate.send(topic, objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Kafka 테스트 이벤트 직렬화에 실패했습니다", e);
        }
    }

    private String confirmKey(UUID orderId) {
        return "payment:confirm:" + orderId;
    }

    private String cancelKey(UUID orderId) {
        return "payment:cancel:" + orderId;
    }

    private void assertSucceededIdempotencyKey(String key) {
        assertThat(stringRedisTemplate.opsForValue().get(key)).isEqualTo("SUCCEEDED");
        assertThat(stringRedisTemplate.getExpire(key)).isPositive();
    }
}
