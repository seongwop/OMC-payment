ALTER TABLE p_payment_status_histories
    ALTER COLUMN payment_status_history_id SET DEFAULT uuidv7();

CREATE TABLE p_payment_reconciliation_results (
    reconciliation_result_id UUID         NOT NULL DEFAULT uuidv7(),
    payment_id               UUID         NOT NULL,
    order_id                 UUID         NOT NULL,
    provider_payment_id      VARCHAR(255) NOT NULL,
    db_status                VARCHAR(50)  NOT NULL,
    pg_status                VARCHAR(50),
    db_amount                BIGINT       NOT NULL,
    pg_amount                BIGINT,
    result_type              VARCHAR(50)  NOT NULL,
    checked_at               TIMESTAMP    NOT NULL,
    PRIMARY KEY (reconciliation_result_id)
);

CREATE INDEX idx_payment_reconciliation_results_payment_id_checked_at
    ON p_payment_reconciliation_results (payment_id, checked_at);

CREATE INDEX idx_payment_reconciliation_results_result_type_checked_at
    ON p_payment_reconciliation_results (result_type, checked_at);