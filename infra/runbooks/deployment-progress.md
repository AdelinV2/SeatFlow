# SeatFlow Production Deployment Progress

Last updated: 2026-09-01

This file tracks non-secret operator state for the GCP production rollout. Secret values must never be recorded here.

## DONE

- Read the P10-007/P10-008 task contracts, deployment architecture, and ADR-006/007/008/009.
- Confirmed the target GCP project `seatflow-production-507311`, remote Terraform state, production VPC/subnet/firewall/API foundation, Artifact Registry, WIF configuration, least-privilege IAM, and secret-scoped runtime access.
- Deployed the production VM `seatflow-production` in `europe-west1-c` as `e2-highmem-2` with the Terraform-managed static IP, protected boot disk, Shielded VM, OS Login, Docker Engine/Compose, Ops Agent, `seatflow.service`, `/opt/seatflow`, and `/run/seatflow`.
- Installed the required PostgreSQL, Redis, Grafana, Stripe test API, and Resend secret versions without recording secret values.
- Audited Stripe test mode and confirmed no webhook endpoint exists yet; disabled the obsolete recoverable `stripe-webhook-secret` version because it was not associated with a Stripe endpoint.
- Audited Cloudflare and confirmed the zone still contains obsolete GitHub Pages apex records while Resend/mail records remain intact. DNS has intentionally not been changed yet.
- Configured Supabase Auth production URLs for `https://seat-flow.me` while retaining localhost redirects.
- Applied `public.custom_access_token_hook`, created the dedicated `prometheus@seat-flow.me` monitoring identity, set protected `metrics_read=true` app metadata, and verified its Supabase JWT contains only `scope=metrics.read` for the monitoring scope contract.
- Added Terraform-managed monitoring identity secret containers plus a root-only Prometheus JWT refresh service/timer. Deployment now forces an immediate refresh before runtime rendering and preserves the fresh `/run/seatflow/prometheus-scrape-token` instead of overwriting it from Secret Manager.
- Implemented P10-007 GitHub OIDC/WIF workflows, immutable Artifact Registry builds, IAP/OS Login VM delivery, explicit Flyway migrations, bounded Compose verification, and immutable-image rollback.
- Configured the GitHub `production` environment with the eight required non-secret GCP deployment variables.
- Documented the GitHub plan/repository limitation: the environment settings surface does not expose a required-reviewer protection rule. Do not fabricate this gate; use `AdelinV2` as reviewer identity where GitHub permits it.
- Normalized production shell scripts to LF and expanded CI ShellCheck coverage, including the Prometheus refresh and edge scripts.
- Fixed frontend CI to run all tests in `ChromeHeadless`; the full suite reaches 358/358 passing tests on Linux runners.
- Made the Angular production build deterministic by disabling network-dependent Google Fonts inlining while preserving production optimization.
- Added safe two-phase edge deployment: host Nginx can provide an HTTP pre-DNS rehearsal, the frontend container is loopback-only, production CORS defaults target `seat-flow.me`/`www.seat-flow.me`, and Certbot/public HTTPS is deferred until all production DNS names resolve to the VM.
- Made first deployment independent of a pre-existing Stripe webhook signing secret by using a deployment-local random bootstrap value until the real Stripe endpoint can be created after HTTPS is live.
- Corrected CD workflows so environment-scoped GitHub variables are read from jobs using `environment: production`, and ensured the production release bundle includes `infra/systemd`.
- Removed all temporary repair workflows used during CI recovery; only the permanent deployment workflows remain.

## BLOCKED

- Stripe test webhook creation is intentionally blocked until public HTTPS routing is live; the real endpoint must generate a new signing secret that replaces the bootstrap value.
- GitHub required-reviewer enforcement is unavailable in the repository/environment settings currently exposed for this plan. This does not block the technical release, but the limitation must remain documented.

## PENDING

- Obtain a fully green PR #4 CI run after the final cleanup/runbook commit and merge PR #4 into `develop`.
- Verify the automatic `develop` artifact-validation workflow builds and pushes all immutable images and publishes its deployment manifest.
- Promote the verified `develop` release to `main` using the repository's normal GitHub flow so the production workflow performs the first VM deployment.
- Verify on the VM: migrations, all required containers, gateway readiness, Eureka registration, frontend health, observability stack, Prometheus token refresh service/timer, and the pre-DNS host Nginx edge.
- Only after the internal release is healthy, apply the approved Cloudflare cutover: apex `seat-flow.me` to `207.175.104.184`, remove obsolete GitHub Pages apex A records, `www` to `seat-flow.me` DNS-only, and `api.seat-flow.me` to `207.175.104.184` DNS-only; preserve Resend/mail records.
- Complete Certbot provisioning and public HTTPS verification after DNS resolves to the VM.
- Create the Stripe test webhook after HTTPS is live, install its generated signing secret without exposing it, redeploy/refresh the runtime contract if required, and verify webhook delivery.
- Perform final public production routing/observability verification and update this runbook to the completed state.
