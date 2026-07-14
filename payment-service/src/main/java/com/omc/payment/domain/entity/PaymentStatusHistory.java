package com.omc.payment.domain.entity;

import com.omc.common.util.UuidV7Generator;
import com.omc.payment.domain.enums.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "p_payment_status_histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentStatusHistory {

    @Id
    @Column(name = "payment_status_history_id", nullable = false, updatable = false)
    private UUID paymentStatusHistoryId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", nullable = false, updatable = false)
    private PaymentStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_status", nullable = false, updatable = false)
    private PaymentStatus currentStatus;

    @Column(name = "reason", columnDefinition = "TEXT", updatable = false)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static PaymentStatusHistory create(
            UUID paymentId,
            UUID orderId,
            PaymentStatus previousStatus,
            PaymentStatus currentStatus,
            String reason
    ) {
        return PaymentStatusHistory.builder()
                .paymentStatusHistoryId(UuidV7Generator.generate())
                .paymentId(paymentId)
                .orderId(orderId)
                .previousStatus(previousStatus)
                .currentStatus(currentStatus)
                .reason(reason)
                .build();
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @Builder(access = AccessLevel.PRIVATE)
    private PaymentStatusHistory(
            UUID paymentStatusHistoryId,
            UUID paymentId,
            UUID orderId,
            PaymentStatus previousStatus,
            PaymentStatus currentStatus,
            String reason,
            LocalDateTime createdAt
    ) {
        this.paymentStatusHistoryId = paymentStatusHistoryId;
        this.paymentId = paymentId;
        this.orderId = orderId;
        this.previousStatus = previousStatus;
        this.currentStatus = currentStatus;
        this.reason = reason;
        this.createdAt = createdAt;
    }
}
