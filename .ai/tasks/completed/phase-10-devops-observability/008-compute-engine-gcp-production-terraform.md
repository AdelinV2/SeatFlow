# TASK-P10-008: Provision Compute Engine Production Infrastructure with Terraform

## 1. Task Metadata
- **Task ID:** `TASK-P10-008`
- **Git Branch:** `feat/p10-008-compute-engine-gcp-production-terraform`
- **Target Module:** `infra/terraform/`, GCP runtime configuration, and cloud deployment runbook
- **Phase:** `Phase 10 - DevOps & Observability`
- **Related Specs:** `AGENTS.md`, `.ai/architecture/08-observability-and-deployment.md`, `.ai/tasks/phase-10-devops-observability/007-github-actions-compute-engine-cd-wif.md`
- **Related ADRs:** `.ai/decisions/ADR-008-compute-engine-single-vm-portfolio-deployment.md`, `.ai/decisions/ADR-007-terraform-for-gcp-runtime-infrastructure.md`
- **Status:** `COMPLETED`

---

## 2. Objective

Use Terraform to provision the cost-conscious GCP production foundation for SeatFlow: one Compute Engine VM running the complete production Docker Compose stack, plus Artifact Registry, IAM/WIF, Secret Manager, networking, persistent storage, static IP, and optional Cloud Logging/Monitoring integration.

This task explicitly replaces the previous Cloud Run / Cloud SQL / Memorystore / managed-Kafka topology.

### Critical Invariants
- [x] Default runtime host is one `e2-highmem-2` VM (2 vCPU, 16 GiB RAM), configurable by Terraform variable but not silently oversized.
- [x] All application/data/observability runtime containers execute through production Docker Compose on that VM.
- [x] Terraform provisions Artifact Registry, VM/networking/storage/IAM/WIF/Secret Manager infrastructure.
- [x] Real secret values never enter Terraform state or repository files.
- [x] Public ingress is restricted to the application edge ports required for HTTP/HTTPS; internal runtime ports are never Internet-exposed.
- [x] Prefer OS Login / IAP or another authenticated GCP administration path instead of unrestricted public SSH.
- [x] Persistent VM storage is protected against accidental destruction where practical.
- [x] No Cloud Run, Cloud SQL, Memorystore, managed Kafka, GKE, Cloud Armor, Cloud CDN, or dedicated HTTPS load balancer is provisioned for MVP.
- [x] No second always-on staging VM is required.
- [x] Terraform state is remote/versioned and excluded from source control.

---

## 3. Exact File Inventory

- `[NEW]` `infra/terraform/versions.tf` and `infra/terraform/providers.tf`.
- `[NEW]` `infra/terraform/README.md`.
- `[NEW]` `infra/terraform/modules/seatflow-vm/main.tf`.
- `[NEW]` `infra/terraform/modules/seatflow-vm/variables.tf`.
- `[NEW]` `infra/terraform/modules/seatflow-vm/outputs.tf`.
- `[NEW]` `infra/terraform/modules/seatflow-vm/network.tf`.
- `[NEW]` `infra/terraform/modules/seatflow-vm/compute.tf`.
- `[NEW]` `infra/terraform/modules/seatflow-vm/artifact-registry.tf`.
- `[NEW]` `infra/terraform/modules/seatflow-vm/secrets.tf`.
- `[NEW]` `infra/terraform/modules/seatflow-vm/iam.tf`.
- `[NEW]` `infra/terraform/modules/seatflow-vm/wif.tf`.
- `[NEW]` `infra/terraform/modules/seatflow-vm/monitoring.tf` when required for Ops Agent/logging integration.
- `[NEW]` `infra/terraform/environments/production/main.tf`, `variables.tf`, `outputs.tf`, `terraform.tfvars.example`, `backend.tf.example`.
- `[NEW]` `infra/terraform/environments/staging-validation/main.tf` or equivalent validation-only root if useful; it must not create a second permanent runtime by default.
- `[NEW]` `infra/runbooks/gcp-compute-engine-deployment-and-verification.md`.
- `[NEW]` `infra/scripts/bootstrap-seatflow-vm.sh` — idempotent first-boot/runtime prerequisite setup.
- `[NEW]` `infra/scripts/verify-gcp-foundation.sh`.
- `[MODIFY]` `.gitignore` — exclude Terraform state/cache/backend/tfvars and generated runtime secret material.
- `[MODIFY]` `.github/workflows/ci-pr-check.yml` — Terraform fmt/validate/policy checks coordinated with P10-007.

---

## 4. Terraform & State Contract

Use supported pinned provider ranges appropriate at implementation time. Baseline structure:

```hcl
terraform {
  required_version = ">= 1.9.0, < 2.0.0"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
  }
  backend "gcs" {}
}

provider "google" {
  project = var.project_id
  region  = var.region
  zone    = var.zone
}
```

Remote state uses a versioned GCS bucket/bootstrap procedure outside repository state. State files, backend credentials, and real `.tfvars` are never committed.

