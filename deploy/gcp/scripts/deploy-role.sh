#!/usr/bin/env bash
set -euo pipefail

ROLE="${1:?role is required}"
REGISTRY_HOST="${2:?registry host is required}"
REMOTE_DIR="/opt/omc-payment"
COMPOSE_FILE="docker-compose.${ROLE}.yml"

chmod 600 "${REMOTE_DIR}/.env.gcp"

if [ "${ROLE}" = "infra" ]; then
  mkdir -p \
    /mnt/omc-kafka/data \
    /mnt/omc-kafka/zookeeper/data \
    /mnt/omc-kafka/zookeeper/log
  chown -R 1000:1000 \
    /mnt/omc-kafka/data \
    /mnt/omc-kafka/zookeeper
fi

if [ "${ROLE}" != "infra" ]; then
  token="$(
    curl -fsS -H "Metadata-Flavor: Google" \
      "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token" \
      | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
  )"
  test -n "${token}"
  printf '%s' "${token}" \
    | docker login -u oauth2accesstoken --password-stdin "https://${REGISTRY_HOST}"
fi

cd "${REMOTE_DIR}"
docker compose --env-file .env.gcp -f "${COMPOSE_FILE}" config --quiet
docker compose --env-file .env.gcp -f "${COMPOSE_FILE}" pull
systemctl enable omc-payment-compose.service >/dev/null
systemctl restart omc-payment-compose.service
docker compose --env-file .env.gcp -f "${COMPOSE_FILE}" ps
