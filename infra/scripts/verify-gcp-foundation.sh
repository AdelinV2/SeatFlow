#!/usr/bin/env bash
# ==============================================================================
# SeatFlow GCP Production Foundation Verification Script
# Validates the Terraform-provisioned infrastructure on Google Cloud Platform.
# INVARIANT: Zero secret values are printed during execution.
# ==============================================================================
set -euo pipefail

ENVIRONMENT="${1:-production}"
PROJECT_ID="${GCP_PROJECT_ID:-seatflow-production-507311}"
REGION="${GCP_REGION:-europe-west1}"
ZONE="${GCP_ZONE:-europe-west1-b}"
VM_NAME="${GCP_VM_NAME:-seatflow-production}"
REPO_NAME="${ARTIFACT_REGISTRY_REPO:-seatflow}"
WIF_POOL="${GCP_WIF_POOL:-seatflow-github-pool}"
WIF_PROVIDER="${GCP_WIF_PROVIDER:-seatflow-github-provider}"
GITHUB_REPO="${GITHUB_REPO:-AdelinV2/SeatFlow}"

echo "=============================================================================="
echo " SeatFlow GCP Foundation Verification"
echo " Environment: ${ENVIRONMENT}"
echo " Project ID:  ${PROJECT_ID}"
echo " Region/Zone: ${REGION} / ${ZONE}"
echo "=============================================================================="

FAILED_CHECKS=0

pass() {
  echo -e "  [PASS] $1"
}

fail() {
  echo -e "  [FAIL] $1" >&2
  FAILED_CHECKS=$((FAILED_CHECKS + 1))
}

# ------------------------------------------------------------------------------
# 1. Verify GCP APIs
# ------------------------------------------------------------------------------
echo "==> 1. Verifying Required GCP APIs..."
REQUIRED_SERVICES=(
  "compute.googleapis.com"
  "artifactregistry.googleapis.com"
  "secretmanager.googleapis.com"
  "iam.googleapis.com"
  "iamcredentials.googleapis.com"
  "sts.googleapis.com"
  "iap.googleapis.com"
  "logging.googleapis.com"
  "monitoring.googleapis.com"
)

ENABLED_SERVICES=$(gcloud services list --enabled --project="${PROJECT_ID}" --format="value(config.name)" 2>/dev/null || true)

for svc in "${REQUIRED_SERVICES[@]}"; do
  if echo "${ENABLED_SERVICES}" | grep -q "^${svc}$"; then
    pass "Service API enabled: ${svc}"
  else
    fail "Service API NOT enabled: ${svc}"
  fi
done

# ------------------------------------------------------------------------------
# 2. Verify Compute Engine VM & Disk
# ------------------------------------------------------------------------------
echo "==> 2. Verifying Compute Engine VM..."
VM_INFO=$(gcloud compute instances describe "${VM_NAME}" --zone="${ZONE}" --project="${PROJECT_ID}" --format="json" 2>/dev/null || true)

if [ -z "${VM_INFO}" ]; then
  fail "VM '${VM_NAME}' not found in zone '${ZONE}'"
else
  VM_STATUS=$(echo "${VM_INFO}" | jq -r '.status')
  VM_MACHINE_TYPE=$(echo "${VM_INFO}" | jq -r '.machineType' | awk -F'/' '{print $NF}')
  DISK_SIZE_GB=$(echo "${VM_INFO}" | jq -r '.disks[0].diskSizeGb')
  DELETION_PROTECTED=$(echo "${VM_INFO}" | jq -r '.deletionProtection')
  NAT_IP=$(echo "${VM_INFO}" | jq -r '.networkInterfaces[0].accessConfigs[0].natIP // empty')

  if [ "${VM_STATUS}" == "RUNNING" ]; then
    pass "VM Status: RUNNING"
  else
    fail "VM Status is '${VM_STATUS}' (expected RUNNING)"
  fi

  if [ "${VM_MACHINE_TYPE}" == "e2-highmem-2" ]; then
    pass "VM Machine Type: e2-highmem-2 (2 vCPU, 16 GiB RAM)"
  else
    fail "VM Machine Type is '${VM_MACHINE_TYPE}' (expected e2-highmem-2)"
  fi

  if [ "${DISK_SIZE_GB}" == "80" ]; then
    pass "Boot Disk Size: 80 GB"
  else
    fail "Boot Disk Size is '${DISK_SIZE_GB}' GB (expected 80 GB)"
  fi

  if [ "${DELETION_PROTECTED}" == "true" ]; then
    pass "VM Deletion Protection: ENABLED"
  else
    fail "VM Deletion Protection is '${DELETION_PROTECTED}' (expected true)"
  fi

  if [ -n "${NAT_IP}" ]; then
    pass "External Static NAT IP attached: ${NAT_IP}"
  else
    fail "External Static NAT IP NOT attached to VM"
  fi
fi

# ------------------------------------------------------------------------------
# 3. Verify Firewall Rules
# ------------------------------------------------------------------------------
echo "==> 3. Verifying Firewall Rules..."
FIREWALLS=$(gcloud compute firewall-rules list --project="${PROJECT_ID}" --format="json" 2>/dev/null || true)

