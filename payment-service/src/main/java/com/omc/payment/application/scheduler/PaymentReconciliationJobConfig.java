package com.omc.payment.application.scheduler;

import com.omc.payment.application.service.PaymentReconciliationService;
import com.omc.payment.domain.entity.Payment;
import com.omc.payment.domain.entity.PaymentReconciliationResult;
import com.omc.payment.domain.enums.PaymentStatus;
import com.omc.payment.domain.repository.PaymentReconciliationResultRepository;
import com.omc.payment.domain.repository.PaymentRepository;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.RepositoryItemWriter;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.batch.item.data.builder.RepositoryItemWriterBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class PaymentReconciliationJobConfig {

    private final List<PaymentStatus> TARGET_STATUSES = List.of(
            // READY, CONFIRMING은 TTL 스케줄러가 처리
            PaymentStatus.PAID,
            PaymentStatus.FAILED,
            PaymentStatus.CANCELED,
            PaymentStatus.CONFIRM_UNKNOWN,
            PaymentStatus.CANCEL_UNKNOWN,
            PaymentStatus.RECOVERY_FAILED
    );

    @Bean
    public Job paymentReconciliationJob(
            JobRepository jobRepository,
            Step paymentReconciliationStep
    ) {
        return new JobBuilder("paymentReconciliationJob", jobRepository)
                .start(paymentReconciliationStep)
                .build();
    }

    @Bean
    public Step paymentReconciliationStep(
         JobRepository jobRepository,
         PlatformTransactionManager transactionManager,
         RepositoryItemReader<Payment> paymentRepositoryItemReader,
         ItemProcessor<Payment, PaymentReconciliationResult> paymentReconciliationItemProcessor,
         RepositoryItemWriter<PaymentReconciliationResult> paymentReconciliationItemWriter,
         @Value("${payment.reconciliation.chunk-size:50}") int chunkSize
    ) {
        // 대량 대조로 확장할 수 있도록 결제 건을 chunk 단위로 조회/비교/저장
        return new StepBuilder("paymentReconciliationStep", jobRepository)
                .<Payment, PaymentReconciliationResult>chunk(chunkSize, transactionManager)
                .reader(paymentRepositoryItemReader)
                .processor(paymentReconciliationItemProcessor)
                .writer(paymentReconciliationItemWriter)
                .build();
    }

    @Bean
    @StepScope
    public RepositoryItemReader<Payment> paymentReconciliationReader(
            PaymentRepository paymentRepository,
            @Value("#{jobParameters['checkedAfter']}") String checkedAfter,
            @Value("${payment.reconciliation.page-size:100}") int pageSize
    ) {
        Map<String, Sort.Direction> sorts = new LinkedHashMap<>();
        sorts.put("updatedAt", Sort.Direction.ASC);
        sorts.put("paymentId", Sort.Direction.ASC);

        return new RepositoryItemReaderBuilder<Payment>()
                .name("paymentReconciliationReader")
                .repository(paymentRepository)
                .methodName("findByProviderPaymentIdIsNotNullAndUpdatedAtAfterAndPaymentStatusIn")
                .arguments(List.of(LocalDateTime.parse(checkedAfter), TARGET_STATUSES))
                .pageSize(pageSize)
                .sorts(sorts)
                .build();
    }

    @Bean
    public ItemProcessor<Payment, PaymentReconciliationResult> paymentReconciliationProcessor(
            PaymentReconciliationService paymentReconciliationService
    ) {
        /*
        * 함수형 인터페이스 ItemProcessor를 람다로 익명 구현체를 만들어서 빈으로 등록
        * null은 필터링하고 불일치 건만 Writer로 전달
        * */
        return payment -> paymentReconciliationService.reconcile(payment).orElse(null);
    }

    @Bean
    public RepositoryItemWriter<PaymentReconciliationResult> paymentReconciliationWriter(
            PaymentReconciliationResultRepository paymentReconciliationResultRepository
    ) {
        return new RepositoryItemWriterBuilder<PaymentReconciliationResult>()
                .repository(paymentReconciliationResultRepository)
                .methodName("save")
                .build();
    }
}
