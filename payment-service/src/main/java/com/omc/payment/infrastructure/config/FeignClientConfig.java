package com.omc.payment.infrastructure.config;

import feign.RequestInterceptor;
import feign.Retryer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeignClientConfig {

    // 내부 서비스 호출 시 gateway secret 헤더를 공통으로 주입
    @Bean
    public RequestInterceptor gatewaySecretRequestInterceptor(
            @Value("${gateway.secret}") String gatewaySecret
    ) {
        return template -> template.header("X-Gateway-Secret", gatewaySecret);
    }

    // 쿠폰 서비스 네트워크성 호출 실패만 짧게 재시도
    @Bean
    public Retryer couponServiceRetryer(
            @Value("${payment.coupon.retry.period-ms:100}") long periodMs,
            @Value("${payment.coupon.retry.max-period-ms:300}") long maxPeriodMs,
            @Value("${payment.coupon.retry.max-attempts:2}") int maxAttempts
    ) {
        return new Retryer.Default(periodMs, maxPeriodMs, maxAttempts);
    }
}
