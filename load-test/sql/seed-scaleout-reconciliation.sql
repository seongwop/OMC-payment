-- Targeted fixture for simultaneous PG-DB reconciliation jobs.
-- WireMock resolves these provider ids as FAILED while the DB stores PAID,
-- causing each job execution to append one mismatch result per payment.
INSERT INTO payment_db.p_payments (
    payment_id,
    order_id,
    user_id,
    sales_type,
    original_amount,
    discount_amount,
    final_amount,
    provider,
    provider_payment_id,
    payment_method,
    payment_status,
    unknown_recovery_retry_count,
    version,
    requested_at,
    approved_at,
    created_at,
    updated_at
)
SELECT
    uuidv7(),
    gen_random_uuid(),
    gen_random_uuid(),
    'DROP',
    10000,
    0,
    10000,
    'TOSS',
    'mock-failed-after-timeout-scaleout-reconciliation-' || fixture_no,
    'CARD',
    'PAID',
    0,
    0,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM generate_series(1, 20) AS fixture(fixture_no);
