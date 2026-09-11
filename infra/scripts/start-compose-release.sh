#!/usr/bin/env bash
set -euo pipefail

if [[ ${EUID} -ne 0 ]]; then
  echo "start-compose-release.sh must run as root" >&2
  exit 1
fi

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <seatflow-root>" >&2
  exit 2
fi

seatflow_root=$1
runtime_file=/run/seatflow/runtime.env
compose=(docker compose
  -f "${seatflow_root}/docker-compose.yml"
  -f "${seatflow_root}/docker-compose.services.yml"
  -f "${seatflow_root}/docker-compose.monitoring.yml"
  -f "${seatflow_root}/docker-compose.prod.yml"
  -f "${seatflow_root}/docker-compose.prod-health.yml"
  --env-file "${runtime_file}")

"${compose[@]}" config --quiet

wait_for_service() {
  local service=$1
  local timeout_seconds=${2:-600}
  local deadline=$((SECONDS + timeout_seconds))
  local container_id status health restarts

  while (( SECONDS < deadline )); do
    container_id=$("${compose[@]}" ps -q "${service}")
    if [[ -n ${container_id} ]]; then
      status=$(docker inspect --format '{{.State.Status}}' "${container_id}")
      health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "${container_id}")
      restarts=$(docker inspect --format '{{.RestartCount}}' "${container_id}")

      if [[ ${status} == running && (${health} == healthy || ${health} == none) ]]; then
        echo "${service} is ready (health=${health}, restarts=${restarts})"
        return 0
      fi

      # A health check may turn unhealthy while a CPU-constrained JVM is still
      # completing a legitimate cold start, then recover without a restart.
      # Keep waiting until the bounded timeout unless the process actually dies
      # or enters a restart loop.
      if [[ ${status} == exited || ${status} == dead || ${restarts} -gt 5 ]]; then
        echo "${service} failed while waiting for readiness (status=${status}, health=${health}, restarts=${restarts})" >&2
        docker logs --tail 120 "${container_id}" >&2 || true
        return 1
      fi
    fi
    sleep 5
  done

  echo "Timed out after ${timeout_seconds}s waiting for ${service}" >&2
  container_id=$("${compose[@]}" ps -q "${service}" || true)
  if [[ -n ${container_id} ]]; then
    docker inspect --format 'status={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} restarts={{.RestartCount}} oom={{.State.OOMKilled}}' "${container_id}" >&2 || true
    docker logs --tail 120 "${container_id}" >&2 || true
  fi
  return 1
}

start_batch() {
  local timeout_seconds=$1
  shift
  local services=("$@")
  "${compose[@]}" up -d --no-deps "${services[@]}"
  for service in "${services[@]}"; do
    wait_for_service "${service}" "${timeout_seconds}"
  done
}

# Avoid a JVM cold-start thundering herd on the 2-vCPU production VM. The old
# all-at-once Compose rollout made api-gateway compete with every backend JVM and
# routinely left frontend waiting on gateway health for several minutes.
"${compose[@]}" up -d --remove-orphans postgres redis kafka eureka-server
for service in postgres redis kafka eureka-server; do
  wait_for_service "${service}" 600
done

# Start the gateway alone after its infrastructure dependencies are healthy.
start_batch 300 api-gateway

# Keep each application batch small enough for the host CPU while still allowing
# useful parallelism. Database-backed services start with Flyway disabled in
# production because run-production-migrations.sh owns schema changes.
start_batch 480 user-service seat-map-service event-service
start_batch 480 reservation-service payment-service ticket-service
start_batch 480 realtime-service notification-service
# Analytics has the heaviest observed production cold start; allow a bounded
# ten-minute window so a recoverable health transition cannot prevent frontend startup.
start_batch 600 analytics-service ai-service

# Frontend can only become useful after gateway and all backend readiness is proven.
start_batch 180 frontend

# Observability is intentionally last so it cannot delay the customer-facing
# application cold start.
"${compose[@]}" up -d --no-deps \
  otel-collector prometheus kafka-exporter grafana tempo loki promtail

echo "Staged production Compose startup completed"
