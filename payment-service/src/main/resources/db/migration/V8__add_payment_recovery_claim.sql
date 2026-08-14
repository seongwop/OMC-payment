-- 스케일아웃 환경에서 UNKNOWN 결제 복구 작업의 소유권을 관리하기 위한 컬럼 추가
ALTER TABLE p_payments
    ADD COLUMN IF NOT EXISTS recovery_claim_owner VARCHAR(100),
    ADD COLUMN IF NOT EXISTS recovery_lease_until TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_payments_unknown_recovery_claim
    ON p_payments (payment_status, recovery_lease_until, updated_at);