# Web rule (80, 443 open to 0.0.0.0/0)
WEB_RULE=$(echo "${FIREWALLS}" | jq -r '.[] | select(.name == "seatflow-allow-web")')
if [ -n "${WEB_RULE}" ]; then
  pass "Firewall rule 'seatflow-allow-web' exists (Ports 80/443 public)"
else
  fail "Firewall rule 'seatflow-allow-web' NOT found"
fi

# IAP SSH rule (port 22 restricted to 35.235.240.0/20)
IAP_RULE=$(echo "${FIREWALLS}" | jq -r '.[] | select(.name == "seatflow-allow-iap-ssh")')
if [ -n "${IAP_RULE}" ]; then
  IAP_RANGES=$(echo "${IAP_RULE}" | jq -r '.sourceRanges[]?')
  if echo "${IAP_RANGES}" | grep -q "35.235.240.0/20"; then
    pass "Firewall rule 'seatflow-allow-iap-ssh' restricted strictly to GCP IAP (35.235.240.0/20)"
  else
    fail "Firewall rule 'seatflow-allow-iap-ssh' source ranges incorrect: ${IAP_RANGES}"
  fi
else
  fail "Firewall rule 'seatflow-allow-iap-ssh' NOT found"
fi

# Guardrail: Check for unrestricted public SSH rule (0.0.0.0/0 port 22)
PUBLIC_SSH=$(echo "${FIREWALLS}" | jq -r '.[] | select(.direction=="INGRESS" and (.sourceRanges[]? == "0.0.0.0/0") and (.allowed[]? | select(.ports[]? == "22")))')
if [ -z "${PUBLIC_SSH}" ]; then
  pass "Guardrail Check: Unrestricted public SSH (0.0.0.0/0:22) is ABSENT"
else
  fail "Guardrail Check: Detected unrestricted public SSH rule! Remove immediately!"
fi

# ------------------------------------------------------------------------------
# 4. Verify Artifact Registry
# ------------------------------------------------------------------------------
echo "==> 4. Verifying Artifact Registry..."
AR_REPO=$(gcloud artifacts repositories describe "${REPO_NAME}" --location="${REGION}" --project="${PROJECT_ID}" --format="json" 2>/dev/null || true)
if [ -n "${AR_REPO}" ]; then
  AR_FORMAT=$(echo "${AR_REPO}" | jq -r '.format')
  if [ "${AR_FORMAT}" == "DOCKER" ]; then
    pass "Artifact Registry repository '${REPO_NAME}' exists (Format: DOCKER, Location: ${REGION})"
  else
    fail "Artifact Registry repository format is '${AR_FORMAT}' (expected DOCKER)"
  fi
else
  fail "Artifact Registry repository '${REPO_NAME}' NOT found in '${REGION}'"
fi

# ------------------------------------------------------------------------------
# 5. Verify Workload Identity Federation (WIF)
# ------------------------------------------------------------------------------
echo "==> 5. Verifying Workload Identity Federation (WIF)..."
WIF_POOL_INFO=$(gcloud iam workload-identity-pools describe "${WIF_POOL}" --location="global" --project="${PROJECT_ID}" --format="json" 2>/dev/null || true)
if [ -n "${WIF_POOL_INFO}" ]; then
  WIF_STATE=$(echo "${WIF_POOL_INFO}" | jq -r '.state')
  if [ "${WIF_STATE}" == "ACTIVE" ]; then
    pass "WIF Pool '${WIF_POOL}' is ACTIVE"
  else
    fail "WIF Pool '${WIF_POOL}' state is '${WIF_STATE}' (expected ACTIVE)"
  fi
else
  fail "WIF Pool '${WIF_POOL}' NOT found"
fi

WIF_PROVIDER_INFO=$(gcloud iam workload-identity-pools providers describe "${WIF_PROVIDER}" \
  --workload-identity-pool="${WIF_POOL}" \
  --location="global" \
  --project="${PROJECT_ID}" \
  --format="json" 2>/dev/null || true)

if [ -n "${WIF_PROVIDER_INFO}" ]; then
  pass "WIF Provider '${WIF_PROVIDER}' exists"
else
  fail "WIF Provider '${WIF_PROVIDER}' NOT found in pool '${WIF_POOL}'"
fi

# ------------------------------------------------------------------------------
# 6. Verify Service Accounts & IAM Least-Privilege Guardrails
# ------------------------------------------------------------------------------
echo "==> 6. Verifying IAM Identities & Least Privilege..."
RUNTIME_SA="seatflow-vm-runtime@${PROJECT_ID}.iam.gserviceaccount.com"
DEPLOY_SA="seatflow-github-deploy@${PROJECT_ID}.iam.gserviceaccount.com"

# Check SAs exist
if gcloud iam service-accounts describe "${RUNTIME_SA}" --project="${PROJECT_ID}" &>/dev/null; then
  pass "Runtime Service Account exists: ${RUNTIME_SA}"
