package com.omc.payment.infrastructure.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;

@EnableKafka
@Configuration
public class KafkaTopicConfig {

    /*
     * 구독 토픽
     * */
    @Bean
    public NewTopic orderCreatedTopic(
            @Value("${kafka.topic.default-partitions:3}") int partitions,
            @Value("${kafka.topic.default-replication-factor:1}") short replicationFactor
    ) {
        return topic(KafkaTopics.ORDER_CREATED, partitions, replicationFactor);
    }

    @Bean
    public NewTopic refundRequestedTopic(
            @Value("${kafka.topic.default-partitions:3}") int partitions,
            @Value("${kafka.topic.default-replication-factor:1}") short replicationFactor
    ) {
        return topic(KafkaTopics.REFUND_REQUESTED, partitions, replicationFactor);
    }

    @Bean
    public NewTopic stockFailedTopic(
            @Value("${kafka.topic.default-partitions:3}") int partitions,
            @Value("${kafka.topic.default-replication-factor:1}") short replicationFactor
    ) {
        return topic(KafkaTopics.STOCK_FAILED, partitions, replicationFactor);
    }

    /*
     * DLT 토픽
     * */
    @Bean
    public NewTopic orderCreatedDltTopic(
            @Value("${kafka.topic.default-partitions:3}") int partitions,
            @Value("${kafka.topic.default-replication-factor:1}") short replicationFactor
    ) {
        return topic(KafkaTopics.ORDER_CREATED_DLT, partitions, replicationFactor);
    }

    @Bean
    public NewTopic refundRequestedDltTopic(
            @Value("${kafka.topic.default-partitions:3}") int partitions,
            @Value("${kafka.topic.default-replication-factor:1}") short replicationFactor
    ) {
        return topic(KafkaTopics.REFUND_REQUESTED_DLT, partitions, replicationFactor);
    }

    @Bean
    public NewTopic stockFailedDltTopic(
            @Value("${kafka.topic.default-partitions:3}") int partitions,
            @Value("${kafka.topic.default-replication-factor:1}") short replicationFactor
    ) {
        return topic(KafkaTopics.STOCK_FAILED_DLT, partitions, replicationFactor);
    }

    /*
     * 발행 도픽
     * */
    @Bean
    public NewTopic paymentCompletedTopic(
            @Value("${kafka.topic.default-partitions:3}") int partitions,
            @Value("${kafka.topic.default-replication-factor:1}") short replicationFactor
    ) {
        return topic(KafkaTopics.PAYMENT_COMPLETED, partitions, replicationFactor);
    }

    @Bean
    public NewTopic paymentFailedTopic(
            @Value("${kafka.topic.default-partitions:3}") int partitions,
            @Value("${kafka.topic.default-replication-factor:1}") short replicationFactor
    ) {
        return topic(KafkaTopics.PAYMENT_FAILED, partitions, replicationFactor);
    }

    @Bean
    public NewTopic refundDoneTopic(
            @Value("${kafka.topic.default-partitions:3}") int partitions,
            @Value("${kafka.topic.default-replication-factor:1}") short replicationFactor
    ) {
        return topic(KafkaTopics.REFUND_DONE, partitions, replicationFactor);
    }

    // 토픽 설정
    // 삭제 정책 명시
    private NewTopic topic(String name, int partitions, short replicationFactor) {
        return TopicBuilder.name(name)
                .partitions(partitions)
                .replicas(replicationFactor)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .build();
    }
}
