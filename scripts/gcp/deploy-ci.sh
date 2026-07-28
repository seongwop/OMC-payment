#!/usr/bin/env bash
set -euo pipefail

PROJECT_ID="${GCP_PROJECT_ID:?GCP_PROJECT_ID is required}"
REGION="${GCP_REGION:-asia-northeast3}"
ZONE="${GCP_ZONE:-asia-northeast3-a}"
IMAGE_TAG="${IMAGE_TAG:?IMAGE_TAG is required}"
REMOTE_DIR="/opt/omc-payment"
REGISTRY_HOST="${REGION}-docker.pkg.dev"
REGISTRY="${REGISTRY_HOST}/${PROJECT_ID}/omc-payment"

APP_VM="omc-payment-app-vm"
INFRA_VM="omc-payment-infra-vm"
TEST_VM="omc-payment-test-vm"
DEPLOY_ROLES="${DEPLOY_ROLES:-infra app test}"

GCLOUD_SSH_FLAGS=(
  --project "${PROJECT_ID}"
  --zone "${ZONE}"
  --tunnel-through-iap
  --quiet
  --strict-host-key-checking=no
  --ssh-key-expire-after=2h
)

retry() {
  local description="$1"
  shift

  for attempt in 1 2 3; do
    if "$@"; then
      return 0
    fi
    echo "${description} failed. attempt=${attempt}" >&2
    if [ "${attempt}" -eq 3 ]; then
      return 1
    fi
    sleep $((attempt * 10))
  done
}

wait_for_docker() {
  local vm_name="$1"

  echo "Waiting for Docker startup on ${vm_name}"
  for _ in $(seq 1 60); do
    if gcloud compute ssh "${vm_name}" "${GCLOUD_SSH_FLAGS[@]}" \
      --command "sudo systemctl is-active docker >/dev/null && sudo systemctl cat omc-payment-compose.service >/dev/null"; then
      return 0
    fi
    sleep 10
  done

  echo "Docker startup did not complete on ${vm_name}" >&2
  return 1
}

copy_common_files() {
  local role="$1"
  local role_dir="$2"

  cp "deploy/gcp/docker-compose.${role}.yml" "${role_dir}/"
  cp "deploy/gcp/scripts/deploy-role.sh" "${role_dir}/scripts/"
  cp "deploy/gcp/scripts/wait-role-health.sh" "${role_dir}/scripts/"
}

create_role_archive() {
  local role="$1"
  local temp_root="$2"
  local env_file="$3"
  local role_dir="${temp_root}/${role}"
  local archive="${temp_root}/omc-payment-${role}.tar.gz"

  mkdir -p "${role_dir}/scripts"
  cp "${env_file}" "${role_dir}/.env.gcp"
  copy_common_files "${role}" "${role_dir}"

  case "${role}" in
    infra)
      mkdir -p \
        "${role_dir}/assets" \
        "${role_dir}/simulators/toss-pg" \
        "${role_dir}/data/redis"
      cp "infra/local/01_create_payment_schema.sql" "${role_dir}/assets/"
      cp -R "simulators/toss-pg/mappings" "${role_dir}/simulators/toss-pg/"
      cp "deploy/gcp/scripts/recover-kafka-after-zookeeper-loss.sh" "${role_dir}/scripts/"
      ;;
    test)
      mkdir -p \
        "${role_dir}/grafana" \
        "${role_dir}/load-test/results"
      cp "deploy/gcp/prometheus.yml" "${role_dir}/"
      cp -R "deploy/gcp/grafana/provisioning" "${role_dir}/grafana/"
      cp -R "observability/grafana/dashboards" "${role_dir}/grafana/"
      cp -R "load-test/k6" "${role_dir}/load-test/"
      cp "deploy/gcp/scripts/run-load-test.sh" "${role_dir}/scripts/"
      ;;
  esac

  tar -czf "${archive}" -C "${role_dir}" .
  printf '%s' "${archive}"
}