The production root should be intentionally small and understandable. Do not recreate enterprise multi-environment complexity merely because Terraform supports it.

---

## 5. Compute Engine Contract

### 5.1 VM

Default variables:

```hcl
variable "machine_type" {
  type    = string
  default = "e2-highmem-2"
}

variable "boot_disk_size_gb" {
  type    = number
  default = 80
}
```

The module creates one Linux VM named similarly to `seatflow-production`.

Requirements:

- 2-vCPU / 16-GiB default machine target;
- supported Ubuntu LTS or approved equivalent image;
- Docker Engine + Docker Compose v2 installed idempotently by startup/bootstrap script;
- automatic restart enabled;
- deletion protection where practical for the portfolio production VM;
- shielded VM/security options enabled when compatible;
- OS Login enabled;
- no embedded SSH private key in Terraform or GitHub;
- no application secrets in startup-script metadata;
- labels such as `app=seatflow`, `environment=production`.

### 5.2 Disk / Data

Use persistent disk storage sufficient for:

- PostgreSQL data;
- Kafka log data;
- Docker images/layers;
- Prometheus/Tempo short-retention data;
- application/container logs.

A single adequately sized persistent disk is acceptable for the portfolio MVP. The runbook must document disk usage monitoring and backup/restore procedures. Do not provision enterprise multi-disk/RAID architecture without a measured need.

Protect persistent resources from accidental Terraform destruction using lifecycle/deletion controls where practical.

---

## 6. Networking Contract

Provision:

- VPC/subnet or an explicitly selected minimal network topology;
- static external IPv4 for the public portfolio endpoint;
- firewall rules allowing only required public web traffic;
- authenticated administrative path.

Public application ingress:

```text
TCP 80  -> Nginx redirect/certificate bootstrap as required
TCP 443 -> Nginx HTTPS/WSS
```

Do not publicly expose:

```text
22 unrestricted SSH
5432 PostgreSQL
6379 Redis
9092 Kafka
8761 Eureka
8080-8088 backend services
9090 Prometheus
3000 Grafana
3100 Loki
3200 Tempo
4317/4318 OTel
```

If SSH over public IP is temporarily required during initial troubleshooting, the rule must be narrowly source-restricted and documented as temporary. Preferred steady-state administration uses OS Login plus IAP or an equivalent authenticated GCP path.

A separate global load balancer/Cloud Armor/CDN is outside MVP scope.

---

## 7. Artifact Registry Contract

Create one Docker Artifact Registry repository, for example:

```text
seatflow
```

P10-007 pushes one independently versioned image per deployable service/frontend.

VM runtime identity receives only the permissions required to pull repository images. GitHub deploy identity receives the minimum push/deployment permissions required by the workflow.

Do not grant broad project Owner/Editor roles.

---

## 8. IAM & Workload Identity Federation Contract

Terraform provisions or configures:

- VM runtime service account;
- GitHub deploy service account;
- Workload Identity Pool;
- GitHub OIDC provider;
- repository-scoped principal binding for `AdelinV2/SeatFlow`;
- least-privilege Artifact Registry access;
- approved Secret Manager access;
- Compute/remote-execution permissions required by P10-007 only.

WIF is the sole GitHub-to-GCP credential path.

The VM runtime identity must be able to:

- pull Artifact Registry images;
- read only approved SeatFlow runtime secret versions;
- emit selected logging/monitoring data when configured.

It must not receive deployment/project administration rights merely for convenience.

---

## 9. Secret Manager Contract

Terraform creates secret **containers** and IAM bindings only. Secret values are inserted through an approved manual/rotation procedure outside Terraform state.

Expected production secret/config containers:

```text
postgres-admin-password
postgres-app-password
redis-password
stripe-api-key
stripe-webhook-secret
resend-api-key
grafana-admin-password
prometheus-scrape-token
```

Do not create meaningless per-service secrets if a value is public configuration. Apply least privilege by service/deployment identity.

P10-007 renders approved secret values on the VM into a root-owned runtime-only environment file or equivalent process environment without logging them.

---

## 10. Bootstrap Contract

`bootstrap-seatflow-vm.sh` is idempotent and may be invoked by Terraform startup metadata only for non-secret host initialization.

It may install/configure:

- Docker Engine / Compose plugin;
- Artifact Registry Docker credential helper or approved auth path;
- Google Cloud Ops Agent where selected;
- system directories such as `/opt/seatflow` and `/run/seatflow`;
- root-owned permissions;
- system service wrapper that can start/recover the Compose stack after VM reboot.

It must **not** contain:

- database passwords;
- Stripe/email secrets;
- service-account JSON keys;
- access tokens;
- GitHub tokens.

Runtime secrets are resolved through the VM service account after boot/deployment.

---

## 11. Compose Runtime Integration

Terraform does not define ten VM processes individually. It creates the host foundation; P10-005/P10-007 own application containers and rollout.

Terraform outputs include non-secret values required by CD, for example:

