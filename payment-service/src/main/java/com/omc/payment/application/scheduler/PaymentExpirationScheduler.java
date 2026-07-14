package com.omc.payment.application.scheduler;

import com.omc.payment.application.service.PaymentExpirationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.expiration.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentExpirationScheduler {

    private final PaymentExpirationService paymentExpirationService;

    @Value("${payment.expiration.batch-size:100}")
    private int batchSize;

    @Value("${payment.expiration.ttl-ms:300000}")
    private long ttlMs;

    @Scheduled(
            initialDelayString = "${payment.expiration.initial-delay-ms:60000}",
            fixedDelayString = "${payment.expiration.fixed-delay-ms:30000}"
    )
    public void expireIncompletePayments() {
        log.debug("미완료 결제 TTL 만료 처리를 시작합니다. batchSize={}, ttlMs={}", batchSize, ttlMs);
        paymentExpirationService.expireIncompletePayments(
                batchSize,
                Duration.ofMillis(ttlMs)
        );
    }
}
