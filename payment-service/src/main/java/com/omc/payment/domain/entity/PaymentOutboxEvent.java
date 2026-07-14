package com.omc.payment.domain.entity;

import com.omc.payment.domain.enums.OutboxAggregateType;
import com.omc.payment.domain.enums.OutboxEventStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "p_payment_outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentOutboxEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "aggregate_type", nullable = false)
    private OutboxAggregateType aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OutboxEventStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    public static PaymentOutboxEvent create(
            UUID eventId,
            OutboxAggregateType aggregateType,
            UUID aggregateId,
            String eventType,
            String payload
    ) {
        return PaymentOutboxEvent.builder()
                .eventId(eventId)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(payload)
                .status(OutboxEventStatus.INIT)
                .retryCount(0)
                .build();
    }

    public void markPublished() {
        this.status = OutboxEventStatus.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
    }

    public void markFailed(int maxRetryCount) {
        this.status = OutboxEventStatus.FAILED;
        this.retryCount += 1;
        this.status = retryCount >= maxRetryCount
                ? OutboxEventStatus.DEAD
                : OutboxEventStatus.FAILED;
    }

    public void resetToInit() {
        this.status = OutboxEventStatus.INIT;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @Builder(access = AccessLevel.PRIVATE)
    private PaymentOutboxEvent(
            UUID eventId,
            OutboxAggregateType aggregateType,
            UUID aggregateId,
            String eventType,
            String payload,
            OutboxEventStatus status,
            int retryCount,
            LocalDateTime createdAt,
            LocalDateTime publishedAt
    ) {
        this.eventId = eventId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = status;
        this.retryCount = retryCount;
        this.createdAt = createdAt;
        this.publishedAt = publishedAt;
    }
}
