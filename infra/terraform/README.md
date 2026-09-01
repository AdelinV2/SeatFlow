# SeatFlow Infrastructure as Code (Terraform)

This directory contains the production and staging-validation Infrastructure as Code (IaC) for **SeatFlow** on Google Cloud Platform (GCP).

The infrastructure design follows [ADR-008: Compute Engine Single-VM Portfolio Deployment](../../.ai/decisions/ADR-008-compute-engine-single-vm-portfolio-deployment.md) and [TASK-P10-008](../../.ai/tasks/completed/phase-10-devops-observability/008-compute-engine-gcp-production-terraform.md).

---

## 1. Architectural Topology

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Google Cloud Platform                              │
│  Project: seatflow-production-507311                                        │
│                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │ Custom VPC (seatflow-vpc) / Subnet (seatflow-subnet, 10.0.1.0/24)      │  │
│  │                                                                       │  │
│  │  Static IPv4 (seat-flow.me)                                           │  │
│  │         │                                                             │  │
│  │         ▼                                                             │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │  │
│  │  │ Compute Engine VM (seatflow-production)                         │  │  │
│  │  │ Machine: e2-highmem-2 (2 vCPU, 16 GiB RAM)                      │  │  │
│  │  │ Disk: 80 GB pd-balanced (Ubuntu 24.04 LTS)                      │  │  │
│  │  │ Service Account: seatflow-vm-runtime                            │  │  │
│  │  │                                                                 │  │  │
│  │  │  Docker Compose v2 Stack:                                       │  │  │
│  │  │  • Nginx Gateway / Frontend Edge (Ports 80 / 443)                │  │  │
│  │  │  • 8 Spring Boot Business Services + Eureka + API Gateway       │  │  │
│  │  │  • PostgreSQL 16 (internal only)                                │  │  │
│  │  │  • Apache Kafka in KRaft Mode (internal only)                   │  │  │
│  │  │  • Redis 7 (internal only)                                      │  │  │
│  │  │  • Observability: Prometheus, Grafana, Tempo, Loki, Promtail    │  │  │
│  │  └─────────────────────────────────────────────────────────────────┘  │  │
│  │                                                                       │  │
│  │  Firewall Rules:                                                      │  │
│  │  • Ingress TCP 80, 443 (0.0.0.0/0) -> tag: seatflow-web               │  │
│  │  • Ingress TCP 22 (35.235.240.0/20 GCP IAP only) -> tag: seatflow-vm  │  │
│  │  • Zero open internal ports / No unrestricted public SSH              │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
│  Managed Cloud Capabilities:                                                │
│  • Artifact Registry: Docker repository `seatflow` (europe-west1)           │
│  • Secret Manager: 8 empty secret containers (populated out-of-band)        │
│  • Workload Identity Federation: Pool & OIDC Provider for GitHub Actions    │
│  • Cloud Logging & Monitoring: Google Cloud Ops Agent integration           │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Directory Layout

```text
infra/terraform/
├── versions.tf                          # Root Terraform version (>= 1.9.0, < 2.0.0) & Google provider (~> 6.0)
├── providers.tf                         # Google provider initialization
├── README.md                            # This overview & architecture guide
│
├── modules/
│   └── seatflow-vm/                     # Reusable Single-VM Foundation Module
│       ├── main.tf                      # GCP Service APIs enablement
│       ├── variables.tf                 # Configurable inputs & defaults
│       ├── outputs.tf                   # Non-secret outputs for CD & operations
│       ├── network.tf                   # Custom VPC, subnet, static IP, strict firewall
│       ├── compute.tf                   # Compute Engine instance (e2-highmem-2) & metadata
│       ├── artifact-registry.tf         # Docker Artifact Registry repo
│       ├── iam.tf                       # Runtime & Deploy Service Accounts + IAM roles
│       ├── wif.tf                       # Workload Identity Federation for GitHub Actions
│       ├── secrets.tf                   # 8 Secret Manager containers
│       └── monitoring.tf                # Ops Agent alert policies (CPU, Disk)
│
└── environments/
    ├── production/                      # Production Environment Root
    │   ├── main.tf                      # Instantiates module "seatflow_vm"
    │   ├── variables.tf                 # Production defaults
    │   ├── outputs.tf                   # Proxies module outputs
    │   ├── terraform.tfvars.example     # Example variable values
    │   └── backend.tf.example           # GCS Remote State configuration
    │
    └── staging-validation/              # Staging Dry-Run Validation Root
        ├── main.tf                      # Syntactic validation without permanent VM
        ├── variables.tf
        └── outputs.tf
```

---

## 3. Strict Architectural & Cost Guardrails

1. **Single Compute Engine VM (`e2-highmem-2`):**
   - 2 vCPU, 16 GiB RAM comfortably hosts the 10 Java microservices, PostgreSQL, Kafka KRaft, Redis, and observability containers.
   - Boot disk is 80 GB `pd-balanced` with `auto_delete = false` and instance `deletion_protection = true`.
2. **Zero Superseded Managed Services:**
   - **No** Cloud Run instances.
   - **No** Cloud SQL database instances.
   - **No** Memorystore Redis instances.
   - **No** Managed Kafka / Confluent Cloud clusters.
   - **No** Google Kubernetes Engine (GKE) clusters.
   - **No** Cloud Armor or dedicated Cloud Load Balancer (Nginx handles edge routing/TLS).
3. **Zero Secrets in Code or Terraform State:**
   - Terraform provisions **empty Secret Manager containers** only.
   - Real secret payloads are injected out-of-band via `gcloud secrets versions add` or runtime bootstrap.
4. **Least-Privilege Identities:**
   - **`seatflow-vm-runtime`**: Pull from Artifact Registry, read Secret Manager secrets, emit logs/metrics.
   - **`seatflow-github-deploy`**: Push to Artifact Registry, IAP tunnel SSH access, instance control for deployments.
   - **WIF Principal Binding**: Restricts token exchange strictly to repository `AdelinV2/SeatFlow`.

---

## 4. Usage & Provisioning Workflow

### 4.1 Prerequisites
- Google Cloud SDK (`gcloud`) installed and authenticated:
  ```bash
  gcloud auth login
  gcloud config set project seatflow-production-507311
  ```
- Terraform CLI (`>= 1.9.0`):
  ```bash
  terraform version
  ```

### 4.2 Initialize GCS Remote State Backend
Create the GCS bucket for remote Terraform state storage:
```bash
gcloud storage buckets create gs://seatflow-prod-adelin-tfstate \
  --project=seatflow-production-507311 \
  --location=europe-west1 \
  --uniform-bucket-level-access
```

Enable object versioning:
```bash
gcloud storage buckets update gs://seatflow-prod-adelin-tfstate --versioning
```

Copy the backend configuration:
```bash
cp infra/terraform/environments/production/backend.tf.example infra/terraform/environments/production/backend.tf
```

### 4.3 Initialize & Apply Terraform
```bash
cd infra/terraform/environments/production

# Initialize Terraform with GCS backend
terraform init

# Validate configuration syntax
terraform validate

# Review execution plan
terraform plan -out=tfplan

# Apply execution plan
terraform apply tfplan
```

### 4.4 Verify Deployment
Execute the automated foundation verification script:
```bash
./infra/scripts/verify-gcp-foundation.sh production
```

For complete step-by-step operations, DNS mapping, SSL provisioning, and disaster recovery procedures, refer to [GCP Compute Engine Deployment Runbook](../runbooks/gcp-compute-engine-deployment-and-verification.md).

