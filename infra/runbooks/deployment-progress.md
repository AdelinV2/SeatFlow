# SeatFlow Production Deployment Progress

Last updated: 2026-09-01

This file tracks non-secret operator state for the GCP production rollout. Secret values must never be recorded here.

## DONE

- Read the P10-007/P10-008 task contracts, deployment architecture, and ADR-006/007/008/009.
- Created the mandatory operator branch `feat/p10-008-gcp-production-terraform` from the current local `develop` state.
- Confirmed the target GCP project `seatflow-production-507311` is active and the authenticated account can inspect it.
- Confirmed the versioned, uniform-access GCS state bucket `seatflow-prod-adelin-tfstate` exists in `EUROPE-WEST1`.
- Confirmed the production state prefix currently contains no Terraform state object.
- Confirmed the checked-in Terraform configuration validates before backend repair.
- Confirmed the runtime contract uses one shared application PostgreSQL credential and the expected eight Secret Manager containers.
- Confirmed runtime Secret Manager access is secret-scoped rather than project-wide.
- Confirmed WIF is restricted to `AdelinV2/SeatFlow`, public web ingress is limited to 80/443, and SSH ingress is limited to the IAP CIDR.
- Activated the ignored production `backend.tf` from the checked-in example so state can be stored remotely and versioned.
- Applied 46 non-VM foundation resources successfully to the remote state.
- Confirmed two VM attempts in `europe-west1-b` failed only because GCP reported no capacity for an 80 GB `pd-balanced` disk; neither attempt left an instance or disk.
- Selected `europe-west1-c` as the same-region capacity fallback and updated production operational defaults accordingly.
- Completed Terraform apply with 47 resources in remote state and confirmed a post-apply no-change plan.
- Verified the production VM is RUNNING as `e2-highmem-2` with an attached 80 GB `pd-balanced` non-auto-delete boot disk, deletion protection, Shielded VM, OS Login, STANDARD scheduling, and the Terraform static IP.
- Verified the VPC, subnet, exact firewall rules, nine required APIs, Artifact Registry, WIF condition, least-privilege IAM, eight secret containers, and absence of broad runtime Secret Manager access.
- Verified VM bootstrap: Docker Engine, Docker Compose, Ops Agent, `seatflow.service`, `/opt/seatflow`, and `/run/seatflow`.
- Generated and installed version 1 for `postgres-admin-password`, `postgres-app-password`, `redis-password`, and `grafana-admin-password` without exposing values.
- Installed version 1 for the existing Stripe test API key and Resend API key without exposing values.
- Audited Stripe test mode and confirmed there are currently no registered webhook endpoints.
- Disabled the recoverable `stripe-webhook-secret` version 1 after confirming it was not associated with a Stripe endpoint.
- Audited Cloudflare and confirmed the active zone still contains four obsolete GitHub Pages apex records; existing Resend and mail records are intact.
- Confirmed the Supabase project has no existing monitoring user carrying `metrics.read`.
- Implemented P10-007 WIF workflows, immutable image builds, VM metadata-identity secret access, explicit migrations, bounded verification, and image rollback on `feat/p10-007-github-actions-compute-engine-cd-wif`.
- Passed Bash syntax, ShellCheck, actionlint, CD contract tests, full production Compose rendering, and Terraform format/init/validate.
- Configured Supabase Auth production URL handling: Site URL `https://seat-flow.me`, while retaining local redirects and adding exact callback/reset-password redirects for the apex and `www` hosts.
- Created the GitHub `production` environment and stored all eight non-secret GCP deployment identifiers as environment-scoped variables.
- Applied the Supabase access-token hook `public.custom_access_token_hook`, created the dedicated `prometheus@seat-flow.me` identity, assigned only the protected `metrics_read` app-metadata flag, and verified a Supabase-issued JWT with `scope=metrics.read`.
- Added Terraform-managed monitoring identity secret containers and a root-only VM token refresher with a 45-minute systemd timer; the initial metrics JWT is stored in Secret Manager without recording its value.

## BLOCKED

- Stripe production-style test webhook creation depends on working HTTPS routing; the endpoint must generate a new signing secret.
- The current GitHub environment settings surface exposes no required-reviewer protection rule for this repository, so `AdelinV2` cannot yet be configured as the reviewer gate. The environment and its scoped deployment variables are otherwise ready.

## PENDING

- Apply the approved Cloudflare DNS record changes only after the release is ready, provision the origin certificate, and verify HTTPS.
- Implement the accepted dedicated `metrics.read` Prometheus credential lifecycle.
- Create the Stripe test webhook endpoint after HTTPS is live and install its generated signing secret.
- Resolve the GitHub plan/repository capability needed to enforce the `AdelinV2` production-reviewer gate.
- Commit the verified P10-007 implementation, then perform and verify the first production deployment.

