# SeatFlow GCP Compute Engine Deployment & Verification Runbook

This runbook is the authoritative, step-by-step operational guide for provisioning, configuring, verifying, securing, and maintaining the single-VM **SeatFlow** production deployment on Google Cloud Platform (GCP).

Related ADR: [ADR-008: Compute Engine Single-VM Portfolio Deployment](../../.ai/decisions/ADR-008-compute-engine-single-vm-portfolio-deployment.md)  
Related Tasks: [TASK-P10-008](../../.ai/tasks/completed/phase-10-devops-observability/008-compute-engine-gcp-production-terraform.md), [TASK-P10-007](../../.ai/tasks/phase-10-devops-observability/007-github-actions-compute-engine-cd-wif.md)

---

## 1. Architecture Overview

SeatFlow runs the complete portfolio microservices topology on a single, high-capacity Compute Engine VM managed via Terraform and orchestrated by Docker Compose v2:

| Component | Target Specification | Notes |
|---|---|---|
| **GCP Project** | `seatflow-production-507311` | Dedicated portfolio production project |
| **Region / Zone** | `europe-west1` / `europe-west1-c` | Belgium region; `-c` selected after repeated `pd-balanced` capacity failures in `-b` |
| **Compute Instance** | `e2-highmem-2` | 2 vCPU, 16 GiB RAM |
| **Boot Storage** | 80 GB `pd-balanced` | Protected from auto-deletion |
| **Domain & Static IP** | `seat-flow.me` | Static external IPv4 attached to VM |
| **Public Ports** | TCP `80` (HTTP), TCP `443` (HTTPS) | Nginx edge reverse proxy |
| **Admin Access** | TCP `22` via Google Cloud IAP only | No public unrestricted SSH |
| **Container Stack** | 10 Java Services, PG 16, Kafka, Redis, Telemetry | All internal ports isolated from WAN |

---

## 2. Prerequisites & Initial GCP Setup

Ensure you have the Google Cloud CLI (`gcloud`) and Terraform (`>= 1.9.0`) installed locally.

```bash
# 1. Authenticate with Google Cloud
gcloud auth login
gcloud auth application-default login

# 2. Set default project
gcloud config set project seatflow-production-507311

# 3. Verify active credentials
gcloud auth list
```

---

## 3. Remote State Backend Initialization

SeatFlow uses a versioned Google Cloud Storage (GCS) bucket for Terraform remote state.

### 3.1 Create GCS Bucket
```bash
gcloud storage buckets create gs://seatflow-prod-adelin-tfstate \
  --project=seatflow-production-507311 \
  --location=europe-west1 \
  --uniform-bucket-level-access
```

### 3.2 Enable Object Versioning
```bash
gcloud storage buckets update gs://seatflow-prod-adelin-tfstate --versioning
```

### 3.3 Activate Backend Configuration
```bash
cp infra/terraform/environments/production/backend.tf.example \
   infra/terraform/environments/production/backend.tf
```

---

## 4. Terraform Execution Sequence

### 4.1 Initialize Terraform
```bash
cd infra/terraform/environments/production

terraform init
```

### 4.2 Validate & Format Check
```bash
terraform fmt -check -recursive
terraform validate
```

### 4.3 Plan Infrastructure Changes
```bash
terraform plan -out=tfplan
```
Inspect the plan output to ensure only the approved resources are being provisioned:
- 1 Custom VPC & Subnet
- 1 Static External IP
- 2 Firewall Rules (`seatflow-allow-web`, `seatflow-allow-iap-ssh`)
- 1 Compute Engine instance (`seatflow-production`, `e2-highmem-2`)
- 1 Artifact Registry repository (`seatflow`)
- 2 Service Accounts (`seatflow-vm-runtime`, `seatflow-github-deploy`)
- 1 Workload Identity Pool + Provider for `AdelinV2/SeatFlow`
- 8 Secret Manager containers

### 4.4 Apply Infrastructure
```bash
terraform apply tfplan
```

### 4.5 Capture Terraform Outputs
```bash
terraform output
```
Note the `static_ip`, `wif_provider_name`, and `deploy_service_account_email` outputs.

---

## 5. DNS Configuration

Point your apex domain `seat-flow.me` and wildcard/subdomains to the static IP provisioned by Terraform:

