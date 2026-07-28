#!/usr/bin/env bash
set -euo pipefail

cd /opt/omc-payment
mkdir -p load-test/results

RATE="${RATE:-300}"
DURATION="${DURATION:-90s}"
TIMEOUT_WEIGHT="${TIMEOUT_WEIGHT:-20}"
SUCCESS_WEIGHT="$((100 - TIMEOUT_WEIGHT))"
RESULT_FILE="/results/payment-$(date +%Y%m%d-%H%M%S).json"

docker compose --env-file .env.gcp -f docker-compose.test.yml run --rm \
  -e RATE="${RATE}" \
  -e DURATION="${DURATION}" \
  -e PRE_ALLOCATED_VUS="${PRE_ALLOCATED_VUS:-300}" \
  -e MAX_VUS="${MAX_VUS:-600}" \
  -e SUCCESS_WEIGHT="${SUCCESS_WEIGHT}" \
  -e CARD_LIMIT_WEIGHT=0 \
  -e APPROVED_TIMEOUT_WEIGHT="${TIMEOUT_WEIGHT}" \
  -e FAILED_AFTER_TIMEOUT_WEIGHT=0 \
  -e PENDING_LONG_TIMEOUT_WEIGHT=0 \
  -e NOT_APPROVED_TIMEOUT_WEIGHT=0 \
  -e LOOKUP_TIMEOUT_WEIGHT=0 \
  -e LOOKUP_RATE_LIMIT_WEIGHT=0 \
  -e LOOKUP_SERVER_ERROR_WEIGHT=0 \
  -e CANCELED_AFTER_TIMEOUT_WEIGHT=0 \
  -e NETWORK_ERROR_WEIGHT=0 \
  k6 run \
  --out experimental-prometheus-rw \
  --summary-export "${RESULT_FILE}" \
  /scripts/payment-wiremock-confirm.k6.js
