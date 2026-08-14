package com.omc.payment.infrastructure.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class PaymentRecoveryClaimRepository {

    // 다른 인스턴스가 선점 중인 결제를 제외하고 작업 소유권과 lease를 원자적으로 갱신
    private static final String CLAIM_SQL = """
            WITH candidates AS (
                SELECT payment_id
                FROM payment_db.p_payments
                WHERE payment_status IN ('CONFIRM_UNKNOWN', 'CANCEL_UNKNOWN')
                  AND (recovery_lease_until IS NULL OR recovery_lease_until < CURRENT_TIMESTAMP)
                ORDER BY updated_at ASC NULLS FIRST, payment_id ASC
                FOR UPDATE SKIP LOCKED
                LIMIT ?
            )
            UPDATE payment_db.p_payments payment
               SET recovery_claim_owner = ?,
                   recovery_lease_until = CURRENT_TIMESTAMP + (CAST(? AS BIGINT) * INTERVAL '1 millisecond')
              FROM candidates
             WHERE payment.payment_id = candidates.payment_id
            RETURNING payment.payment_id
            """;

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public List<UUID> claimBatch(String owner, int batchSize, Duration leaseDuration) {
        if (owner == null || owner.isBlank() || batchSize <= 0 || leaseDuration == null
                || leaseDuration.isZero() || leaseDuration.isNegative()) {
            return List.of();
        }

        return jdbcTemplate.query(
                CLAIM_SQL,
                preparedStatement -> {
                    preparedStatement.setInt(1, batchSize);
                    preparedStatement.setString(2, owner);
                    preparedStatement.setLong(3, leaseDuration.toMillis());
                },
                (resultSet, rowNumber) -> resultSet.getObject("payment_id", UUID.class)
        );
    }

    // 현재 복구 작업이 선점한 결제만 해제
    @Transactional
    public int releaseClaims(String owner) {
        if (owner == null || owner.isBlank()) {
            return 0;
        }
        return jdbcTemplate.update(
                """
                UPDATE payment_db.p_payments
                   SET recovery_claim_owner = NULL,
                       recovery_lease_until = NULL
                 WHERE recovery_claim_owner = ?
                """,
                owner
        );
    }
}
