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
started_file=${marker_dir}/migrations-${image_tag}.started
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

# This marker is deliberately durable. Once production migration work begins,
# deploy-compose-release.sh must not automatically boot an older image set over
# a possibly-forward schema. A failed migration is a forward-fix event.
printf 'image_tag=%s\nstarted_at=%s\n' \
  "${image_tag}" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "${started_file}"
chmod 0600 "${started_file}"

# A single 2-vCPU production host cannot cold-start the application stack and
# migration JVMs at the same time reliably. Stop application containers before
# migrating; persistent dependencies and named volumes remain untouched. This
# also freezes legacy writers while the P12 session backfill gate runs.
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
  analytics-service
  frontend
)
"${compose[@]}" stop "${application_services[@]}" >/dev/null 2>&1 || true

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
      if [[ ${status} == exited || ${status} == dead || ${health} == unhealthy ]]; then
        echo "${service} failed while waiting for migration dependencies (status=${status}, health=${health})" >&2
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

declare -A migration_databases=(
  [user-service]=seatflow_user
  [seat-map-service]=seatflow_seatmap
  [event-service]=seatflow_event
  [reservation-service]=seatflow_reservation
  [payment-service]=seatflow_payment
  [ticket-service]=seatflow_ticket
  [notification-service]=seatflow_notification
  [analytics-service]=seatflow_analytics
)

psql_scalar() {
  local db=$1
  local sql=$2
  docker exec seatflow-postgres bash -lc \
    'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1" -Atc "$2"' \
    -- "${db}" "${sql}"
}

flyway_version() {
  local db=$1
  local has_history
  has_history=$(psql_scalar "${db}" "SELECT CASE WHEN to_regclass('public.flyway_schema_history') IS NULL THEN 'no' ELSE 'yes' END;")
  if [[ ${has_history} != yes ]]; then
    printf '0\n'
    return 0
  fi
  psql_scalar "${db}" "SELECT COALESCE(MAX(CASE WHEN version ~ '^[0-9]+$' THEN version::integer END), 0) FROM flyway_schema_history WHERE success = true;"
}

verify_flyway_history() {
  local db=$1
  local result
  result=$(psql_scalar "${db}" "SELECT CASE WHEN to_regclass('public.flyway_schema_history') IS NOT NULL AND EXISTS (SELECT 1 FROM flyway_schema_history) AND NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE success = false) THEN 'ok' ELSE 'bad' END;")
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
    analytics-service)
      sql="SELECT CASE WHEN to_regclass('public.processed_events') IS NOT NULL AND to_regclass('public.analytics_session_facts') IS NOT NULL AND to_regclass('public.daily_operational_metrics') IS NOT NULL AND to_regclass('public.daily_revenue_metrics') IS NOT NULL THEN 'ok' ELSE 'bad' END;"
      ;;
    *)
      echo "No migration schema assertion defined for ${service}" >&2
      return 1
      ;;
  esac

  local result
  result=$(psql_scalar "${db}" "${sql}")
  [[ ${result} == ok ]]
}

run_migration_stage() {
  local service=$1
  local target=${2:-}
  local full_verify=${3:-true}
  local db=${migration_databases[${service}]}
  local suffix=${target:-latest}
  local container_name="seatflow-migrate-${service}-${image_tag:0:12}-${suffix}"
  local -a flyway_target_args=()
  local container_id deadline migrated state current

  if [[ -n ${target} ]]; then
    flyway_target_args=(-e "SPRING_FLYWAY_TARGET=${target}")
  fi

  docker rm -f "${container_name}" >/dev/null 2>&1 || true
  container_id=$("${compose[@]}" run -d --no-deps --name "${container_name}" \
    -e SPRING_FLYWAY_ENABLED=true \
    -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
    -e SPRING_KAFKA_LISTENER_AUTO_STARTUP=false \
    -e EUREKA_CLIENT_ENABLED=false \
    -e SPRING_CLOUD_DISCOVERY_ENABLED=false \
    -e OTEL_SDK_DISABLED=true \
    "${flyway_target_args[@]}" \
    "${service}")

  deadline=$((SECONDS + 600))
  migrated=false
  while (( SECONDS < deadline )); do
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

  if [[ ${migrated} != true ]] || ! verify_flyway_history "${db}"; then
    echo "Migration stage failed verification for ${service}${target:+ target ${target}}" >&2
    docker logs --tail 120 "${container_id}" >&2 || true
    docker rm -f "${container_id}" >/dev/null 2>&1 || true
    return 1
  fi

  if [[ -n ${target} ]]; then
    current=$(flyway_version "${db}")
    if (( current < target )); then
      echo "${service} stopped below requested Flyway target ${target} (current=${current})" >&2
      docker logs --tail 120 "${container_id}" >&2 || true
      docker rm -f "${container_id}" >/dev/null 2>&1 || true
      return 1
    fi
  fi

  if [[ ${full_verify} == true ]] && ! verify_required_schema "${service}" "${db}"; then
    echo "Required schema verification failed for ${service}" >&2
    docker logs --tail 120 "${container_id}" >&2 || true
    docker rm -f "${container_id}" >/dev/null 2>&1 || true
    return 1
  fi

  docker rm -f "${container_id}" >/dev/null 2>&1 || true
  echo "Migration stage completed and verified for ${service}${target:+ through V${target}}"
}

