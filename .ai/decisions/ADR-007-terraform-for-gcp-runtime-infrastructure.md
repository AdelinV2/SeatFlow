# ADR-007: Terraform for GCP Runtime Infrastructure

## Status

SUPERSEDED by `ADR-008-compute-engine-single-vm-portfolio-deployment.md`.

## Context

The original Phase 10 plan selected Terraform to provision a fully managed GCP topology based on Cloud Run, Cloud SQL PostgreSQL, Memorystore Redis, Cloud Armor, and an external HTTPS load balancer. The Terraform choice remains valid, but the managed runtime topology no longer matches the MVP deployment objective and 90-day portfolio budget.

## Original Decision

Use Terraform under `infra/terraform/` as the source-controlled definition of GCP runtime infrastructure, with isolated environment configuration, remote GCS state, reviewed plans, and secret values kept outside Terraform state.

## Supersession

ADR-008 keeps Terraform, remote state, IAM/WIF, Artifact Registry, Secret Manager, reproducibility, and reviewed infrastructure changes, but changes the runtime target to a single Compute Engine VM running production Docker Compose.

The following parts of the original decision are no longer MVP requirements:

- Cloud Run services/revisions;
- Cloud SQL;
- Memorystore;
- Cloud Armor / dedicated HTTPS load balancer;
- managed Kafka;
- duplicate always-on staging runtime.

## Historical Alternatives Considered

1. Cloud Run + managed data services — operationally attractive at scale, but unnecessarily expensive and complex for a short-lived low-traffic portfolio deployment.
2. Console-managed VM — cheaper and simpler, but lacks reproducibility and reviewable infrastructure changes.
3. Terraform-managed Compute Engine — selected by ADR-008 as the best balance of cost, cloud engineering value, and operational simplicity.

## Consequences

Terraform remains mandatory for the Phase 10 cloud foundation. Existing or future automation must follow ADR-008 for the actual resource topology.
