#!/usr/bin/env bash
set -euo pipefail

if [[ ${EUID} -ne 0 ]]; then
  echo "rollback-compose-release.sh must run as root" >&2
  exit 1
fi

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "Usage: $0 <seatflow-root> [https-smoke-url]" >&2
  exit 2
fi

seatflow_root=$1
smoke_url=${2:-}
deployment_dir=${seatflow_root}/deployment
previous_file=${deployment_dir}/previous.env
current_file=${deployment_dir}/current.env
runtime_file=/run/seatflow/runtime.env

if [[ ! -f ${previous_file} ]]; then
  echo "No previous release metadata exists; automatic rollback is unavailable" >&2
  exit 1
fi

runtime_tag=$(awk -F= '$1 == "SEATFLOW_IMAGE_TAG" { print $2; exit }' "${runtime_file}")
if [[ ${runtime_tag} =~ ^[0-9a-f]{40}$ && -f ${deployment_dir}/migrations-${runtime_tag}.started ]]; then
  echo "Rollback refused: migration work started for runtime image ${runtime_tag}." >&2
  echo "The database may no longer be compatible with the previous image set; use a forward fix." >&2
  exit 42
fi

# Metadata is written only by deploy-compose-release.sh and contains no secrets.
# shellcheck disable=SC1090
source "${previous_file}"
if [[ ! ${SEATFLOW_IMAGE_TAG:-} =~ ^[0-9a-f]{40}$ ]]; then
  echo "Previous release metadata does not contain an immutable image tag" >&2
  exit 1
fi

temp_runtime=$(mktemp /run/seatflow/runtime.env.rollback.XXXXXX)
trap 'rm -f "${temp_runtime}"' EXIT
awk -v tag="${SEATFLOW_IMAGE_TAG}" \
  'BEGIN { replaced=0 } /^SEATFLOW_IMAGE_TAG=/ { print "SEATFLOW_IMAGE_TAG=" tag; replaced=1; next } { print } END { if (!replaced) exit 3 }' \
  "${runtime_file}" > "${temp_runtime}"
install -o root -g root -m 0600 "${temp_runtime}" "${runtime_file}"

compose=(docker compose
  -f "${seatflow_root}/docker-compose.yml"
  -f "${seatflow_root}/docker-compose.services.yml"
  -f "${seatflow_root}/docker-compose.monitoring.yml"
  -f "${seatflow_root}/docker-compose.prod.yml"
  -f "${seatflow_root}/docker-compose.prod-health.yml"
  --env-file "${runtime_file}")
"${compose[@]}" config --quiet
"${compose[@]}" pull

"${seatflow_root}/infra/scripts/start-compose-release.sh" "${seatflow_root}"
"${seatflow_root}/infra/scripts/verify-compose-release.sh" "${seatflow_root}" "${smoke_url}"
install -o root -g root -m 0600 "${previous_file}" "${current_file}"
echo "Restored previous immutable application image set only after proving no migration stage started"
