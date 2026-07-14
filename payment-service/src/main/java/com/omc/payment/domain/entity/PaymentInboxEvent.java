package com.omc.payment.domain.entity;

import com.omc.payment.domain.enums.PaymentInboxStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "p_payment_inbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentInboxEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "topic", nullable = false)
    private String topic;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private PaymentInboxStatus status;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    public static PaymentInboxEvent create(String eventId, String topic) {
        return PaymentInboxEvent.builder()
                .eventId(eventId)
                .topic(topic)
                .status(PaymentInboxStatus.PROCESSING)
                .processedAt(LocalDateTime.now())
                .build();
    }

    public boolean isFailed() {
        return status == PaymentInboxStatus.FAILED;
    }

    public void markProcessing() {
        this.status = PaymentInboxStatus.PROCESSING;
    }

    public void markProcessed() {
        this.status = PaymentInboxStatus.PROCESSED;
        this.processedAt = LocalDateTime.now();
    }

    public void markFailed() {
        this.status = PaymentInboxStatus.FAILED;
    }

    @Builder(access = AccessLevel.PRIVATE)
    private PaymentInboxEvent(
            String eventId,
            String topic,
            PaymentInboxStatus status,
            LocalDateTime processedAt
    ) {
        this.eventId = eventId;
        this.topic = topic;
        this.status = status;
        this.processedAt = processedAt;
    }
}
