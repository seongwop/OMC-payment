package com.omc.payment.application.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.reconciliation.enabled", havingValue = "true")
public class PaymentReconciliationScheduler {

    private final JobLauncher jobLauncher;
    private final Job paymentReconciliationJob;

    @Value("${payment.reconciliation.lookback-days:3}")
    private int lookbackDays;

    @Value("${payment.reconciliation.zone:Asia/Seoul}")
    private String zone;

    // 사용자가 적은 새벽 시간대에 하루 한 번 PG-DB 결제 대조 배치를 실행
    @Scheduled(
            cron = "${payment.reconciliation.cron:0 0 4 * * *}",
            zone = "${payment.reconciliation.zone:Asia/Seoul}"
    )
    public void runPaymentReconciliation() {
        if (lookbackDays <= 0) {
            log.warn("결제 대조 배치 lookbackDays가 올바르지 않습니다. lookbackDays={}", lookbackDays);
            return;
        }
        try {
            JobParameters jobParameters = new JobParametersBuilder()
                    .addLong("startedAt", System.currentTimeMillis())
                    .addString("checkedAfter", LocalDateTime.now(ZoneId.of(zone)).minusDays(lookbackDays).toString())
                    .toJobParameters();
            jobLauncher.run(paymentReconciliationJob, jobParameters);
        } catch (Exception e) {
            log.error("PG-DB 결제 대조 배치 실행에 실패했습니다.", e);
        }
    }
}
