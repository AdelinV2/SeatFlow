#!/usr/bin/env bash
set -euo pipefail

if [[ ${EUID} -ne 0 ]]; then
  echo "deploy-compose-release.sh must run as root" >&2
  exit 1
fi

if [[ $# -ne 7 ]]; then
  echo "Usage: $0 <release-root> <image-sha> <release-version> <project-id> <region> <artifact-repository> <https-smoke-url>" >&2
  exit 2
fi

release_root=$1
image_tag=$2
release_version=$3
project_id=$4
region=$5
artifact_repository=$6
smoke_url=$7
seatflow_root=/opt/seatflow
deployment_dir=${seatflow_root}/deployment
runtime_file=/run/seatflow/runtime.env
metadata_url=http://metadata.google.internal/computeMetadata/v1

if [[ ! ${image_tag} =~ ^[0-9a-f]{40}$ ]]; then
  echo "Image tag must be a full immutable Git SHA" >&2
  exit 2
fi
if [[ ! ${release_version} =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "Release version contains unsupported characters" >&2
  exit 2
fi
if [[ ! ${smoke_url} =~ ^https:// ]]; then
  echo "Production smoke URL must use HTTPS" >&2
  exit 2
fi

exec 9>"/run/seatflow/deploy.lock"
flock -n 9 || { echo "Another SeatFlow deployment is already running" >&2; exit 1; }

install -d -o root -g root -m 0750 "${seatflow_root}" "${seatflow_root}/infra/scripts"
install -d -o root -g root -m 0700 "${deployment_dir}"
cp -a "${release_root}/docker/." "${seatflow_root}/"
cp -a "${release_root}/infra/scripts/." "${seatflow_root}/infra/scripts/"
chmod 0750 "${seatflow_root}/infra/scripts/"*.sh

if [[ -d ${release_root}/infra/systemd ]]; then
  install -o root -g root -m 0644 \
    "${release_root}/infra/systemd/seatflow-prometheus-token-refresh.service" \
    /etc/systemd/system/seatflow-prometheus-token-refresh.service
  install -o root -g root -m 0644 \
    "${release_root}/infra/systemd/seatflow-prometheus-token-refresh.timer" \
    /etc/systemd/system/seatflow-prometheus-token-refresh.timer
  systemctl daemon-reload
  systemctl enable --now seatflow-prometheus-token-refresh.timer
fi

if [[ -f ${deployment_dir}/current.env ]]; then
  install -o root -g root -m 0600 \
    "${deployment_dir}/current.env" "${deployment_dir}/previous.env"
fi

"${seatflow_root}/infra/scripts/render-runtime-env.sh" \
  "${project_id}" "${region}" "${artifact_repository}" "${image_tag}"

registry_token=$(curl -fsS -H 'Metadata-Flavor: Google' \
  "${metadata_url}/instance/service-accounts/default/token" | jq -er '.access_token')
printf '%s' "${registry_token}" | docker login \
  -u oauth2accesstoken --password-stdin "https://${region}-docker.pkg.dev" >/dev/null
unset registry_token

compose=(docker compose
  -f "${seatflow_root}/docker-compose.yml"
  -f "${seatflow_root}/docker-compose.services.yml"
  -f "${seatflow_root}/docker-compose.monitoring.yml"
  -f "${seatflow_root}/docker-compose.prod.yml"
  --env-file "${runtime_file}")

rollout() {
  "${compose[@]}" config --quiet
  "${compose[@]}" pull
  "${seatflow_root}/infra/scripts/run-production-migrations.sh" \
    "${seatflow_root}" "${image_tag}"
  "${compose[@]}" up -d --remove-orphans
  "${seatflow_root}/infra/scripts/verify-compose-release.sh" \
    "${seatflow_root}" "${smoke_url}"
}

if ! rollout; then
  echo "Release verification failed" >&2
  if [[ -f ${deployment_dir}/previous.env ]]; then
    "${seatflow_root}/infra/scripts/rollback-compose-release.sh" \
      "${seatflow_root}" "${smoke_url}" || true
  fi
  exit 1
fi

temp_metadata=$(mktemp "${deployment_dir}/current.env.XXXXXX")
trap 'rm -f "${temp_metadata}"' EXIT
cat > "${temp_metadata}" <<EOF
SEATFLOW_IMAGE_TAG=${image_tag}
SEATFLOW_RELEASE_VERSION=${release_version}
SEATFLOW_RELEASE_TIMESTAMP=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
install -o root -g root -m 0600 "${temp_metadata}" "${deployment_dir}/current.env"
echo "SeatFlow release ${release_version} deployed successfully"

