-- Targeted fixture for multi-instance UNKNOWN recovery overlap.
-- The provider id matches WireMock's delayed lookup mapping, so each recovery
-- attempt occupies the scheduler long enough for another instance to select
-- the same candidate set.
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
    'mock-lookup-timeout-scaleout-overlap-' || fixture_no,
    'CARD',
    'CONFIRM_UNKNOWN',
    0,
    0,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM generate_series(1, 10) AS fixture(fixture_no);
