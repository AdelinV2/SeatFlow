# TASK-P10-007: Implement GitHub Actions Compute Engine CD with Workload Identity Federation

## 1. Task Metadata
- **Task ID:** `TASK-P10-007`
- **Git Branch:** `feat/p10-007-github-actions-compute-engine-cd-wif`
- **Target Module:** `.github/workflows/`, `.github/actions/`, `infra/scripts/`, deployment metadata
- **Phase:** `Phase 10 - DevOps & Observability`
- **Related Specs:** `AGENTS.md`, `.ai/architecture/08-observability-and-deployment.md`, `.ai/tasks/phase-10-devops-observability/005-full-stack-dockerization-and-compose-orchestration.md`, `.ai/tasks/phase-10-devops-observability/008-compute-engine-gcp-production-terraform.md`
- **Related ADRs:** `.ai/decisions/ADR-008-compute-engine-single-vm-portfolio-deployment.md`, `.ai/decisions/ADR-007-terraform-for-gcp-runtime-infrastructure.md`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective

Create passwordless GitHub Actions CI/CD for the single-VM SeatFlow GCP topology.

`develop` is the integration branch: it builds/tests immutable staging-tagged images, pushes them to Artifact Registry, and validates production Compose/Terraform contracts without creating a second always-on staging stack.

`main` / approved release tags deploy the immutable image set to the Terraform-managed Compute Engine VM using WIF, a controlled remote deployment path, production Compose, migrations, health checks, and image-based rollback.

### Critical Invariants
- [ ] GitHub-to-GCP auth uses Workload Identity Federation only; no service-account JSON key.
- [ ] `permissions` includes `id-token: write` and least-privilege `contents: read`.
- [ ] Application images are immutable and tagged with Git SHA; `latest` is never the production deployment selector.
- [ ] `develop` does not create or keep a second long-running GCP stack by default.
- [ ] Production deploys only to the single Compute Engine VM created by P10-008.
- [ ] The release sequence is pull → validate config → migrate once → replace services → verify.
- [ ] Failed verification restores the previously recorded image tag/Compose deployment metadata.
- [ ] Automated rollback never runs destructive Flyway rollback/clean/undo.
- [ ] Secrets are read through GCP Secret Manager/VM identity and are never printed by GitHub Actions.
- [ ] No Cloud Run, Cloud SQL, Memorystore, managed Kafka, GKE, Cloud Armor, or load-balancer deployment commands appear in the workflows/scripts.

---

## 3. Exact File Inventory

- `[NEW]` `.github/workflows/cd-staging.yml` — build/push/validate on `develop`, no permanent runtime deployment.
- `[NEW]` `.github/workflows/cd-production.yml` — approved single-VM deployment from `main` / semantic release.
- `[MODIFY]` `.github/workflows/ci-pr-check.yml` — include Docker/Compose/Terraform/workflow validation.
- `[NEW]` `.github/actions/setup-gcp-wif/action.yml` — reusable WIF auth action using official Google actions.
- `[NEW]` `infra/scripts/render-runtime-env.sh` — executed on VM; reads approved Secret Manager values into a root-owned runtime file without emitting them.
- `[NEW]` `infra/scripts/deploy-compose-release.sh` — pulls Artifact Registry images and performs controlled Compose rollout.
- `[NEW]` `infra/scripts/run-production-migrations.sh` — runs migrations exactly once before application rollout.
- `[NEW]` `infra/scripts/verify-compose-release.sh` — health/readiness and end-to-end smoke verification through the public Gateway/edge.
- `[NEW]` `infra/scripts/rollback-compose-release.sh` — restores previous image tag/deployment metadata and re-runs health verification.
- `[NEW]` `.github/workflows/tests/cd-workflow-contract.ps1` — rejects JSON-key auth, mutable selectors, Cloud Run commands, direct secret echoing, and missing environment gates.
- `[MODIFY]` deployment runbook generated/owned with P10-008.

---

## 4. Artifact Build Contract

Every deployable image is built independently and pushed to Artifact Registry.

Canonical naming:

