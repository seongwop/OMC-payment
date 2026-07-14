-- ========================
-- p_payments
-- ========================
ALTER TABLE p_payments
    ADD COLUMN IF NOT EXISTS drop_id UUID,
    ADD COLUMN IF NOT EXISTS raffle_id UUID,
    ADD COLUMN IF NOT EXISTS product_id UUID;

ALTER TABLE p_payments
    ALTER COLUMN payment_id SET DEFAULT uuidv7();

-- ========================
-- p_payment_outbox_events
-- ========================
CREATE TABLE p_payment_outbox_events (
    event_id        UUID         NOT NULL DEFAULT uuidv7(),
    aggregate_type  VARCHAR(50)  NOT NULL,
    aggregate_id    UUID         NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    retry_count     INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at    TIMESTAMP,
    PRIMARY KEY (event_id)
);

CREATE INDEX idx_payment_outbox_status_retry_created_at
    ON p_payment_outbox_events (status, retry_count, created_at);

CREATE INDEX idx_payment_outbox_aggregate_id
    ON p_payment_outbox_events (aggregate_id);

-- ========================
-- p_payment_inbox_events
-- ========================
CREATE TABLE p_payment_inbox_events (
    event_id      VARCHAR(100) NOT NULL,
    topic         VARCHAR(100) NOT NULL,
    processed_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (event_id)
);

CREATE INDEX idx_payment_inbox_topic_processed_at
    ON p_payment_inbox_events (topic, processed_at);
