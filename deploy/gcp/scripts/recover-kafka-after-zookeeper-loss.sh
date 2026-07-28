#!/usr/bin/env bash
set -euo pipefail

KAFKA_ROOT="/mnt/omc-kafka"
KAFKA_DATA="${KAFKA_ROOT}/data"
ZOOKEEPER_ROOT="${KAFKA_ROOT}/zookeeper"
BACKUP_NAME="${1:?backup directory name is required}"
BACKUP_PATH="${KAFKA_ROOT}/${BACKUP_NAME}"

test "$(readlink -f "${KAFKA_ROOT}")" = "${KAFKA_ROOT}"
mountpoint -q "${KAFKA_ROOT}"
test -d "${KAFKA_DATA}"

if [ -e "${BACKUP_PATH}" ]; then
  echo "backup path already exists: ${BACKUP_PATH}" >&2
  exit 1
fi

mkdir -p "${ZOOKEEPER_ROOT}/data" "${ZOOKEEPER_ROOT}/log"

docker stop omc-zookeeper >/dev/null
docker cp omc-zookeeper:/var/lib/zookeeper/data/. "${ZOOKEEPER_ROOT}/data"
docker cp omc-zookeeper:/var/lib/zookeeper/log/. "${ZOOKEEPER_ROOT}/log"
docker rm -f omc-kafka omc-zookeeper >/dev/null

mv "${KAFKA_DATA}" "${BACKUP_PATH}"
mkdir -p "${KAFKA_DATA}"
chown -R 1000:1000 "${KAFKA_DATA}" "${ZOOKEEPER_ROOT}"

echo "Kafka data was preserved at ${BACKUP_PATH}"
echo "ZooKeeper state was copied to ${ZOOKEEPER_ROOT}"
