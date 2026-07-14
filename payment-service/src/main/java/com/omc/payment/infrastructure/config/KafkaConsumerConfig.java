package com.omc.payment.infrastructure.config;

import com.omc.payment.domain.exception.NonRetryablePaymentException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Slf4j
@Configuration
public class KafkaConsumerConfig {

    @Bean
    public CommonErrorHandler kafkaCommonErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${payment.kafka.consumer.retry.interval-ms:1000}") long retryIntervalMs,
            @Value("${payment.kafka.consumer.retry.max-attempts:3}") long maxAttempts
    ) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(resolveDltTopic(record), record.partition())
        );

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(retryIntervalMs, Math.max(0, maxAttempts - 1))
        );

        // 포이즌 필은 바로 DLT
        errorHandler.addNotRetryableExceptions(NonRetryablePaymentException.class);

        // DLT로 이관된 레코드는 offset도 함께 커밋
        // 같은 실패 메시지 무한 반복 소비 방지
        errorHandler.setCommitRecovered(true);

        errorHandler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.warn("카프카 메시지 재시도 중입니다. topic={}, key={}, attempt={}",
                        record.topic(), record.key(), deliveryAttempt, ex)
        );
        return errorHandler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConsumerFactory<Object, Object> consumerFactory,
            CommonErrorHandler kafkaCommonErrorHandler,
            @Value("${spring.kafka.listener.concurrency:3}") int concurrency
    ) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(kafkaCommonErrorHandler);
        factory.setConcurrency(concurrency);

        // MANUAL_IMMEDIATE로 listener 내부에서 acknowledge()를 호출한 시점에 바로 offset 커밋
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }

    private String resolveDltTopic(ConsumerRecord<?, ?> record) {
        return switch (record.topic()) {
            case KafkaTopics.ORDER_CREATED -> KafkaTopics.ORDER_CREATED_DLT;
            case KafkaTopics.REFUND_REQUESTED -> KafkaTopics.REFUND_REQUESTED_DLT;
            case KafkaTopics.STOCK_FAILED -> KafkaTopics.STOCK_FAILED_DLT;
            default -> record.topic() + ".DLT";
        };
    }
}