```text
vm_name
vm_zone
static_ip
artifact_registry_repository
runtime_service_account_email
deploy_service_account_email
wif_provider_name
```

P10-007 uses these outputs/GitHub environment variables to address the existing VM and deploy the image manifest.

---

## 12. Observability Integration

Self-hosted Prometheus/Grafana/Tempo/Loki remain part of the Compose runtime.

Terraform may additionally enable/install GCP Ops Agent and required IAM/API support for host-level Cloud Logging/Monitoring where this materially improves operation. Avoid duplicating every Prometheus metric into a paid managed service without a need.

The runbook must explain how to inspect:

- VM CPU/memory/disk/network;
- Docker/container status;
- Nginx/application logs;
- Grafana dashboards;
- Prometheus targets;
- Tempo traces;
- Loki log streams.

---

## 13. Cost Guardrails

The module must make expensive managed services impossible to create accidentally through default variables.

Add validation/policy/static tests that reject resources/types corresponding to the superseded topology where practical:

```text
google_cloud_run_v2_service
google_sql_database_instance
google_redis_instance
GKE cluster resources
Cloud Armor security policy
dedicated global forwarding/load-balancer stack
managed Kafka resources
```

Document the expected dominant costs as the single VM, persistent disk, and static public IP/traffic. The deployment is designed for roughly the 90-day portfolio/trial window, not an indefinite production SLA.

---

## 14. Verification Script Contract

`verify-gcp-foundation.sh` checks at minimum:

- required APIs enabled;
- expected Compute Engine VM exists/runs in configured zone;
- machine type matches approved input;
- disk size/type and deletion protection match contract;
- static IP attached;
- public firewall contains only approved web ingress;
- unrestricted SSH rule is absent;
- Artifact Registry repository exists;
- WIF provider/deploy identity bindings exist;
- VM runtime identity has expected pull/secret access and no broad Owner/Editor role;
- Secret Manager containers exist without any secret value printed;
- no Cloud Run/Cloud SQL/Memorystore/GKE runtime resources are required by the SeatFlow Terraform state.

---

## 15. Runbook Requirements

`infra/runbooks/gcp-compute-engine-deployment-and-verification.md` documents:

- prerequisites and billing/trial awareness;
- Terraform backend bootstrap;
- `init`, `validate`, `plan`, reviewed `apply`;
- DNS A-record to the static IP;
- HTTPS certificate setup/renewal for Nginx;
- OS Login/IAP administrative access;
- first VM bootstrap verification;
- inserting/rotating Secret Manager versions;
- Artifact Registry authentication;
- invoking P10-007 production deployment;
- PostgreSQL `pg_dump`/restore procedure;
- Kafka/data retention expectations;
- persistent disk snapshot/backup option where appropriate;
- VM restart/recovery procedure;
- disk-full/container-crash diagnostics;
- Terraform recovery/state procedure;
- explicit prohibition on accidental production `terraform destroy`.

---

## 16. Step-by-Step Implementation Sequence

1. Read ADR-008 and establish production project/region/zone/domain inputs.
2. Create provider/version/backend structure and production root.
3. Provision Artifact Registry, runtime/deploy service accounts, WIF and Secret Manager containers/IAM.
4. Provision network/subnet, static IP, restrictive firewall, and OS Login/IAP policy.
5. Provision the `e2-highmem-2` VM + persistent disk with deletion/security settings and idempotent non-secret bootstrap.
6. Add optional Ops Agent/Cloud Logging/Monitoring integration only where useful.
7. Produce non-secret Terraform outputs consumed by P10-007.
8. Add Terraform validation/static guardrails against the superseded managed topology.
9. Apply in the selected production GCP project after reviewed plan.
10. Verify foundation script and runbook before P10-007 performs the first full Compose deployment.

---

## 17. Definition of Done & Verification

```bash
terraform -chdir=infra/terraform/environments/production fmt -check -recursive
terraform -chdir=infra/terraform/environments/production init -backend=false
terraform -chdir=infra/terraform/environments/production validate
terraform -chdir=infra/terraform/environments/production plan -refresh=false -out=tfplan
./infra/scripts/verify-gcp-foundation.sh production
```

- [x] One approved Compute Engine VM foundation is reproducible through Terraform.
- [x] Default machine target is `e2-highmem-2` with persistent storage and static IP.
- [x] Public firewall exposes only the approved web edge; unrestricted admin/internal ports are absent.
- [x] Artifact Registry, Secret Manager, IAM and WIF are provisioned with least privilege.
- [x] No secret value exists in Terraform source/state/output.
- [x] No Cloud Run, Cloud SQL, Memorystore, managed Kafka, GKE, Cloud Armor, or dedicated load-balancer resource is required/provisioned.
- [x] Runbook covers deploy, HTTPS, backup/restore, VM recovery and Terraform recovery.
- [x] P10-007 receives all non-secret outputs needed to deploy production Compose.
- [x] On completion move this file to `.ai/tasks/completed/phase-10-devops-observability/008-compute-engine-gcp-production-terraform.md`.