```text
${GCP_REGION}-docker.pkg.dev/${GCP_PROJECT_ID}/${ARTIFACT_REGISTRY_REPOSITORY}/api-gateway:${GITHUB_SHA}
${GCP_REGION}-docker.pkg.dev/${GCP_PROJECT_ID}/${ARTIFACT_REGISTRY_REPOSITORY}/eureka-server:${GITHUB_SHA}
${GCP_REGION}-docker.pkg.dev/${GCP_PROJECT_ID}/${ARTIFACT_REGISTRY_REPOSITORY}/user-service:${GITHUB_SHA}
...
${GCP_REGION}-docker.pkg.dev/${GCP_PROJECT_ID}/${ARTIFACT_REGISTRY_REPOSITORY}/frontend:${GITHUB_SHA}
```

For `develop`, an additional convenience tag `staging-${GITHUB_SHA}` may be published. For a semantic release, a `vX.Y.Z` alias may be published. Production deployment metadata must still resolve to an immutable SHA/digest.

Use Buildx and enable provenance/SBOM where supported. Never place secrets in build args.

---

## 5. WIF Contract

Protected GitHub Environment variables hold non-secret identifiers such as:

```text
GCP_PROJECT_ID
GCP_REGION
GCP_WIF_PROVIDER
GCP_DEPLOY_SERVICE_ACCOUNT
ARTIFACT_REGISTRY_REPOSITORY
GCP_VM_NAME
GCP_VM_ZONE
GATEWAY_HEALTH_URL
```

The workflow uses:

```yaml
permissions:
  contents: read
  id-token: write
```

and official Google authentication/setup actions pinned according to repository security policy.

No workflow may contain a service-account key JSON, call `gcloud auth activate-service-account --key-file`, print access tokens, or upload credential artifacts.

---

## 6. `develop` Workflow Contract

`cd-staging.yml` is an integration artifact pipeline rather than a permanent staging deployment.

Trigger:

```yaml
on:
  push:
    branches: [develop]
  workflow_dispatch: {}
```

Required flow:

```text
checkout
 -> WIF auth
 -> backend/frontend tests or depend on green CI result
 -> build all immutable Docker images
 -> push staging-${SHA}/SHA images to Artifact Registry
 -> docker compose ... docker-compose.prod.yml config --quiet
 -> terraform fmt/validate
 -> workflow/script static checks
 -> publish deployment manifest metadata as artifact
```

No default `develop` workflow provisions a second VM or launches a duplicate always-on stack.

An optional manually approved smoke deployment may reuse the production-shaped VM only when explicitly invoked and must not coexist as a full second stack. This is optional and not required for DoD.

---

## 7. Production Workflow Contract

Triggers:

- approved merge/push to `main` according to branch protection; and/or
- semantic tag matching `^v[0-9]+\.[0-9]+\.[0-9]+$`.

The workflow uses `environment: production` with manual approval.

High-level sequence:

```text
1. WIF authenticate
2. build/push immutable release images if not already present
3. write release manifest containing image SHA/tag set (no secrets)
4. connect to VM through approved GCP remote execution path
5. VM authenticates to Artifact Registry using its runtime identity
6. render runtime secret environment from Secret Manager without stdout
7. save current deployment metadata as rollback target
8. docker compose pull
9. validate production Compose config
10. run Flyway migrations once
11. docker compose up -d --remove-orphans
12. wait for internal health checks
13. smoke-test HTTPS API + frontend + WebSocket-relevant readiness
14. mark release successful
```

The exact GCP remote execution mechanism should prefer authenticated GCP tooling (for example OS Login/IAP-backed SSH) over storing a private SSH key in GitHub.

---

## 8. Production Compose Invocation

The VM deployment script owns the canonical command:

```bash
docker compose \
  -f docker/docker-compose.yml \
  -f docker/docker-compose.services.yml \
  -f docker/docker-compose.monitoring.yml \
  -f docker/docker-compose.prod.yml \
  --env-file /run/seatflow/runtime.env \
  pull

docker compose \
  -f docker/docker-compose.yml \
  -f docker/docker-compose.services.yml \
  -f docker/docker-compose.monitoring.yml \
  -f docker/docker-compose.prod.yml \
  --env-file /run/seatflow/runtime.env \
  up -d --remove-orphans
```