else
  fail "Runtime Service Account NOT found: ${RUNTIME_SA}"
fi

if gcloud iam service-accounts describe "${DEPLOY_SA}" --project="${PROJECT_ID}" &>/dev/null; then
  pass "Deploy Service Account exists: ${DEPLOY_SA}"
else
  fail "Deploy Service Account NOT found: ${DEPLOY_SA}"
fi

# Guardrail: Ensure neither SA has project Owner or Editor, and Runtime SA has no project-wide secretAccessor
PROJECT_IAM=$(gcloud projects get-iam-policy "${PROJECT_ID}" --format="json" 2>/dev/null || true)

RUNTIME_OVERPRIV=$(echo "${PROJECT_IAM}" | jq -r --arg sa "serviceAccount:${RUNTIME_SA}" '.bindings[] | select((.role == "roles/owner" or .role == "roles/editor" or .role == "roles/secretmanager.secretAccessor") and (.members[]? == $sa))')
if [ -z "${RUNTIME_OVERPRIV}" ]; then
  pass "Guardrail Check: Runtime SA has NO Owner/Editor/project-wide secretAccessor privileges"
else
  fail "Guardrail Check: Runtime SA possesses excessive project-level privileges: ${RUNTIME_OVERPRIV}"
fi

DEPLOY_OVERPRIV=$(echo "${PROJECT_IAM}" | jq -r --arg sa "serviceAccount:${DEPLOY_SA}" '.bindings[] | select((.role == "roles/owner" or .role == "roles/editor") and (.members[]? == $sa))')
if [ -z "${DEPLOY_OVERPRIV}" ]; then
  pass "Guardrail Check: Deploy SA has NO Owner/Editor privileges"
else
  fail "Guardrail Check: Deploy SA possesses excessive Owner/Editor role!"
fi

# ------------------------------------------------------------------------------
# 7. Verify Secret Manager Containers
# ------------------------------------------------------------------------------
echo "==> 7. Verifying Secret Manager Containers (Empty Containers Check)..."
EXPECTED_SECRETS=(
  "postgres-admin-password"
  "postgres-app-password"
  "redis-password"
  "stripe-api-key"
  "stripe-webhook-secret"
  "resend-api-key"
  "grafana-admin-password"
  "prometheus-scrape-token"
)

EXISTING_SECRETS=$(gcloud secrets list --project="${PROJECT_ID}" --format="value(name)" 2>/dev/null || true)

for secret in "${EXPECTED_SECRETS[@]}"; do
  if echo "${EXISTING_SECRETS}" | grep -q "${secret}$"; then
    pass "Secret container exists: ${secret}"
  else
    fail "Secret container MISSING: ${secret}"
  fi
done

# ------------------------------------------------------------------------------
# 8. Guardrail Check: Superseded Managed Services
# ------------------------------------------------------------------------------
echo "==> 8. Guardrail Check: Verifying Superseded Managed Services are ABSENT..."

# Cloud Run
CLOUD_RUN_SVCS=$(gcloud run services list --project="${PROJECT_ID}" --format="value(name)" 2>/dev/null || true)
if [ -z "${CLOUD_RUN_SVCS}" ]; then
  pass "Guardrail Check: Cloud Run services absent (0 active)"
else
  fail "Guardrail Check: Found active Cloud Run services: ${CLOUD_RUN_SVCS}"
fi

# Cloud SQL
SQL_INSTANCES=$(gcloud sql instances list --project="${PROJECT_ID}" --format="value(name)" 2>/dev/null || true)
if [ -z "${SQL_INSTANCES}" ]; then
  pass "Guardrail Check: Cloud SQL instances absent (0 active)"
else
  fail "Guardrail Check: Found active Cloud SQL instances: ${SQL_INSTANCES}"
fi

# Memorystore Redis
REDIS_INSTANCES=$(gcloud redis instances list --region="${REGION}" --project="${PROJECT_ID}" --format="value(name)" 2>/dev/null || true)
if [ -z "${REDIS_INSTANCES}" ]; then
  pass "Guardrail Check: Memorystore Redis instances absent (0 active)"
else
  fail "Guardrail Check: Found active Memorystore instances: ${REDIS_INSTANCES}"
fi

# GKE Clusters
GKE_CLUSTERS=$(gcloud container clusters list --project="${PROJECT_ID}" --format="value(name)" 2>/dev/null || true)
if [ -z "${GKE_CLUSTERS}" ]; then
  pass "Guardrail Check: GKE clusters absent (0 active)"
else
  fail "Guardrail Check: Found active GKE clusters: ${GKE_CLUSTERS}"
fi

# ------------------------------------------------------------------------------
# Summary
# ------------------------------------------------------------------------------
echo "=============================================================================="
if [ "${FAILED_CHECKS}" -eq 0 ]; then
  echo " [SUCCESS] All GCP foundation infrastructure checks passed successfully!"
  echo "=============================================================================="
  exit 0
else
  echo " [FAILED] ${FAILED_CHECKS} infrastructure check(s) failed!"
  echo "=============================================================================="
  exit 1
fi
