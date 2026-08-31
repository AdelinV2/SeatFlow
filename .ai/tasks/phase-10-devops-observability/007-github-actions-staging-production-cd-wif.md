# TASK-P10-007: Implement GitHub Actions Staging and Production CD with WIF

## 1. Task Metadata
- **Task ID:** `TASK-P10-007`
- **Git Branch:** `feat/p10-007-github-actions-cd-wif`
- **Target Module:** `.github/workflows/`, deployment scripts, and Terraform validation integration
- **Phase:** `Phase 10 - DevOps & Observability`
- **Related Specs:** `AGENTS.md`, `.ai/architecture/08-observability-and-deployment.md`, `.ai/tasks/phase-10-devops-observability/005-full-stack-dockerization-and-compose-orchestration.md`, `.ai/tasks/phase-10-devops-observability/008-cloud-run-gcp-production-terraform.md`
- **Related ADRs:** `.ai/decisions/ADR-007-terraform-for-gcp-runtime-infrastructure.md`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants

Create passwordless GitHub Actions deployments. A `develop` push builds immutable staging images, migrates Cloud SQL safely, and deploys Cloud Run. `main` and semver-tag releases use the production environment gate, build immutable release tags, migrate once, and advance Cloud Run traffic 10% → 50% → 100% only after health checks.

### Critical Invariants to Enforce:
- [ ] Workload Identity Federation is the only GitHub-to-GCP credential path; no service-account JSON key is a secret, artifact, variable, or file.
- [ ] The job has `permissions: id-token: write` and least-privilege `contents: read`; WIF provider/service account come from protected GitHub environment variables.
- [ ] A database migration runs once per environment/release before service rollout, with Cloud SQL connectivity through the deployed migration identity; no parallel service job runs Flyway.
- [ ] Every image is immutable (`staging-${GITHUB_SHA}` or semantic release SHA/tag); production `latest` is a convenience tag, never a deployment selector.
- [ ] Production rollout stops and restores 100% traffic to the last healthy revision on failed health verification; it never executes an automatic destructive database rollback.

---

## 3. Exact File Inventory

- `[NEW]` `.github/workflows/cd-staging.yml`.
- `[NEW]` `.github/workflows/cd-production.yml`.
- `[MODIFY]` `.github/workflows/ci-pr-check.yml` — include frontend/docker/infra changes and validate workflow/Terraform syntax; retain existing PR verification.
- `[NEW]` `.github/actions/setup-gcp-wif/action.yml` — composite action for `google-github-actions/auth@v3` and `setup-gcloud@v3` only.
- `[NEW]` `infra/scripts/run-flyway-migrations.sh` — idempotent Cloud Run Job migration launcher/waiter.
- `[NEW]` `infra/scripts/deploy-cloud-run-revision.sh` — deploy service revision with immutable image, secret mapping, health wait and no traffic by default.
- `[NEW]` `infra/scripts/promote-cloud-run-traffic.sh` — 10/50/100 rollout with verification and rollback to saved revision.
- `[NEW]` `infra/scripts/verify-cloud-run-release.sh` — authenticated health/readiness and Gateway smoke tests.
- `[NEW]` `.github/workflows/tests/cd-workflow-contract.ps1` — static workflow contract test, runnable locally and in CI.
- `[MODIFY]` `infra/terraform/environments/staging/variables.tf` and `infra/terraform/environments/production/variables.tf` — declare only non-secret CI inputs/outputs used by workflows, after P10-008 creates them.

---

## 4. Technical Specifications & Contracts

### 4.1 Required GitHub Environment Values

The protected `staging` and `production` environments contain variables, not secrets, named `GCP_PROJECT_ID`, `GCP_REGION`, `GCP_WIF_PROVIDER`, `GCP_DEPLOY_SERVICE_ACCOUNT`, `ARTIFACT_REGISTRY_REPOSITORY`, `CLOUD_RUN_SERVICE_PREFIX`, and `GATEWAY_HEALTH_URL`. They contain secrets only when a third-party migration/runtime value cannot live in GCP Secret Manager. GitHub environment protection rules require named reviewers for `production`; staging has no manual gate.

### 4.2 Staging Workflow Contract

```yaml
name: CD - Staging
on:
  push: {branches: [develop]}
  workflow_dispatch: {}
permissions: {contents: read, id-token: write}
concurrency: {group: cd-staging, cancel-in-progress: false}
jobs:
  deploy:
    environment: staging
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: ./.github/actions/setup-gcp-wif
        with:
          workload_identity_provider: ${{ vars.GCP_WIF_PROVIDER }}
          service_account: ${{ vars.GCP_DEPLOY_SERVICE_ACCOUNT }}
      - run: ./infra/scripts/run-flyway-migrations.sh staging ${{ github.sha }}
      - run: ./infra/scripts/deploy-cloud-run-revision.sh staging staging-${{ github.sha }}
      - run: ./infra/scripts/verify-cloud-run-release.sh staging
```

