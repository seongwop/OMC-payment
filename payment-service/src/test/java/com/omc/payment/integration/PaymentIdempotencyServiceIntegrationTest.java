package com.omc.payment.integration;

import com.omc.common.exception.BusinessException;
import com.omc.payment.application.service.PaymentIdempotencyService;
import com.omc.payment.domain.exception.PaymentErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
@DataRedisTest
@Import(PaymentIdempotencyService.class)
@TestPropertySource(properties = {
        "payment.idempotency.processing-ttl-seconds=30",
        "payment.idempotency.success-ttl-seconds=60"
})
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("결제 Redis 멱등성 통합 테스트")
class PaymentIdempotencyServiceIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.2-alpine")
            .withExposedPorts(6379)
            .withTmpFs(Map.of("/data", "rw"));

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired PaymentIdempotencyService paymentIdempotencyService;
    @Autowired StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void setUp() {
        stringRedisTemplate.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }

    @Test
    @DisplayName("처리에 성공하면 멱등성 키를 성공 상태와 TTL로 저장한다")
    void execute_success_savesSucceededWithTtl() {
        String key = paymentIdempotencyService.confirmKey(UUID.randomUUID());
        AtomicInteger executionCount = new AtomicInteger();

        paymentIdempotencyService.execute(key, executionCount::incrementAndGet);

        assertThat(executionCount).hasValue(1);
        assertThat(stringRedisTemplate.opsForValue().get(key)).isEqualTo("SUCCEEDED");
        assertThat(stringRedisTemplate.getExpire(key)).isBetween(1L, 60L);
    }

    @Test
    @DisplayName("이미 성공한 요청은 비즈니스 로직을 다시 실행하지 않는다")
    void execute_alreadySucceeded_doesNotRunAgain() {
        String key = paymentIdempotencyService.confirmKey(UUID.randomUUID());
        AtomicInteger executionCount = new AtomicInteger();

        paymentIdempotencyService.execute(key, executionCount::incrementAndGet);
        paymentIdempotencyService.execute(key, executionCount::incrementAndGet);

        assertThat(executionCount).hasValue(1);
        assertThat(stringRedisTemplate.opsForValue().get(key)).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("다른 요청이 처리 중인 키를 선점하고 있으면 중복 처리를 차단한다")
    void execute_processingKeyExists_throwsBusinessException() {
        String key = paymentIdempotencyService.confirmKey(UUID.randomUUID());
        stringRedisTemplate.opsForValue().set(
                key,
                "PROCESSING:다른 요청 토큰",
                Duration.ofSeconds(30)
        );
        AtomicInteger executionCount = new AtomicInteger();

        assertThatThrownBy(() -> paymentIdempotencyService.execute(key, executionCount::incrementAndGet))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_ALREADY_EXISTS)
                );

        assertThat(executionCount).hasValue(0);
        assertThat(stringRedisTemplate.opsForValue().get(key)).isEqualTo("PROCESSING:다른 요청 토큰");
        assertThat(stringRedisTemplate.getExpire(key)).isPositive();
    }

    @Test
    @DisplayName("비즈니스 로직이 실패하면 현재 요청이 선점한 처리 중 키를 삭제한다")
    void execute_actionFails_deletesProcessingKey() {
        String key = paymentIdempotencyService.cancelKey(UUID.randomUUID());

        assertThatThrownBy(() -> paymentIdempotencyService.execute(key, () -> {
            throw new IllegalStateException("결제 처리 실패");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("결제 처리 실패");

        assertThat(stringRedisTemplate.hasKey(key)).isFalse();
    }
}
