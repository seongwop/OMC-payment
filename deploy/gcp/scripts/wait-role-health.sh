#!/usr/bin/env bash
set -euo pipefail

ROLE="${1:?role is required}"

case "${ROLE}" in
  infra)
    for _ in $(seq 1 120); do
      ready=true
      for container in omc-postgres omc-redis omc-zookeeper omc-kafka omc-toss-wiremock; do
        status="$(
          docker inspect \
            --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
            "${container}" 2>/dev/null || true
        )"
        if [ "${status}" != "healthy" ] && [ "${status}" != "running" ]; then
          ready=false
          break
        fi
      done
      if [ "${ready}" = "true" ]; then
        exit 0
      fi
      sleep 5
    done
    ;;
  app)
    for _ in $(seq 1 120); do
      if curl -fsS http://localhost:8085/actuator/health >/dev/null; then
        exit 0
      fi
      sleep 5
    done
    ;;
  test)
    for _ in $(seq 1 120); do
      if curl -fsS http://localhost:8090/actuator/health >/dev/null \
        && curl -fsS http://localhost:19090/-/healthy >/dev/null \
        && curl -fsS http://localhost:13000/api/health >/dev/null; then
        exit 0
      fi
      sleep 5
    done
    ;;
  *)
    echo "unknown role: ${ROLE}" >&2
    exit 2
    ;;
esac

echo "${ROLE} did not become healthy" >&2
exit 1
