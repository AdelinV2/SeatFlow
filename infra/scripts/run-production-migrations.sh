#!/usr/bin/env bash
set -euo pipefail

if [[ ${EUID} -ne 0 ]]; then
  echo "run-production-migrations.sh must run as root" >&2
  exit 1
fi

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <seatflow-root> <immutable-image-tag>" >&2
  exit 2
fi

seatflow_root=$1
image_tag=$2
runtime_file=/run/seatflow/runtime.env
marker_dir=${seatflow_root}/deployment
marker_file=${marker_dir}/migrations-${image_tag}.done

if [[ ! ${image_tag} =~ ^[0-9a-f]{40}$ ]]; then
  echo "Image tag must be a full immutable Git SHA" >&2
  exit 2
fi

if [[ -f ${marker_file} ]]; then
  echo "Migrations already completed for ${image_tag}"
  exit 0
fi

compose=(docker compose
  -f "${seatflow_root}/docker-compose.yml"
  -f "${seatflow_root}/docker-compose.services.yml"
  -f "${seatflow_root}/docker-compose.monitoring.yml"
  -f "${seatflow_root}/docker-compose.prod.yml"
  --env-file "${runtime_file}")

install -d -o root -g root -m 0700 "${marker_dir}"
"${compose[@]}" up -d postgres redis kafka eureka-server

wait_for_healthy() {
  local service=$1
  local deadline=$((SECONDS + 300))
  local container_id status health
  while (( SECONDS < deadline )); do
    container_id=$("${compose[@]}" ps -q "${service}")
    if [[ -n ${container_id} ]]; then
      status=$(docker inspect --format '{{.State.Status}}' "${container_id}")
      health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "${container_id}")
      if [[ ${status} == running && (${health} == healthy || ${health} == none) ]]; then
        return 0
      fi
    fi
    sleep 5
  done
  echo "Timed out waiting for ${service}" >&2
  return 1
}

for dependency in postgres redis kafka eureka-server; do
  wait_for_healthy "${dependency}"
done

migration_services=(
  user-service
  seat-map-service
  event-service
  reservation-service
  payment-service
  ticket-service
  notification-service
)

for service in "${migration_services[@]}"; do
  container_name="seatflow-migrate-${service}-${image_tag:0:12}"
  docker rm -f "${container_name}" >/dev/null 2>&1 || true
  container_id=$("${compose[@]}" run -d --no-deps --name "${container_name}" \
    -e SPRING_FLYWAY_ENABLED=true \
    -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
    -e SPRING_KAFKA_LISTENER_AUTO_STARTUP=false \
    "${service}")

  deadline=$((SECONDS + 240))
  migrated=false
  while (( SECONDS < deadline )); do
    if docker logs "${container_id}" 2>&1 | grep -Eq \
      'Successfully applied|Schema .* is up to date|Started .*Application'; then
      migrated=true
      break
    fi
    state=$(docker inspect --format '{{.State.Status}}' "${container_id}")
    if [[ ${state} == exited || ${state} == dead ]]; then
      break
    fi
    sleep 3
  done

  if [[ ${migrated} != true ]]; then
    echo "Migration stage failed for ${service}" >&2
    docker logs --tail 80 "${container_id}" >&2 || true
    docker rm -f "${container_id}" >/dev/null 2>&1 || true
    exit 1
  fi

  docker rm -f "${container_id}" >/dev/null 2>&1 || true
  echo "Migration stage completed for ${service}"
done

printf 'image_tag=%s\ncompleted_at=%s\n' \
  "${image_tag}" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "${marker_file}"
chmod 0600 "${marker_file}"
echo "All production migrations completed exactly once for ${image_tag}"

