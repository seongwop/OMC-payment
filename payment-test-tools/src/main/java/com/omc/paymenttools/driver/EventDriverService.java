package com.omc.paymenttools.driver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omc.paymenttools.config.KafkaTopics;
import com.omc.paymenttools.driver.dto.OrderCreatedRequest;
import com.omc.paymenttools.driver.dto.PublishedEventResponse;
import com.omc.paymenttools.driver.dto.RefundRequestedRequest;
import com.omc.paymenttools.driver.dto.StockFailedRequest;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@RequiredArgsConstructor
public class EventDriverService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${payment-test-tools.publish-timeout:10s}")
    private Duration publishTimeout;

    // 주문 생성 이벤트 발행
    public PublishedEventResponse publishOrderCreated(OrderCreatedRequest request) {
        String eventId = resolveEventId(request.eventId());
        return publish(
                KafkaTopics.ORDER_CREATED,
                request.orderId().toString(),
                eventId,
                request.toEvent(eventId)
        );
    }

    // 환불 요청 이벤트 발행
    public PublishedEventResponse publishRefundRequested(RefundRequestedRequest request) {
        String eventId = resolveEventId(request.eventId());
        return publish(
                KafkaTopics.REFUND_REQUESTED,
                request.orderId().toString(),
                eventId,
                request.toEvent(eventId)
        );
    }

    // 재고 차감 실패 이벤트 발행
    public PublishedEventResponse publishStockFailed(StockFailedRequest request) {
        String eventId = resolveEventId(request.eventId());
        return publish(
                KafkaTopics.STOCK_FAILED,
                request.orderId().toString(),
                eventId,
                request.toEvent(eventId)
        );
    }

    // 이벤트를 JSON으로 직렬화하고 Kafka 발행 결과 반환
    private PublishedEventResponse publish(String topic, String key, String eventId, Object event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            SendResult<String, String> result = kafkaTemplate.send(topic, key, payload)
                    .get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
            RecordMetadata metadata = result.getRecordMetadata();
            return new PublishedEventResponse(
                    topic,
                    eventId,
                    key,
                    metadata.partition(),
                    metadata.offset(),
                    Instant.now()
            );
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("테스트 이벤트 직렬화에 실패했습니다.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka 테스트 이벤트 발행 중 인터럽트가 발생했습니다.", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Kafka 테스트 이벤트 발행에 실패했습니다.", e);
        }
    }

    // eventId 미입력 시 테스트용 ID 생성
    private String resolveEventId(String eventId) {
        return eventId == null || eventId.isBlank()
                ? UUID.randomUUID().toString()
                : eventId;
    }
}
