#!/usr/bin/env bash
set -euo pipefail

bootstrap_server="${1:-localhost:29092}"
consumer_group="${2:-payment-service}"
topic="${3:-order.created}"
sample_interval_seconds="${4:-2}"
sample_count="${5:-50}"

echo "timestamp_utc,partitions,current_offset,log_end_offset,lag,active_hosts"

for ((sample = 0; sample < sample_count; sample++)); do
  description="$({
    sudo docker exec omc-kafka kafka-consumer-groups \
      --bootstrap-server "${bootstrap_server}" \
      --describe \
      --group "${consumer_group}" 2>/dev/null || true
  })"

  metrics="$(awk -v target_topic="${topic}" '
    $2 == target_topic {
      partitions += 1
      current_offset += $4
      log_end_offset += $5
      lag += $6
      if ($8 != "-") {
        hosts[$8] = 1
      }
    }
    END {
      host_count = 0
      for (host in hosts) {
        host_count += 1
      }
      printf "%d,%d,%d,%d,%d", partitions, current_offset, log_end_offset, lag, host_count
    }
  ' <<<"${description}")"

  echo "$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ),${metrics}"
  sleep "${sample_interval_seconds}"
done
