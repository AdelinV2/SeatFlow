# ADR-007: Terraform for GCP Runtime Infrastructure

## Status

ACCEPTED

## Context

Phase 10 must create repeatable GCP staging and production resources for Cloud Run, Cloud SQL PostgreSQL 16 HA, Memorystore Redis, Secret Manager, Cloud Armor, and the external HTTPS load balancer. The deployment specification permits either Cloud Run manifests or Terraform. These resources have dependencies, IAM bindings, immutable network settings, and environment-specific values that must be reviewed as one plan.

## Decision

Use Terraform under `infra/terraform/` as the source-controlled definition of GCP runtime infrastructure. Use one reusable module for the shared platform resources and two root environment directories, `environments/staging` and `environments/production`. Cloud Run application revisions remain deployed by the CD workflows after Terraform has created the services, service accounts, secrets, networking, and load balancer.

Terraform state is stored in an encrypted, versioned GCS backend configured outside source control. Runtime secret *values* are never Terraform variables, state values, workflow logs, or repository files: Terraform creates secret containers and grants Cloud Run access; operators or the approved secret-rotation process add secret versions.

## Alternatives Considered

1. Cloud Run YAML manifests only. They describe revisions well but do not coherently manage Cloud SQL HA, Memorystore, Secret Manager IAM, Cloud Armor, or the HTTPS load balancer.
2. Console-managed infrastructure. This is fast initially but has no reviewable drift detection, reproducibility, or reliable disaster recovery path.
3. A single unsegmented Terraform root. It is simpler to start but makes staging/production separation and blast-radius control too weak.

## Consequences

Positive consequences: reviewed `plan` output, environment isolation, deterministic IAM/networking, and drift-aware operations. Negative consequences: operators must bootstrap remote state and understand Terraform/provider upgrades. Mitigation: pin providers, validate/format in CI, use separate state prefixes, and require production plan approval.

## Implementation Notes

- Implemented by `TASK-P10-008`.
- `TASK-P10-007` authenticates through GitHub OIDC/WIF and applies only approved plans for the selected environment.
- Cloud Run application image tags and secret version values remain runtime deployment concerns, not Terraform state.