p12_backfill_session_inventory() {
  local event_sessions_exists reservation_column_exists holds_column_exists
  local null_parent_count orphan_count event_id session_rows
  local -a sessions=()

  event_sessions_exists=$(psql_scalar seatflow_event "SELECT CASE WHEN to_regclass('public.event_sessions') IS NULL THEN 'no' ELSE 'yes' END;")
  reservation_column_exists=$(psql_scalar seatflow_reservation "SELECT CASE WHEN EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='reservations' AND column_name='event_session_id') THEN 'yes' ELSE 'no' END;")
  holds_column_exists=$(psql_scalar seatflow_reservation "SELECT CASE WHEN EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='seat_holds' AND column_name='event_session_id') THEN 'yes' ELSE 'no' END;")

  if [[ ${event_sessions_exists} != yes || ${reservation_column_exists} != yes || ${holds_column_exists} != yes ]]; then
    echo "P12 staged backfill prerequisites are missing after additive migration targets" >&2
    return 1
  fi

  null_parent_count=$(psql_scalar seatflow_reservation "SELECT (SELECT COUNT(*) FROM reservations WHERE event_session_id IS NULL AND event_id IS NULL) + (SELECT COUNT(*) FROM seat_holds WHERE event_session_id IS NULL AND event_id IS NULL);")
  if (( null_parent_count > 0 )); then
    echo "P12 staged backfill found ${null_parent_count} orphan row(s) without a legacy event_id; refusing to guess" >&2
    return 1
  fi

  orphan_count=$(psql_scalar seatflow_reservation "SELECT (SELECT COUNT(*) FROM reservations WHERE event_session_id IS NULL) + (SELECT COUNT(*) FROM seat_holds WHERE event_session_id IS NULL);")
  if (( orphan_count == 0 )); then
    echo "P12 staged backfill gate already clean: zero NULL event_session_id rows"
    return 0
  fi

  echo "P12 staged backfill: resolving ${orphan_count} legacy reservation/hold row(s)"
  while IFS= read -r event_id; do
    [[ -n ${event_id} ]] || continue
    session_rows=$(psql_scalar seatflow_event "SELECT id::text FROM event_sessions WHERE event_id='${event_id}'::uuid AND legacy_backfill = TRUE ORDER BY id;")
    mapfile -t sessions <<< "${session_rows}"
    if [[ ${#sessions[@]} -ne 1 || -z ${sessions[0]} ]]; then
      echo "Legacy event ${event_id} has ${#sessions[@]} canonical legacy_backfill sessions; refusing to guess" >&2
      return 1
    fi

    docker exec seatflow-postgres bash -lc \
      'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d seatflow_reservation -v event_id="$1" -v session_id="$2" <<'"'"'SQL'"'"'
BEGIN;
UPDATE reservations
SET event_session_id = :'"'"'session_id'"'"'::uuid
WHERE event_id = :'"'"'event_id'"'"'::uuid
  AND event_session_id IS NULL;
UPDATE seat_holds
SET event_session_id = :'"'"'session_id'"'"'::uuid
WHERE event_id = :'"'"'event_id'"'"'::uuid
  AND event_session_id IS NULL;
COMMIT;
SQL' \
      -- "${event_id}" "${sessions[0]}"
  done < <(psql_scalar seatflow_reservation "SELECT event_id::text FROM reservations WHERE event_session_id IS NULL UNION SELECT event_id::text FROM seat_holds WHERE event_session_id IS NULL ORDER BY 1;")

  orphan_count=$(psql_scalar seatflow_reservation "SELECT (SELECT COUNT(*) FROM reservations WHERE event_session_id IS NULL) + (SELECT COUNT(*) FROM seat_holds WHERE event_session_id IS NULL);")
  if (( orphan_count != 0 )); then
    echo "P12 staged backfill incomplete: ${orphan_count} NULL event_session_id row(s) remain" >&2
    return 1
  fi
  echo "P12 staged backfill verified: zero NULL event_session_id rows"
}

# P12 expand -> backfill -> contract hotfix. The original deployment ran the
# event-service migration to V4 (DROP events.event_date) before reservation V8
# had proved that every legacy booking row had a session identity. Stage the two
# services at their additive boundaries first, backfill deterministically from
# the unique legacy_backfill session, then enforce reservation V8/V9 before the
# event-service contract migration. Already-upgraded environments skip this path.
event_version=$(flyway_version seatflow_event)
reservation_version=$(flyway_version seatflow_reservation)
if (( event_version < 4 || reservation_version < 8 )); then
  if (( event_version < 3 )); then
    run_migration_stage event-service 3 false
  fi
  if (( reservation_version < 7 )); then
    run_migration_stage reservation-service 7 false
  fi
  p12_backfill_session_inventory
  if (( reservation_version < 8 )); then
    run_migration_stage reservation-service "" true
  fi
  if (( event_version < 4 )); then
    run_migration_stage event-service "" true
  fi
fi

# Normal full migration order keeps booking/inventory enforcement ahead of event
# contract changes. Future destructive cross-service changes must follow the same
# expand/backfill/contract pattern instead of relying on automatic rollback.
migration_services=(
  user-service
  seat-map-service
  reservation-service
  event-service
  payment-service
  ticket-service
  notification-service
  analytics-service
)

for service in "${migration_services[@]}"; do
  run_migration_stage "${service}" "" true
done

printf 'image_tag=%s\ncompleted_at=%s\n' \
  "${image_tag}" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "${marker_file}"
chmod 0600 "${marker_file}"
echo "All production migrations completed and verified for ${image_tag}"
