CREATE TABLE p_payment_status_histories (
    payment_status_history_id UUID        NOT NULL DEFAULT gen_random_uuid(),
    payment_id                UUID        NOT NULL,
    order_id                  UUID        NOT NULL,
    previous_status           VARCHAR(50) NOT NULL,
    current_status            VARCHAR(50) NOT NULL,
    reason                    TEXT,
    created_at                TIMESTAMP   NOT NULL,
    PRIMARY KEY (payment_status_history_id)
);

CREATE INDEX idx_payment_status_histories_payment_id_created_at
    ON p_payment_status_histories (payment_id, created_at);

CREATE INDEX idx_payment_status_histories_order_id_created_at
    ON p_payment_status_histories (order_id, created_at);