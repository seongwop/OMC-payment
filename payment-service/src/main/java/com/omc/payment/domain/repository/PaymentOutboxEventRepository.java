package com.omc.payment.domain.repository;

import com.omc.payment.domain.entity.PaymentOutboxEvent;
import com.omc.payment.domain.enums.OutboxEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PaymentOutboxEventRepository extends JpaRepository<PaymentOutboxEvent, UUID> {
    // 발행 대기 또는 재시도 대상 이벤트를 오래된 순서대로 100개씩 조회
    List<PaymentOutboxEvent> findTop100ByStatusInAndRetryCountLessThanOrderByCreatedAtAsc(
            Collection<OutboxEventStatus> status, int retryCount
    );

    /*
     * 수정 쿼리 실행 전 영속성 컨텍스트 flush, 실행 후 clear
     * */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PaymentOutboxEvent event
            set event.status = :publishingStatus
            where event.eventId = :eventId
              and event.status in :claimableStatuses
              and event.retryCount < :maxRetryCount
            """)
    int claimForPublishing(
            @Param("eventId") UUID eventId,
            @Param("publishingStatus") OutboxEventStatus publishingStatus,
            @Param("claimableStatuses") Collection<OutboxEventStatus> claimableStatuses,
            @Param("maxRetryCount") int maxRetryCount
    );

}