1. Retrieve the static IP:
   ```bash
   gcloud compute addresses describe seatflow-production-static-ip \
     --region=europe-west1 \
     --project=seatflow-production-507311 \
     --format="value(address)"
   ```
2. Log into your DNS Registrar (e.g. Cloudflare, Namecheap, Google Domains) and create the following records:

| Record Type | Host | Value | TTL |
|---|---|---|---|
| `A` | `@` (`seat-flow.me`) | `<STATIC_IP>` | Auto / 300s |
| `A` | `www` (`www.seat-flow.me`) | `<STATIC_IP>` | Auto / 300s |
| `A` | `api` (`api.seat-flow.me`) | `<STATIC_IP>` | Auto / 300s |

Verify DNS propagation:
```bash
dig +short seat-flow.me
```

---

## 6. Populating Secret Values in GCP Secret Manager

Terraform provisions empty Secret Manager containers to maintain zero secrets in IaC state. Populate the real secret values using `gcloud`:

```bash
PROJECT_ID="seatflow-production-507311"

# 1. PostgreSQL Admin Superuser Password (maps to POSTGRES_PASSWORD)
echo -n "CHANGE_ME_POSTGRES_ADMIN_PASSWORD" | gcloud secrets versions add postgres-admin-password --data-file=- --project=${PROJECT_ID}

# 2. PostgreSQL Application Role Password (maps to DB_PASSWORD for 'seatflow' user)
echo -n "CHANGE_ME_POSTGRES_APP_PASSWORD" | gcloud secrets versions add postgres-app-password --data-file=- --project=${PROJECT_ID}

# 3. Redis Auth Password (maps to REDIS_PASSWORD)
echo -n "CHANGE_ME_REDIS_PASSWORD" | gcloud secrets versions add redis-password --data-file=- --project=${PROJECT_ID}

# 4. Stripe API Secret Key (maps to STRIPE_API_KEY)
echo -n "sk_live_..." | gcloud secrets versions add stripe-api-key --data-file=- --project=${PROJECT_ID}

# 5. Stripe Webhook Signing Secret (maps to STRIPE_WEBHOOK_SECRET)
echo -n "whsec_..." | gcloud secrets versions add stripe-webhook-secret --data-file=- --project=${PROJECT_ID}

# 6. Resend Email API Key (maps to RESEND_API_KEY)
echo -n "re_..." | gcloud secrets versions add resend-api-key --data-file=- --project=${PROJECT_ID}

# 7. Grafana Admin Password (maps to GRAFANA_ADMIN_PASSWORD)
echo -n "CHANGE_ME_GRAFANA_ADMIN_PASSWORD" | gcloud secrets versions add grafana-admin-password --data-file=- --project=${PROJECT_ID}

# 8. Prometheus Actuator Scrape JWT Token (maps to PROMETHEUS_SCRAPE_TOKEN / /run/secrets/prometheus-scrape-token)
echo -n "CHANGE_ME_JWT_METRICS_READ_TOKEN" | gcloud secrets versions add prometheus-scrape-token --data-file=- --project=${PROJECT_ID}

# 9. Dedicated monitoring identity credentials (used only by the VM token refresher)
echo -n "prometheus@seat-flow.me" | gcloud secrets versions add prometheus-identity-email --data-file=- --project=${PROJECT_ID}
echo -n "CHANGE_ME_MONITORING_IDENTITY_PASSWORD" | gcloud secrets versions add prometheus-identity-password --data-file=- --project=${PROJECT_ID}
```

---

## 7. Administrative VM Access (IAP / OS Login)

Because unrestricted port 22 is disabled in the firewall, all SSH connections must use Google Cloud Identity-Aware Proxy (IAP):

```bash
# Connect to the VM securely through IAP tunnel
gcloud compute ssh seatflow-production \
  --zone=europe-west1-c \
  --project=seatflow-production-507311 \
  --tunnel-through-iap
```

Verify host health inside the VM:
```bash
# Check Docker daemon and compose
docker --version
docker compose version

# Check SeatFlow directory structure
ls -la /opt/seatflow /run/seatflow

# Check Ops Agent status
systemctl status google-cloud-ops-agent
```

---

## 8. SSL / TLS Certificate Setup (Let's Encrypt / Certbot)

