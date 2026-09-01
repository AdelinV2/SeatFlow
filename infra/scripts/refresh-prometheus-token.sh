#!/usr/bin/env bash
set -euo pipefail

if [[ ${EUID} -ne 0 ]]; then
  echo "refresh-prometheus-token.sh must run as root" >&2
  exit 1
fi

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <project-id> <token-file>" >&2
  exit 2
fi

project_id=$1
token_file=$2
metadata_url=http://metadata.google.internal/computeMetadata/v1
supabase_auth_url='https://txyyirobwnomhxygbacq.supabase.co/auth/v1/token?grant_type=password'
supabase_publishable_key='sb_publishable_PYk5C_1_YArT3EV24KjdDA_60lYr5ff'
runtime_dir=$(dirname "${token_file}")

install -d -o root -g root -m 0700 "${runtime_dir}"
umask 077
temp_token=$(mktemp "${runtime_dir}/prometheus-scrape-token.XXXXXX")
temp_body=$(mktemp "${runtime_dir}/prometheus-auth-request.XXXXXX")
temp_response=$(mktemp "${runtime_dir}/prometheus-auth-response.XXXXXX")
trap 'rm -f "${temp_token}" "${temp_body}" "${temp_response}"' EXIT

metadata_response=''
if ! metadata_response=$(curl -fsS -H 'Metadata-Flavor: Google' \
  "${metadata_url}/instance/service-accounts/default/token"); then
  echo "Failed to obtain the VM runtime identity token from Compute Engine metadata" >&2
  exit 1
fi
if ! access_token=$(printf '%s' "${metadata_response}" | jq -er '.access_token'); then
  echo "Compute Engine metadata response did not contain an access token" >&2
  exit 1
fi
unset metadata_response

fetch_secret() {
  local secret_name=$1
  local response encoded

  if ! response=$(curl -fsS \
    -H "Authorization: Bearer ${access_token}" \
    "https://secretmanager.googleapis.com/v1/projects/${project_id}/secrets/${secret_name}/versions/latest:access"); then
    echo "Failed to access Secret Manager secret: ${secret_name}" >&2
    return 1
  fi
  if ! encoded=$(printf '%s' "${response}" | jq -er '.payload.data'); then
    echo "Secret Manager response did not contain payload data for: ${secret_name}" >&2
    return 1
  fi
  if ! printf '%s' "${encoded}" | base64 --decode; then
    echo "Secret Manager payload was not valid base64 for: ${secret_name}" >&2
    return 1
  fi
}

if ! email=$(fetch_secret prometheus-identity-email); then
  exit 1
fi
if ! password=$(fetch_secret prometheus-identity-password); then
  exit 1
fi
if [[ -z ${email} || -z ${password} || ${email} == *$'\n'* || ${password} == *$'\n'* ]]; then
  echo "Monitoring identity credentials are empty or malformed" >&2
  exit 1
fi

jq -n --arg email "${email}" --arg password "${password}" \
  '{email:$email,password:$password}' > "${temp_body}"
unset email password access_token

auth_status=$(curl -sS -o "${temp_response}" -w '%{http_code}' -X POST "${supabase_auth_url}" \
  -H "apikey: ${supabase_publishable_key}" \
  -H 'Content-Type: application/json' \
  --data-binary "@${temp_body}") || {
  echo "Failed to reach Supabase Auth for the monitoring identity" >&2
  exit 1
}
if [[ ${auth_status} != 200 ]]; then
  echo "Supabase monitoring identity authentication failed with HTTP ${auth_status}" >&2
  exit 1
fi
if ! token=$(jq -er '.access_token' "${temp_response}"); then
  echo "Supabase Auth response did not contain an access token" >&2
  exit 1
fi

payload=${token#*.}
payload=${payload%%.*}
payload=$(printf '%s' "${payload}" | tr '_-' '/+')
while (( ${#payload} % 4 != 0 )); do payload+="="; done
if ! scope=$(printf '%s' "${payload}" | base64 --decode 2>/dev/null | jq -er '.scope'); then
  echo "Supabase monitoring token did not contain a scope claim" >&2
  exit 1
fi
if [[ ${scope} != "metrics.read" ]]; then
  echo "Supabase monitoring token did not contain the required metrics.read scope" >&2
  exit 1
fi
printf '%s' "${token}" > "${temp_token}"
unset token payload scope

if [[ ! -s ${temp_token} ]]; then
  echo "Supabase did not return a monitoring access token" >&2
  exit 1
fi

install -o root -g root -m 0600 "${temp_token}" "${token_file}"
echo "Refreshed the root-owned Prometheus metrics token"