`/run/seatflow/runtime.env` is root-owned mode `0600`, populated from Secret Manager at deployment/runtime, not from a repository `.env` file.

---

## 9. Migration Contract

`run-production-migrations.sh` executes migrations once per release before replacing application containers.

Requirements:

- use the release image/code compatible with the target schema;
- connect to the PostgreSQL container through the private Compose network or an explicit one-shot Compose migration service;
- abort release if migration fails;
- never run `flyway clean`, `undo`, or destructive automatic repair;
- migration changes must be backwards compatible with the previously running app version sufficiently for safe application rollback.

Do not let all ten services race to perform uncontrolled production migrations during startup.

---

## 10. Verification Contract

`verify-compose-release.sh` checks at minimum:

- Nginx/frontend returns expected HTTPS status/content;
- API Gateway `/actuator/health/readiness` or protected equivalent reports `UP`;
- required Eureka registrations are present through an internal VM check;
- PostgreSQL/Kafka/Redis containers are healthy;
- core business smoke path returns expected response;
- realtime service is healthy and WebSocket endpoint accepts an upgrade/handshake test where practical;
- no required container is continuously restarting.

Verification has a bounded timeout and exits non-zero on failure.

---

## 11. Rollback Contract

Before rollout, persist non-secret deployment metadata:

```text
current image SHA/tag
previous image SHA/tag
release timestamp
release version
```

On failed health/smoke verification:

```text
restore previous immutable image selector
 -> docker compose pull
 -> docker compose up -d --remove-orphans
 -> verify previous release
```

Do not attempt destructive DB schema rollback automatically. If a forward migration is not backward-compatible, the release must not pass review.

---

## 12. Security Contract

- No long-lived cloud key in GitHub.
- No secret values in workflow outputs, job summaries, artifacts, Docker labels, image layers, Terraform state, or logs.
- VM service account has only permissions required to pull Artifact Registry images, access approved secrets, and emit telemetry.
- GitHub deploy identity has only permissions needed for Artifact Registry/deployment access; it is not a project owner.
- Production GitHub Environment requires reviewer approval.
- Commands run with `set -euo pipefail`; secret-bearing commands disable shell tracing.
- Third-party actions are pinned under repository security policy.

---

## 13. Step-by-Step Implementation Sequence

1. Confirm P10-005 production Compose contract and P10-008 Terraform outputs.
2. Add/pin WIF composite action and workflow contract test.
3. Build `develop` pipeline: WIF → build/push immutable images → Compose/Terraform validation.
4. Implement VM-side runtime secret rendering and Artifact Registry authentication through the VM identity.
5. Implement migration, deployment, verification, and rollback scripts with shell tests/static checks.
6. Implement protected production workflow using authenticated GCP remote execution.
7. Rehearse a deployment using a non-critical release image set on the target VM.
8. Verify failed smoke test restores the previous image set without destructive DB actions.
9. Document release and manual recovery steps.

---

## 14. Definition of Done & Verification

```bash
actionlint .github/workflows/*.yml
shellcheck infra/scripts/*.sh
terraform -chdir=infra/terraform/environments/production fmt -check -recursive
terraform -chdir=infra/terraform/environments/production init -backend=false
terraform -chdir=infra/terraform/environments/production validate
powershell -ExecutionPolicy Bypass -File .github/workflows/tests/cd-workflow-contract.ps1
```

- [ ] `develop` builds/pushes immutable images and validates deployment contracts without a second permanent stack.
- [ ] Production deploys only after approval and only to the Terraform-managed Compute Engine VM.
- [ ] WIF is the only GitHub-to-GCP authentication path.
- [ ] Production Compose uses immutable image selectors from Artifact Registry.
- [ ] Secrets are sourced from Secret Manager and never emitted.
- [ ] Migrations execute once before application rollout.
- [ ] Failed release verification restores the prior image set.
- [ ] No Cloud Run / Cloud SQL / Memorystore / managed-Kafka / GKE deployment command exists in this task implementation.
- [ ] On completion move this file to `.ai/tasks/completed/phase-10-devops-observability/007-github-actions-compute-engine-cd-wif.md`.