For HTTPS on `seat-flow.me`, provision Let's Encrypt SSL certificates inside the VM:

```bash
# Connect to the VM via IAP
gcloud compute ssh seatflow-production --zone=europe-west1-c --project=seatflow-production-507311 --tunnel-through-iap

# Install Certbot
sudo apt-get update
sudo apt-get install -y certbot

# Stop Nginx edge temporarily if running, or use webroot mode
sudo certbot certonly --standalone \
  -d seat-flow.me \
  -d www.seat-flow.me \
  -d api.seat-flow.me \
  --agree-tos \
  --email adelin.v2@gmail.com \
  --non-interactive

# Verify certificates
sudo ls -la /etc/letsencrypt/live/seat-flow.me/
```

Set up automatic renewal cron:
```bash
sudo crontab -l 2>/dev/null; echo "0 3 * * * certbot renew --quiet && docker compose -f /opt/seatflow/docker-compose.yml exec frontend nginx -s reload" | sudo crontab -
```

---

## 9. Automated Infrastructure Verification

Run the verification script to validate that all GCP resources, IAM bindings, WIF configuration, and firewall rules conform to architectural standards:

```bash
./infra/scripts/verify-gcp-foundation.sh production
```

Expected output:
```text
==============================================================================
 SeatFlow GCP Foundation Verification
 Environment: production
 Project ID:  seatflow-production-507311
 Region/Zone: europe-west1 / europe-west1-c
==============================================================================
==> 1. Verifying Required GCP APIs...
  [PASS] Service API enabled: compute.googleapis.com
  ...
==> 2. Verifying Compute Engine VM...
  [PASS] VM Status: RUNNING
  [PASS] VM Machine Type: e2-highmem-2 (2 vCPU, 16 GiB RAM)
  [PASS] Boot Disk Size: 80 GB
  [PASS] VM Deletion Protection: ENABLED
  [PASS] External Static NAT IP attached: 34.xx.xx.xx
==> 3. Verifying Firewall Rules...
  [PASS] Firewall rule 'seatflow-allow-web' exists (Ports 80/443 public)
  [PASS] Firewall rule 'seatflow-allow-iap-ssh' restricted strictly to GCP IAP (35.235.240.0/20)
  [PASS] Guardrail Check: Unrestricted public SSH (0.0.0.0/0:22) is ABSENT
==> 4. Verifying Artifact Registry...
  [PASS] Artifact Registry repository 'seatflow' exists (Format: DOCKER, Location: europe-west1)
==> 5. Verifying Workload Identity Federation (WIF)...
  [PASS] WIF Pool 'seatflow-github-pool' is ACTIVE
  [PASS] WIF Provider 'seatflow-github-provider' exists
==> 6. Verifying Secret Manager Containers...
  [PASS] Secret container exists: postgres-admin-password
  ...
==> 7. Guardrail Check: Verifying Superseded Managed Services are ABSENT...
  [PASS] Guardrail Check: Cloud Run services absent (0 active)
  [PASS] Guardrail Check: Cloud SQL instances absent (0 active)
  [PASS] Guardrail Check: Memorystore Redis instances absent (0 active)
  [PASS] Guardrail Check: GKE clusters absent (0 active)
==============================================================================
 [SUCCESS] All GCP foundation infrastructure checks passed successfully!
==============================================================================
```

---

## 10. Database Backup & Disaster Recovery Procedures

### 10.1 PostgreSQL Backup (`pg_dump`)
Run regular database dumps inside the VM:
```bash
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
docker exec -t seatflow-postgres pg_dumpall -U postgres | gzip > /opt/seatflow/backups/seatflow_backup_${TIMESTAMP}.sql.gz
```

To sync backups to GCS:
```bash
gcloud storage cp /opt/seatflow/backups/seatflow_backup_${TIMESTAMP}.sql.gz \
  gs://seatflow-prod-adelin-tfstate/backups/
```

### 10.2 PostgreSQL Restore
```bash
BACKUP_FILE="/opt/seatflow/backups/seatflow_backup_XXXXXX.sql.gz"
gunzip -c "${BACKUP_FILE}" | docker exec -i seatflow-postgres psql -U postgres
```

