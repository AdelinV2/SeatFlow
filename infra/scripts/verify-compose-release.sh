#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "Usage: $0 <seatflow-root> [https-smoke-url]" >&2
  exit 2
fi

seatflow_root=$1
smoke_url=${2:-}
runtime_file=/run/seatflow/runtime.env
compose=(docker compose
  -f "${seatflow_root}/docker-compose.yml"
  -f "${seatflow_root}/docker-compose.services.yml"
  -f "${seatflow_root}/docker-compose.monitoring.yml"
  -f "${seatflow_root}/docker-compose.prod.yml"
  --env-file "${runtime_file}")

required_services=(
  postgres redis kafka eureka-server api-gateway
  user-service seat-map-service event-service reservation-service
  payment-service ticket-service realtime-service notification-service frontend
  otel-collector prometheus kafka-exporter grafana tempo loki promtail
)

deadline=$((SECONDS + 600))
while (( SECONDS < deadline )); do
  ready=true
  for service in "${required_services[@]}"; do
    container_id=$("${compose[@]}" ps -q "${service}")
    if [[ -z ${container_id} ]]; then
      ready=false
      break
    fi
    status=$(docker inspect --format '{{.State.Status}}' "${container_id}")
    health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "${container_id}")
    restarts=$(docker inspect --format '{{.RestartCount}}' "${container_id}")
    if [[ ${status} != running || (${health} != healthy && ${health} != none) || ${restarts} -gt 5 ]]; then
      ready=false
      break
    fi
  done
  if [[ ${ready} == true ]]; then
    break
  fi
  sleep 10
done

if [[ ${ready:-false} != true ]]; then
  "${compose[@]}" ps >&2
  echo "Compose release did not become healthy before the timeout" >&2
  exit 1
fi

gateway_health=$("${compose[@]}" exec -T api-gateway \
  wget -qO- http://127.0.0.1:8080/actuator/health/readiness)
printf '%s' "${gateway_health}" | jq -e '.status == "UP"' >/dev/null

eureka_apps=$("${compose[@]}" exec -T eureka-server \
  wget -qO- --header='Accept: application/json' http://127.0.0.1:8761/eureka/apps)
printf '%s' "${eureka_apps}" | jq -e \
  '(.applications.application // []) | length >= 9' >/dev/null

frontend_status=$("${compose[@]}" exec -T frontend \
  wget -qSO /dev/null http://127.0.0.1:8080/health 2>&1 | awk '/HTTP\// {print $2}' | tail -1)
[[ ${frontend_status} == 200 ]]

websocket_status=$("${compose[@]}" exec -T frontend sh -c \
  "wget -qSO /dev/null --header='Connection: Upgrade' --header='Upgrade: websocket' http://127.0.0.1:8080/ws 2>&1 | awk '/HTTP\\// {print \\$2}' | tail -1" || true)
if [[ ! ${websocket_status} =~ ^(101|200|400|401|403|426)$ ]]; then
  echo "WebSocket edge probe returned unexpected status: ${websocket_status:-none}" >&2
  exit 1
fi

if [[ -n ${smoke_url} ]]; then
  if [[ ! ${smoke_url} =~ ^https:// ]]; then
    echo "Public smoke URL must use HTTPS" >&2
    exit 1
  fi
  public_status=$(curl -fsS -o /dev/null -w '%{http_code}' "${smoke_url}")
  if [[ ! ${public_status} =~ ^2[0-9]{2}$ ]]; then
    echo "Public smoke probe failed with HTTP ${public_status}" >&2
    exit 1
  fi
fi

echo "SeatFlow release verification passed"

