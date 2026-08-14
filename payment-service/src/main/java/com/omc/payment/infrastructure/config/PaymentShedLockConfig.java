package com.omc.payment.infrastructure.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class PaymentShedLockConfig {

    // 모든 인스턴스가 공유하는 Redis를 PG-DB 대조 배치의 잠금 저장소로 사용
    @Bean
    public LockProvider paymentSchedulerLockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider(connectionFactory, "payment-service-shedlock");
    }
}
