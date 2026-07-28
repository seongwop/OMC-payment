#!/usr/bin/env bash
set -euo pipefail

ROLE="__ROLE__"
REMOTE_DIR="/opt/omc-payment"
COMPOSE_FILE="docker-compose.${ROLE}.yml"

export DEBIAN_FRONTEND=noninteractive

install_docker() {
  if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
    systemctl enable --now docker
    return
  fi

  apt-get update
  apt-get install -y ca-certificates curl gnupg
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/debian/gpg \
    | gpg --dearmor --yes -o /etc/apt/keyrings/docker.gpg
  chmod a+r /etc/apt/keyrings/docker.gpg

  . /etc/os-release
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian ${VERSION_CODENAME} stable" \
    >/etc/apt/sources.list.d/docker.list

  apt-get update
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  systemctl enable --now docker
}

prepare_disk() {
  local device="$1"
  local mount_point="$2"

  for _ in $(seq 1 60); do
    if [ -b "${device}" ]; then
      break
    fi
    sleep 2
  done

  if [ ! -b "${device}" ]; then
    echo "disk was not attached: ${device}" >&2
    return 1
  fi

  if ! blkid "${device}" >/dev/null 2>&1; then
    mkfs.ext4 -F "${device}"
  fi

  mkdir -p "${mount_point}"
  local uuid
  uuid="$(blkid -s UUID -o value "${device}")"
  if ! grep -q "UUID=${uuid}" /etc/fstab; then
    echo "UUID=${uuid} ${mount_point} ext4 defaults,nofail 0 2" >>/etc/fstab
  fi
  mountpoint -q "${mount_point}" || mount "${mount_point}"
}

install_docker
mkdir -p "${REMOTE_DIR}"

if [ "${ROLE}" = "infra" ]; then
  prepare_disk "/dev/disk/by-id/google-postgres-data" "/mnt/omc-postgres"
  prepare_disk "/dev/disk/by-id/google-kafka-data" "/mnt/omc-kafka"
  mkdir -p "${REMOTE_DIR}/data/redis"
  mkdir -p \
    "/mnt/omc-kafka/data" \
    "/mnt/omc-kafka/zookeeper/data" \
    "/mnt/omc-kafka/zookeeper/log"
  chown -R 1000:1000 \
    "/mnt/omc-kafka/data" \
    "/mnt/omc-kafka/zookeeper"
fi

echo "${ROLE}" >/etc/omc-payment-role
echo "${COMPOSE_FILE}" >/etc/omc-payment-compose-file

cat >/usr/local/bin/omc-payment-compose-up <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail

REMOTE_DIR="/opt/omc-payment"
COMPOSE_FILE="$(cat /etc/omc-payment-compose-file)"

if [ ! -f "${REMOTE_DIR}/${COMPOSE_FILE}" ] || [ ! -f "${REMOTE_DIR}/.env.gcp" ]; then
  exit 0
fi

cd "${REMOTE_DIR}"
docker compose --env-file .env.gcp -f "${COMPOSE_FILE}" up -d --remove-orphans
SCRIPT
chmod +x /usr/local/bin/omc-payment-compose-up

cat >/etc/systemd/system/omc-payment-compose.service <<'UNIT'
[Unit]
Description=OMC payment Docker Compose
After=docker.service network-online.target
Wants=docker.service network-online.target

[Service]
Type=oneshot
ExecStart=/usr/local/bin/omc-payment-compose-up
RemainAfterExit=yes
TimeoutStartSec=20min
Restart=on-failure
RestartSec=30s

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable omc-payment-compose.service
