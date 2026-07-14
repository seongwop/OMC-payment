-- ========================
-- p_payment_inbox_events status
-- ========================
ALTER TABLE p_payment_inbox_events
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'PROCESSED';

CREATE INDEX IF NOT EXISTS idx_payment_inbox_topic_status_processed_at
    ON p_payment_inbox_events (topic, status, processed_at);