deploy_role() {
  local role="$1"
  local vm_name="$2"
  local archive="$3"
  local remote_archive="/tmp/omc-payment-${role}.tar.gz"

  echo "Deploying ${role} to ${vm_name}"
  retry "copy ${role} archive" \
    gcloud compute scp "${archive}" "${vm_name}:${remote_archive}" "${GCLOUD_SSH_FLAGS[@]}"

  gcloud compute ssh "${vm_name}" "${GCLOUD_SSH_FLAGS[@]}" \
    --command "set -e
sudo mkdir -p '${REMOTE_DIR}'
sudo tar -xzf '${remote_archive}' -C '${REMOTE_DIR}' --no-same-owner
sudo rm -f '${remote_archive}'
sudo chmod +x '${REMOTE_DIR}'/scripts/*.sh
sudo bash '${REMOTE_DIR}/scripts/deploy-role.sh' '${role}' '${REGISTRY_HOST}'
sudo timeout 610 bash '${REMOTE_DIR}/scripts/wait-role-health.sh' '${role}'"
}

temp_root="$(mktemp -d)"
cleanup() {
  resolved_temp_root="$(readlink -f "${temp_root}")"
  if [ -n "${resolved_temp_root}" ] \
    && [ "${resolved_temp_root}" != "/" ] \
    && [ -d "${resolved_temp_root}" ]; then
    rm -rf -- "${resolved_temp_root}"
  fi
}
trap cleanup EXIT

umask 077
db_password="$(
  gcloud secrets versions access latest \
    --secret omc-payment-db-password \
    --project "${PROJECT_ID}"
)"
grafana_password="$(
  gcloud secrets versions access latest \
    --secret omc-payment-grafana-admin-password \
    --project "${PROJECT_ID}"
)"

env_file="${temp_root}/runtime.env"
cat >"${env_file}" <<EOF
IMAGE_REGISTRY=${REGISTRY}
IMAGE_TAG=${IMAGE_TAG}
APP_INTERNAL_IP=10.20.0.10
INFRA_INTERNAL_IP=10.20.0.20
TEST_INTERNAL_IP=10.20.0.30
DB_USERNAME=omc
DB_PASSWORD=${db_password}
GRAFANA_ADMIN_PASSWORD=${grafana_password}
TOSS_SECRET_KEY=test-secret-key
GATEWAY_SECRET=local-secret
KAFKA_TOPIC_DEFAULT_PARTITIONS=3
KAFKA_LISTENER_CONCURRENCY=3
TOSS_READ_TIMEOUT_MS=3000
TOSS_MAX_CONNECTIONS=170
TOSS_MAX_CONNECTIONS_PER_ROUTE=170
TOSS_BULKHEAD_MAX_CONCURRENT_CALLS=160
PAYMENT_DATASOURCE_MAX_POOL_SIZE=30
PAYMENT_UNKNOWN_RECOVERY_INITIAL_DELAY_MS=60000
PAYMENT_UNKNOWN_RECOVERY_FIXED_DELAY_MS=30000
TEST_TOOLS_VERIFIER_ENABLED=false
TEST_TOOLS_VERIFICATION_TIMEOUT=15s
EOF

roles=()
vm_names=()
for role in ${DEPLOY_ROLES}; do
  case "${role}" in
    infra)
      roles+=("${role}")
      vm_names+=("${INFRA_VM}")
      ;;
    app)
      roles+=("${role}")
      vm_names+=("${APP_VM}")
      ;;
    test)
      roles+=("${role}")
      vm_names+=("${TEST_VM}")
      ;;
    *)
      echo "Unknown deployment role: ${role}" >&2
      exit 2
      ;;
  esac
done

if [ "${#roles[@]}" -eq 0 ]; then
  echo "At least one deployment role is required" >&2
  exit 2
fi

echo "Ensuring deployment VMs are running"
stopped_vms=()
for vm_name in "${vm_names[@]}"; do
  vm_status="$(
    gcloud compute instances describe "${vm_name}" \
      --project "${PROJECT_ID}" \
      --zone "${ZONE}" \
      --format "value(status)"
  )"
  if [ "${vm_status}" != "RUNNING" ]; then
    stopped_vms+=("${vm_name}")
  fi
done

if [ "${#stopped_vms[@]}" -gt 0 ]; then
  gcloud compute instances start "${stopped_vms[@]}" \
    --project "${PROJECT_ID}" \
    --zone "${ZONE}" \
    --quiet
fi

for vm_name in "${vm_names[@]}"; do
  wait_for_docker "${vm_name}"
done

for role in "${roles[@]}"; do
  case "${role}" in
    infra) vm_name="${INFRA_VM}" ;;
    app) vm_name="${APP_VM}" ;;
    test) vm_name="${TEST_VM}" ;;
  esac

  archive="$(create_role_archive "${role}" "${temp_root}" "${env_file}")"
  deploy_role "${role}" "${vm_name}" "${archive}"
done

echo "Deployment completed for roles ${DEPLOY_ROLES}; image tag ${IMAGE_TAG}"
