-- Targeted fixture for multi-instance payment expiration scheduling.
-- All rows are older than the default five-minute TTL and are eligible in
-- the same scheduler cycle.
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
    'mock-expired-ready-scaleout-' || fixture_no,
    'CARD',
    'READY',
    0,
    0,
    CURRENT_TIMESTAMP - INTERVAL '10 minutes',
    CURRENT_TIMESTAMP - INTERVAL '10 minutes',
    CURRENT_TIMESTAMP - INTERVAL '10 minutes'
FROM generate_series(1, 400) AS fixture(fixture_no);
