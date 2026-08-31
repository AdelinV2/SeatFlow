# ADR-009: Dedicated OAuth Scope for Prometheus Scraping

- **Date:** 2026-08-31
- **Author(s):** SeatFlow Engineering
- **Driven by Task:** TASK-P10-004
- **Supersedes:** N/A

## 1. Status
`ACCEPTED`

## 2. Context
SeatFlow exposes Micrometer metrics through Spring Boot Actuator. Allowing `/actuator/prometheus` anonymously does not constrain it to Docker or private-cloud networks and exposes operational metadata on public application listeners. Existing JWT conversion retained roles but discarded standard OAuth scopes, so an independently authorized monitoring identity could not be represented.

## 3. Decision
Prometheus scraping requires the dedicated OAuth authority `SCOPE_metrics.read`. The shared JWT converter preserves standard `scope`/`scp` authorities alongside SeatFlow roles. A scope-only monitoring identity is not assigned the default customer role. Health and info retain their existing public behavior; `/actuator/metrics` requires an administrator role.

Prometheus reads a bearer token from a runtime-mounted secret file. No token is stored in source control. Docker and cloud environments must issue and rotate a token for a dedicated monitoring principal with the `metrics.read` scope.

## 4. Alternatives Considered
1. **Anonymous endpoint on an assumed private network:** simple, but application authorization cannot prove network isolation and public ingress mistakes expose metrics. Rejected.
2. **Static HTTP Basic credentials in every service:** broadly compatible, but duplicates credential configuration and creates long-lived passwords. Rejected.
3. **IP-address allowlisting in application code:** avoids tokens, but is brittle behind proxies and unsafe unless forwarded-header trust is perfectly configured. Rejected.

## 5. Consequences
### Positive:
- Scrape authorization is explicit, testable, and consistent across reactive and servlet services.
- Monitoring credentials can be short-lived and rotated without code changes.
- Scope-only monitoring identities are not treated as SeatFlow customers.

### Negative / Trade-offs:
- Local Prometheus needs a valid runtime token file.
- The identity provider and deployment pipeline must issue and rotate the monitoring token.

## 6. Implementation Notes
- Impacted modules: `common-security`, API Gateway, Eureka, all eight business services, and Docker Prometheus configuration.
- Related task: `.ai/tasks/completed/phase-10-devops-observability/004-prometheus-metrics-and-business-kpis.md`.