The build step uses `docker buildx build --platform linux/amd64 --push` once per Dockerfile and tags `${GCP_REGION}-docker.pkg.dev/${GCP_PROJECT_ID}/${ARTIFACT_REGISTRY_REPOSITORY}/${service}:staging-${GITHUB_SHA}`. Build provenance/SBOM must be enabled with `--provenance=true --sbom=true`.

### 4.3 Production Workflow and Traffic Contract

Production triggers on `push` to `main` and `push.tags: ['v*.*.*']`; reject a tag that does not match `^v[0-9]+\.[0-9]+\.[0-9]+$`. It uses `environment: production`, identical WIF authentication, a single migration job, then parallel image/revision deploys with `--no-traffic`. It assigns initial tags `candidate-${GITHUB_SHA}` and release `${GITHUB_REF_NAME#v}`; only after all deploys succeed may it apply:

```bash
gcloud run services update-traffic "$service" --region "$region" --to-revisions "${candidate}=10,${previous}=90"
verify 10
gcloud run services update-traffic "$service" --region "$region" --to-revisions "${candidate}=50,${previous}=50"
verify 50
gcloud run services update-traffic "$service" --region "$region" --to-revisions "${candidate}=100"
```

`verify` polls `/actuator/health/readiness` through the protected gateway for up to 5 minutes and requires HTTP 200 plus an `UP` status. On failure, invoke `gcloud run services update-traffic --to-revisions "${previous}=100"`, retain candidate diagnostics, and exit non-zero.

### 4.4 Migration Contract

`run-flyway-migrations.sh` deploys/runs the P10-008-created `seatflow-<environment>-migrations` Cloud Run Job with `--wait`, revision environment `SPRING_PROFILES_ACTIVE=prod`, Cloud SQL instance binding, and Secret Manager references. It verifies Flyway’s exit status and exactly one execution before images/revisions deploy. Migrations are forward-only and backwards compatible; this job never invokes `flyway clean`, `repair`, or `undo` in automated CD.

### 4.5 Workflow Security Contract

Pin third-party actions to documented major version and full immutable commit SHA once validated by the repository security policy. Do not echo environment JSON, access tokens, `gcloud auth print-access-token`, Secret Manager values, Docker build args, or raw `gcloud run services describe` output containing environment variables. Artifact uploads contain only test reports, Terraform plan metadata with sensitive fields redacted, SBOM, and release verification summaries.

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Checkout `feat/p10-007-github-actions-cd-wif`; validate the P10-008 Terraform outputs and establish protected GitHub environment values with platform owners.
2. Add the WIF composite action and static test that rejects JSON-key auth, missing `id-token: write`, missing environment, missing SHA tags, and unguarded production triggers.
3. Implement/rehearse migration, revision deploy, verification, traffic promotion and safe rollback scripts against a non-production project.
4. Create staging workflow with serial migrate → deploy → verify stages and immutable Artifact Registry images.
5. Create production workflow with protected environment, semantic release validation, all revisions deployed without traffic, and staged promotion/rollback.
6. Extend PR CI with actionlint, shellcheck, Docker Compose configuration, Terraform `fmt -check`/`validate`, and workflow contract test.
7. Dry-run the scripts with mocked `gcloud` in CI, then conduct one approved staging deployment before enabling the production workflow.

---

## 6. Definition of Done & Verification Command

To verify this task, run:

```bash
actionlint .github/workflows/*.yml
shellcheck infra/scripts/*.sh
terraform -chdir=infra/terraform/environments/staging fmt -check -recursive
terraform -chdir=infra/terraform/environments/staging validate
powershell -ExecutionPolicy Bypass -File .github/workflows/tests/cd-workflow-contract.ps1
```

- [ ] Staging deploys only from `develop` through WIF with SHA-tagged images.
- [ ] Production is manually approved through the protected environment and uses WIF plus semver/immutable tags.
- [ ] Migrations run once and finish before any service receives new traffic.
- [ ] A failed 10/50/100 health check restores prior traffic and preserves diagnostics.
- [ ] No long-lived cloud key or secret value is stored or emitted.
- [ ] On completion move this file to `.ai/tasks/completed/phase-10-devops-observability/007-github-actions-staging-production-cd-wif.md`.
