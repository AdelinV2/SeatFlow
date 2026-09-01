#!/usr/bin/env bash
set -euo pipefail

if [[ ${EUID} -ne 0 ]]; then
  echo "render-runtime-env.sh must run as root" >&2
  exit 1
fi

if [[ $# -ne 4 ]]; then
  echo "Usage: $0 <project-id> <region> <artifact-repository> <immutable-image-tag>" >&2
  exit 2
fi

project_id=$1
region=$2
artifact_repository=$3
image_tag=$4
runtime_dir=/run/seatflow
runtime_file=${runtime_dir}/runtime.env
prometheus_token_file=${runtime_dir}/prometheus-scrape-token
metadata_url=http://metadata.google.internal/computeMetadata/v1

if [[ ! ${image_tag} =~ ^[0-9a-f]{40}$ ]]; then
  echo "Image tag must be a full immutable Git SHA" >&2
  exit 2
fi

umask 077
install -d -o root -g root -m 0700 "${runtime_dir}"
temp_runtime=$(mktemp "${runtime_dir}/runtime.env.XXXXXX")
temp_prometheus=$(mktemp "${runtime_dir}/prometheus-scrape-token.XXXXXX")
trap 'rm -f "${temp_runtime}" "${temp_prometheus}"' EXIT

access_token=$(curl -fsS -H 'Metadata-Flavor: Google' \
  "${metadata_url}/instance/service-accounts/default/token" | jq -er '.access_token')

fetch_secret() {
  local secret_name=$1
  curl -fsS \
    -H "Authorization: Bearer ${access_token}" \
    "https://secretmanager.googleapis.com/v1/projects/${project_id}/secrets/${secret_name}/versions/latest:access" \
    | jq -er '.payload.data' \
    | base64 --decode
}

append_secret_env() {
  local environment_name=$1
  local secret_name=$2
  local secret_value
  secret_value=$(fetch_secret "${secret_name}")
  if [[ -z ${secret_value} || ${secret_value} == *$'\n'* || ${secret_value} == *$'\r'* ]]; then
    echo "Secret ${secret_name} is empty or is not a single-line value" >&2
    exit 1
  fi
  printf '%s=%s\n' "${environment_name}" "${secret_value}" >> "${temp_runtime}"
  unset secret_value
}

cat > "${temp_runtime}" <<EOF
COMPOSE_PROJECT_NAME=seatflow
POSTGRES_USER=postgres
DB_USERNAME=seatflow
REDIS_USERNAME=
GRAFANA_ADMIN_USER=admin
AR_BASE=${region}-docker.pkg.dev/${project_id}/${artifact_repository}
SEATFLOW_IMAGE_TAG=${image_tag}
PROMETHEUS_SCRAPE_TOKEN_FILE=${prometheus_token_file}
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=https://txyyirobwnomhxygbacq.supabase.co/auth/v1
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI=https://txyyirobwnomhxygbacq.supabase.co/auth/v1/.well-known/jwks.json
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES=authenticated
EOF

append_secret_env POSTGRES_PASSWORD postgres-admin-password
append_secret_env DB_PASSWORD postgres-app-password
append_secret_env REDIS_PASSWORD redis-password
append_secret_env STRIPE_API_KEY stripe-api-key
append_secret_env STRIPE_WEBHOOK_SECRET stripe-webhook-secret
append_secret_env RESEND_API_KEY resend-api-key
append_secret_env GRAFANA_ADMIN_PASSWORD grafana-admin-password

fetch_secret prometheus-scrape-token > "${temp_prometheus}"
if [[ ! -s ${temp_prometheus} ]]; then
  echo "Secret prometheus-scrape-token is empty" >&2
  exit 1
fi

install -o root -g root -m 0600 "${temp_runtime}" "${runtime_file}"
install -o root -g root -m 0600 "${temp_prometheus}" "${prometheus_token_file}"
unset access_token

echo "Rendered root-owned SeatFlow runtime files"