### 10.3 Compute Engine Persistent Disk Snapshots
Create on-demand disk snapshots prior to major version upgrades:
```bash
gcloud compute disks snapshot seatflow-production \
  --zone=europe-west1-c \
  --snapshot-names="seatflow-manual-snapshot-$(date +%Y%m%d)" \
  --project=seatflow-production-507311
```

---

## 11. Troubleshooting & Diagnostics

### 11.1 Container Status & Logs
```bash
# Connect to VM
gcloud compute ssh seatflow-production --zone=europe-west1-c --tunnel-through-iap

# Check all running containers
docker ps -a

# View logs for a specific service
docker logs -f seatflow-api-gateway
docker logs -f seatflow-reservation-service
docker logs -f seatflow-postgres
```

### 11.2 Check Host Resource Consumption
```bash
# CPU / Memory
htop
free -h

# Disk Space
df -h /
docker system df
```

### 11.3 Systemd Service Status
```bash
# Check status of seatflow.service
systemctl status seatflow.service

# Restart all containers via systemd
sudo systemctl restart seatflow.service
```

---

## 12. Safety Warnings & State Protection

> [!CAUTION]
> **NEVER RUN `terraform destroy` ON PRODUCTION!**  
> Running `terraform destroy` will terminate the Compute Engine VM, purge network attachments, and drop persistent disk state.
>
> The VM resource has `deletion_protection = true` enabled in Terraform to prevent accidental destruction from the console and CLI.

---

## 13. GitHub Actions Deployment Configuration

Create a protected GitHub Environment named `production`, require an approving reviewer, and define these non-secret variables. The `develop` artifact-validation workflow reads the same identifiers as repository variables; it does not create a second runtime stack.

| Variable | Value |
|---|---|
| `GCP_PROJECT_ID` | `seatflow-production-507311` |
| `GCP_REGION` | `europe-west1` |
| `GCP_WIF_PROVIDER` | `projects/515874727347/locations/global/workloadIdentityPools/seatflow-github-pool/providers/seatflow-github-provider` |
| `GCP_DEPLOY_SERVICE_ACCOUNT` | `seatflow-github-deploy@seatflow-production-507311.iam.gserviceaccount.com` |
| `ARTIFACT_REGISTRY_REPOSITORY` | `seatflow` |
| `GCP_VM_NAME` | `seatflow-production` |
| `GCP_VM_ZONE` | `europe-west1-c` |
| `GATEWAY_HEALTH_URL` | `https://seat-flow.me/api/events?size=1` |

No GCP service-account key or runtime application secret belongs in GitHub. GitHub obtains a short-lived GCP credential through WIF, while the VM reads only its approved runtime secrets through the attached service account.

### 13.1 Release Sequence

1. Merge an approved release into `main` or push a semantic `vX.Y.Z` tag.
2. The protected production workflow builds and pushes every deployable image using the full commit SHA.
3. GitHub transfers only Compose, deployment scripts, and non-secret release metadata through IAP/OS Login.
4. The VM reads Secret Manager through the metadata identity, validates Compose, pulls images, runs each database migration stage once, replaces services, and verifies the stack.
5. Successful non-secret metadata is stored in `/opt/seatflow/deployment/current.env`; the previous release is retained in `previous.env`.

The seven database-backed application containers have normal startup Flyway disabled in the production override. `run-production-migrations.sh` owns the ordered, release-idempotent migration stage and creates a root-only marker after all migrations succeed.

### 13.2 Automatic and Manual Rollback

Failed verification automatically restores the previous immutable image SHA and re-runs health checks. It never executes Flyway `clean`, `undo`, `repair`, or any schema reversal.

For a manual image rollback:

```bash
gcloud compute ssh seatflow-production \
  --zone=europe-west1-c \
  --project=seatflow-production-507311 \
  --tunnel-through-iap \
  --command="sudo /opt/seatflow/infra/scripts/rollback-compose-release.sh /opt/seatflow https://seat-flow.me/api/events?size=1"
```

If no `previous.env` exists, stop and investigate rather than inventing a mutable image selector.

### 13.3 Runtime Secret Readiness

All eight Secret Manager containers must have an enabled version before the first release. `prometheus-scrape-token` must contain an IdP-issued JWT with only `metrics.read`; do not use a random token. A Stripe webhook signing secret must come from the exact test-mode endpoint `https://seat-flow.me/api/payments/webhook`, not from a local Stripe CLI listener.

