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
  -f "${seatflow_root}/docker-compose.prod-health.yml"
  --env-file "${runtime_file}")

install -d -o root -g root -m 0700 "${marker_dir}"
"${compose[@]}" config --quiet

# A single 2-vCPU production host cannot cold-start the old application stack and
# seven migration JVMs at the same time reliably. Stop application containers
# before migrating; persistent dependencies and named volumes remain untouched.
application_services=(
  api-gateway
  user-service
  seat-map-service
  event-service
  reservation-service
  payment-service
  ticket-service
  realtime-service
  notification-service
  frontend
)
"${compose[@]}" stop "${application_services[@]}" >/dev/null 2>&1 || true

# Keep the normal production dependency contract for migration containers. The
# production-only override provides the lightweight Kafka healthcheck and the
# measured memory headroom required on this VM.
"${compose[@]}" up -d postgres redis kafka eureka-server

wait_for_healthy() {
  local service=$1
  local deadline=$((SECONDS + 600))
  local container_id status health
  while (( SECONDS < deadline )); do
    container_id=$("${compose[@]}" ps -q "${service}")
    if [[ -n ${container_id} ]]; then
      status=$(docker inspect --format '{{.State.Status}}' "${container_id}")
      health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "${container_id}")
      if [[ ${status} == running && (${health} == healthy || ${health} == none) ]]; then
        return 0
      fi
      if [[ ${status} == exited || ${status} == dead ]]; then
        echo "${service} exited while waiting for migration dependencies" >&2
        return 1
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

declare -A migration_databases=(
  [user-service]=seatflow_user
  [seat-map-service]=seatflow_seatmap
  [event-service]=seatflow_event
  [reservation-service]=seatflow_reservation
  [payment-service]=seatflow_payment
  [ticket-service]=seatflow_ticket
  [notification-service]=seatflow_notification
)

verify_flyway_history() {
  local db=$1
  local result
  result=$(docker exec seatflow-postgres bash -lc \
    'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1" -Atc "SELECT CASE WHEN to_regclass('"'"'public.flyway_schema_history'"'"') IS NOT NULL AND EXISTS (SELECT 1 FROM flyway_schema_history) AND NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE success = false) THEN '"'"'ok'"'"' ELSE '"'"'bad'"'"' END;"' \
    -- "${db}")
  [[ ${result} == ok ]]
}

verify_required_schema() {
  local service=$1
  local db=$2
  local sql
  case ${service} in
    user-service|seat-map-service|reservation-service|payment-service|ticket-service)
      sql="SELECT CASE WHEN to_regclass('public.outbox_events') IS NOT NULL THEN 'ok' ELSE 'bad' END;"
      ;;
    event-service)
      sql="SELECT CASE WHEN to_regclass('public.event_pricing_tiers') IS NOT NULL AND to_regclass('public.outbox_events') IS NOT NULL THEN 'ok' ELSE 'bad' END;"
      ;;
    notification-service)
      sql="SELECT CASE WHEN to_regclass('public.notification_logs') IS NOT NULL THEN 'ok' ELSE 'bad' END;"
      ;;
    *)
      echo "No migration schema assertion defined for ${service}" >&2
      return 1
      ;;
  esac

  local result
  result=$(docker exec seatflow-postgres bash -lc \
    'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1" -Atc "$2"' \
    -- "${db}" "${sql}")
  [[ ${result} == ok ]]
}

for service in "${migration_services[@]}"; do
  container_name="seatflow-migrate-${service}-${image_tag:0:12}"
  docker rm -f "${container_name}" >/dev/null 2>&1 || true

  container_id=$("${compose[@]}" run -d --no-deps --name "${container_name}" \
    -e SPRING_FLYWAY_ENABLED=true \
    -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
    -e SPRING_KAFKA_LISTENER_AUTO_STARTUP=false \
    -e EUREKA_CLIENT_ENABLED=false \
    -e SPRING_CLOUD_DISCOVERY_ENABLED=false \
    -e OTEL_SDK_DISABLED=true \
    "${service}")

  deadline=$((SECONDS + 600))
  migrated=false
  while (( SECONDS < deadline )); do
    # A generic "Started ...Application" is intentionally NOT accepted. The
    # migration gate must observe Flyway itself reporting applied/up-to-date.
    if docker logs "${container_id}" 2>&1 | grep -Eq \
      'Successfully applied [0-9]+ migration|Successfully applied [0-9]+ migrations|Schema .* is up to date'; then
      migrated=true
      break
    fi

    state=$(docker inspect --format '{{.State.Status}}' "${container_id}")
    if [[ ${state} == exited || ${state} == dead ]]; then
      break
    fi
    sleep 3
  done

  db=${migration_databases[${service}]}
  if [[ ${migrated} != true ]] || ! verify_flyway_history "${db}" || ! verify_required_schema "${service}" "${db}"; then
    echo "Migration stage failed verification for ${service}" >&2
    docker logs --tail 100 "${container_id}" >&2 || true
    docker rm -f "${container_id}" >/dev/null 2>&1 || true
    exit 1
  fi

  docker rm -f "${container_id}" >/dev/null 2>&1 || true
  echo "Migration stage completed and verified for ${service}"
done

printf 'image_tag=%s\ncompleted_at=%s\n' \
  "${image_tag}" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "${marker_file}"
chmod 0600 "${marker_file}"
echo "All production migrations completed and verified for ${image_tag}"
