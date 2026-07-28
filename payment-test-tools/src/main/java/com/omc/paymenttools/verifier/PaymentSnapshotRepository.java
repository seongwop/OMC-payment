package com.omc.paymenttools.verifier;

import com.omc.paymenttools.verifier.model.OutboxSnapshot;
import com.omc.paymenttools.verifier.model.PaymentSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class PaymentSnapshotRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    // 주문 ID 기준 결제 상태 조회
    public Optional<PaymentSnapshot> findPayment(UUID orderId) {
        String sql = """
                SELECT payment_id,
                       order_id,
                       user_id,
                       payment_status,
                       provider_payment_id,
                       provider_cancellation_id,
                       unknown_recovery_retry_count,
                       created_at,
                       updated_at
                  FROM p_payments
                 WHERE order_id = :orderId
                """;

        List<PaymentSnapshot> results = jdbcTemplate.query(
                sql,
                Map.of("orderId", orderId),
                (rs, rowNum) -> new PaymentSnapshot(
                        rs.getObject("payment_id", UUID.class),
                        rs.getObject("order_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("payment_status"),
                        rs.getString("provider_payment_id"),
                        rs.getString("provider_cancellation_id"),
                        rs.getInt("unknown_recovery_retry_count"),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("updated_at") == null
                                ? null
                                : rs.getTimestamp("updated_at").toLocalDateTime()
                )
        );
        return results.stream().findFirst();
    }

    // 결제 ID 기준 Outbox 발행 상태 조회
    public List<OutboxSnapshot> findOutboxEvents(UUID paymentId) {
        String sql = """
                SELECT event_id,
                       event_type,
                       status,
                       retry_count,
                       created_at,
                       published_at
                  FROM p_payment_outbox_events
                 WHERE aggregate_id = :paymentId
                 ORDER BY created_at ASC
                """;

        return jdbcTemplate.query(
                sql,
                Map.of("paymentId", paymentId),
                (rs, rowNum) -> new OutboxSnapshot(
                        rs.getObject("event_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("status"),
                        rs.getInt("retry_count"),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("published_at") == null
                                ? null
                                : rs.getTimestamp("published_at").toLocalDateTime()
                )
        );
    }
}
