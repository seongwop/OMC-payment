package com.omc.payment.application.scheduler;

import com.omc.payment.application.service.PaymentUnknownRecoveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.unknown-recovery.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentUnknownRecoveryScheduler {

    private final PaymentUnknownRecoveryService paymentUnknownRecoveryService;

    @Value("${payment.unknown-recovery.batch-size:100}")
    private int batchSize;

    @Scheduled(
            initialDelayString = "${payment.unknown-recovery.initial-delay-ms:60000}",
            fixedDelayString = "${payment.unknown-recovery.fixed-delay-ms:30000}"
    )
    public void recoverUnknownPayments() {
        log.debug("UNKNOWN 결제 처리를 시작합니다. batchSize={}", batchSize);
        paymentUnknownRecoveryService.recoverPendingPayments(batchSize);
    }
}
