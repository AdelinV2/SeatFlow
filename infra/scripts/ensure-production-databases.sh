#!/usr/bin/env bash
set -euo pipefail

if [[ ${EUID} -ne 0 ]]; then
  echo "ensure-production-databases.sh must run as root" >&2
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
"${compose[@]}" up -d postgres

wait_for_postgres() {
  local deadline=$((SECONDS + 180))
  local container_id status health

  while (( SECONDS < deadline )); do
    container_id=$("${compose[@]}" ps -q postgres)
    if [[ -n ${container_id} ]]; then
      status=$(docker inspect --format '{{.State.Status}}' "${container_id}")
      health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "${container_id}")

      if [[ ${status} == running && ${health} == healthy ]]; then
        return 0
      fi

      if [[ ${status} == exited || ${status} == dead || ${health} == unhealthy ]]; then
        echo "postgres failed before database provisioning (status=${status}, health=${health})" >&2
        docker logs --tail 120 "${container_id}" >&2 || true
        return 1
      fi
    fi
    sleep 3
  done

  echo "Timed out waiting for postgres before database provisioning" >&2
  return 1
}

wait_for_postgres

db_owner=$(docker exec seatflow-postgres bash -lc 'printf "%s" "${DB_USERNAME:-}"')
if [[ -z ${db_owner} ]]; then
  echo "PostgreSQL container is missing DB_USERNAME; cannot provision service databases" >&2
  exit 1
fi
if [[ ! ${db_owner} =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
  echo "DB_USERNAME contains unsupported characters for production database provisioning" >&2
  exit 1
fi

role_exists=$(docker exec seatflow-postgres bash -lc \
  'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -Atc "SELECT 1 FROM pg_roles WHERE rolname = '\''$DB_USERNAME'\'';"')
if [[ ${role_exists} != 1 ]]; then
  echo "Application database role ${db_owner} does not exist; refusing to create databases with an unknown owner" >&2
  exit 1
fi

service_databases=(
  seatflow_user
  seatflow_seatmap
  seatflow_event
  seatflow_reservation
  seatflow_payment
  seatflow_ticket
  seatflow_notification
  seatflow_analytics
)

for db in "${service_databases[@]}"; do
  if [[ ! ${db} =~ ^[a-z0-9_]+$ ]]; then
    echo "Unsafe database name in provisioning list: ${db}" >&2
    exit 1
  fi

  exists=$(docker exec seatflow-postgres bash -lc \
    'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -Atc "SELECT 1 FROM pg_database WHERE datname = '\''$1'\'';"' \
    -- "${db}")

  if [[ ${exists} == 1 ]]; then
    continue
  fi

  echo "Provisioning missing production database ${db}"
  docker exec seatflow-postgres bash -lc \
    'createdb -U "$POSTGRES_USER" -O "$DB_USERNAME" "$1"' \
    -- "${db}"

  exists=$(docker exec seatflow-postgres bash -lc \
    'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -Atc "SELECT 1 FROM pg_database WHERE datname = '\''$1'\'';"' \
    -- "${db}")
  if [[ ${exists} != 1 ]]; then
    echo "Database provisioning verification failed for ${db}" >&2
    exit 1
  fi
done

echo "Production service databases are provisioned"
