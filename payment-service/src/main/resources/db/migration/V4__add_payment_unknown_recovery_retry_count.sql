-- ========================
-- p_payments unknown recovery retry count
-- ========================
ALTER TABLE p_payments
    ADD COLUMN IF NOT EXISTS unknown_recovery_retry_count INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_payments_status_unknown_recovery_retry_updated_at
    ON p_payments (payment_status, unknown_recovery_retry_count, updated_at);
