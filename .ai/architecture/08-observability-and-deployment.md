# 08 — Observability, CI/CD & Deployment Specification

This document is the authoritative SeatFlow specification for runtime configuration, container orchestration, observability, CI/CD, and the Google Cloud portfolio deployment. It supersedes older Cloud Run / Cloud SQL / Memorystore / managed-Kafka deployment wording elsewhere in the repository for the MVP deployment topology.

The MVP cloud target is intentionally optimized for a portfolio application that needs to remain publicly available for roughly the Google Cloud Free Trial window rather than for high-scale commercial traffic.

---

## 1. Deployment Decision

### 1.1 Production Runtime

SeatFlow production runs on **one Google Cloud Compute Engine VM** using a production Docker Compose stack.

Default target:

```text
Google Cloud Compute Engine
Machine type: e2-highmem-2
CPU:          2 vCPU
Memory:       16 GiB
OS:           Ubuntu LTS / Container-Optimized compatible Linux
Disk:         persistent balanced disk sized for the portfolio workload
Runtime:      Docker Engine + Docker Compose v2
```

The VM hosts the application, stateful infrastructure, and the self-hosted observability stack:

```text
Internet
   |
   | HTTPS / WSS
   v
Nginx edge container
   |
   +--> Angular SPA
   +--> API Gateway
             |
             +--> Eureka Server
             +--> User Service
             +--> Seat Map Service
             +--> Event Service
             +--> Reservation Service
             +--> Payment Service
             +--> Ticket Service
             +--> Realtime Service
             +--> Notification Service

Private Docker network
   |
   +--> PostgreSQL 16
   +--> Apache Kafka (KRaft)
   +--> Redis 7
   +--> OpenTelemetry Collector
   +--> Prometheus
   +--> Grafana
   +--> Tempo
```

Only the edge proxy exposes public application ports. PostgreSQL, Kafka, Redis, Eureka, backend service ports, Actuator endpoints, Prometheus, Tempo, and Grafana are private by default.

### 1.2 Managed GCP Services Kept

The deployment deliberately keeps a small number of GCP-managed capabilities that add real operational value without requiring separate managed runtimes for every SeatFlow component:

- **Artifact Registry** — immutable Docker image storage.
- **Secret Manager** — production secrets and runtime credentials.
- **IAM + Workload Identity Federation (WIF)** — passwordless GitHub Actions authentication and least-privilege VM/deployment identities.
- **Cloud Logging / Cloud Monitoring where useful** — VM/platform visibility in addition to the self-hosted Prometheus/Grafana/Tempo stack.
- **Compute Engine persistent disk, static IP and firewall rules** — provisioned with Terraform.

### 1.3 Explicit MVP Non-Targets

Do **not** provision these services for the portfolio MVP unless a later ADR changes the decision:

- Google Cloud Run for the microservices.
- Google Cloud SQL.
- Google Cloud Memorystore.
- Google Managed Service for Apache Kafka / Confluent Cloud.
- Google Kubernetes Engine / Kubernetes.
- Cloud Armor, Cloud CDN, or a dedicated external HTTPS load balancer solely for architectural appearance.
- Separate always-on staging infrastructure duplicating the production stack.

These are valid future scale/migration options, but they add cost and operational surface without solving a current SeatFlow requirement.

---

## 2. Environment & Configuration Architecture

SeatFlow retains four Spring profiles:

| Profile | Purpose | Runtime addressing |
|---|---|---|
| `local` | IDE/host development | `localhost` dependencies |
| `docker` | Local Docker Compose | Docker DNS names |
| `prod` | Compute Engine production Docker Compose | Docker DNS names + production security/resource settings |
| `test` | Automated tests | Testcontainers/dynamic properties |

`prod` is **not** a Cloud Run-specific profile. It represents production behavior independent of orchestrator and therefore uses environment-provided endpoints such as `postgres`, `kafka`, `redis`, and `eureka-server` inside the private Compose network.

Example production contract:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:postgres}:${DB_PORT:5432}/${DB_NAME}
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:kafka:9092}

eureka:
  client:
    service-url:
      defaultZone: ${EUREKA_SERVER_URL:http://eureka-server:8761/eureka}

spring.data.redis:
  host: ${REDIS_HOST:redis}
  port: ${REDIS_PORT:6379}
```

Real secrets are never committed. The deployment identity reads approved Secret Manager versions during deployment and materializes only the minimum runtime environment required by Compose in a root-owned, `0600` temporary/runtime location. GitHub Actions must never store a long-lived service-account JSON key or print secret values.

---

## 3. Production Docker Compose Contract

Local orchestration is split into:

```text
docker/docker-compose.yml
  core infrastructure + Eureka + Gateway

docker/docker-compose.services.yml
  eight business services + frontend

docker/docker-compose.monitoring.yml
  OTel Collector + Prometheus + Grafana + Tempo
```

Phase 10 adds:

```text
docker/docker-compose.prod.yml
```

as a production override rather than a second, unrelated deployment model.

The production override must:

- use Artifact Registry images pinned by immutable SHA/release tag, not local builds;
- set `SPRING_PROFILES_ACTIVE=prod` for JVM services;
- apply conservative CPU/memory limits so the full stack fits the 2-vCPU / 16-GiB VM;
- set `restart: unless-stopped` or an equivalent restart policy;
- keep PostgreSQL and Kafka on persistent named volumes;
- keep Redis non-authoritative;
- use internal Docker networks and expose only the Nginx edge publicly;
- add health checks and health-aware startup ordering;
- use bounded Docker log rotation;
- never mount repository `.env` files or source-control secrets;
- preserve one logical PostgreSQL database per business service even though one PostgreSQL server/container hosts them.

Recommended JVM portfolio defaults are intentionally conservative and must be validated under the complete stack:

```text
Most JVM services:      256–384 MiB max heap
Reservation/Event:      384–512 MiB max heap when measurements justify it
Kafka:                  bounded heap appropriate for demo traffic
PostgreSQL:             conservative shared buffers / connection counts
Prometheus/Tempo:       short portfolio retention
```

Resource limits are operational safeguards, not business invariants; tune them from measured usage.

---

## 4. Networking & Edge

Terraform provisions the VM, VPC/firewall resources, static external IP, service account, and related IAM. Public ingress is limited to the application edge:

```text
TCP 80  -> HTTPS redirect / certificate bootstrap
TCP 443 -> Nginx HTTPS/WSS
```

Administrative SSH should prefer an authenticated Google Cloud path such as IAP/OS Login instead of exposing unrestricted TCP/22 to the Internet.

Nginx responsibilities:

- serve the Angular production build;
- proxy `/api/` to `api-gateway:8080`;
- proxy WebSocket/STOMP traffic with upgrade headers;
- terminate HTTPS using the approved certificate approach;
- apply security headers and sensible request/body/timeouts;
- never expose internal service ports directly.

Eureka remains useful in this deployment because the Spring services are independent containers and use the existing Eureka + Spring Cloud LoadBalancer contract. Kubernetes-native service discovery is not introduced.

---

## 5. Data & Messaging Runtime

### PostgreSQL

One PostgreSQL 16 container/instance hosts separate SeatFlow logical databases. Service data ownership remains unchanged: no service may query another service's database directly.

Production requires:

- persistent Docker volume;
- startup health check;
- bounded connections/Hikari pools appropriate for VM capacity;
- Flyway migrations before rollout;
- documented `pg_dump` backup and restore procedure;
- no `flyway clean` in deployment automation.

### Kafka

Apache Kafka runs in KRaft mode on the VM. Kafka remains the durable asynchronous backbone and Transactional Outbox remains mandatory. The production deployment does not collapse event types merely to satisfy a third-party free-tier topic limit.

Kafka requires a persistent volume, bounded retention appropriate for a portfolio workload, explicit health verification, and no public broker port.

### Redis

Redis 7 runs on the VM for rate limiting, realtime fan-out, cache/coordination use cases already approved by the architecture. Redis is disposable supporting infrastructure and is never the authoritative source for reservation ownership, payment state, or ticket validity.

---

## 6. Three-Pillar Observability

SeatFlow keeps the engineering value of self-hosted observability while avoiding separate paid managed observability products:

```text
Spring Boot services
   |
   +--> structured JSON logs
   +--> Micrometer /actuator/prometheus
   +--> OTLP traces

OTel Collector ----> Tempo
Prometheus --------> service metrics
Grafana -----------> Prometheus + Tempo dashboards
```

Production logs must be structured JSON with correlation/trace context and sensitive-data masking. Container stdout/stderr uses bounded log rotation. Where configured, the GCP Ops Agent / Cloud Logging may ingest selected host/container logs, but Cloud Logging does not replace the local structured-log contract.

Required operational views include:

- RED metrics: request rate, errors, latency;
- JVM memory/GC and Hikari pool pressure;
- Kafka outbox publish latency/retries and consumer health;
- reservation conflicts/expiration;
- payment outcomes;
- ticket issuance;
- realtime active connections and Redis publish/consume failures;
- trace-to-log correlation.

Do not use high-cardinality identifiers such as `eventId`, `reservationId`, `ticketId`, or user IDs as Prometheus metric labels.

---

## 7. CI/CD Architecture

### 7.1 Pull Requests

PRs into `develop` or `main` run CI only:

```text
checkout
 -> backend Maven verify
 -> frontend lint/test/build
 -> Docker image build validation
 -> Docker Compose config validation
 -> Terraform fmt/validate
 -> security/static checks
```

### 7.2 `develop`

`develop` is the integration branch. To avoid paying for a second always-on stack, merging to `develop` does **not** create a permanent staging environment.

The staging workflow:

1. authenticates to GCP through WIF;
2. builds immutable images;
3. pushes `staging-${GITHUB_SHA}` images to Artifact Registry;
4. validates the production Compose rendering and Terraform configuration;
5. optionally supports an explicitly invoked temporary/smoke deployment on the single VM, but never runs a duplicate long-lived stack by default.

### 7.3 `main` / Release

A merge to `main` or approved semantic release deploys to the single production VM:

```text
GitHub Actions
   |
   | OIDC
   v
GCP Workload Identity Federation
   |
   +--> build/push immutable images -> Artifact Registry
   +--> read deployment metadata / approved secrets
   +--> remote deploy command through approved VM access path
                                      |
                                      v
                             Compute Engine VM
                                      |
                            docker compose pull
                            Flyway migration step
                            docker compose up -d
                            health + smoke checks
```

Deployment must preserve the previously running image tag/configuration so a failed release can be rolled back by restoring the previous Compose image set. Database migrations are forward-only/backwards compatible; automated rollback never performs destructive schema reversal.

There is no Cloud Run revision traffic split in the MVP deployment. Availability during a portfolio deployment is best-effort; correctness and reproducibility take precedence over implementing fake zero-downtime complexity.

---

## 8. Artifact Registry Contract

Every deployable image is independently built and pushed. Tags are immutable deployment selectors:

```text
<region>-docker.pkg.dev/<project>/seatflow/api-gateway:<git-sha>
<region>-docker.pkg.dev/<project>/seatflow/user-service:<git-sha>
...
<region>-docker.pkg.dev/<project>/seatflow/frontend:<git-sha>
```

Release-friendly aliases may exist, but production Compose must resolve to an immutable SHA/digest recorded in deployment metadata.

---

## 9. Terraform Scope

Terraform under `infra/terraform/` provisions **infrastructure**, not application source code.

Required MVP resources:

```text
GCP project service enablement
Artifact Registry repository
Compute Engine VM (default e2-highmem-2)
Persistent disk configuration
Static external IP
Firewall rules
VM runtime service account + IAM
GitHub Workload Identity Pool/Provider + deploy service account bindings
Secret Manager secret containers + IAM
Optional logging/monitoring agent configuration
Remote Terraform state bucket/bootstrap documentation
```

Terraform must **not** provision Cloud Run, Cloud SQL, Memorystore, managed Kafka, GKE, Cloud Armor, or an external HTTPS load balancer for the MVP.

Production state is stored in a versioned GCS backend outside source control. Real secret values are not Terraform variables/state. Use deletion protection / `prevent_destroy` where appropriate for persistent resources.

---

## 10. Security Requirements

- WIF/OIDC is the only GitHub-to-GCP credential mechanism; no long-lived service-account JSON key.
- Least-privilege deployment and VM service accounts.
- Secret values originate from Secret Manager or explicitly approved third-party secret stores.
- Only the public edge ports are Internet-accessible.
- PostgreSQL, Kafka, Redis, Eureka, Actuator, Prometheus, Tempo, and Grafana remain private unless temporarily exposed through a controlled admin path.
- Containers run as non-root where supported.
- Images are immutable, versioned, and built from pinned base/runtime versions.
- Production Compose and deployment scripts do not echo secrets.
- Stripe remains Test Mode for the portfolio MVP.

---

## 11. Cost & Scale Rationale

The Compute Engine topology is a deliberate architectural decision, not a limitation hidden from reviewers.

SeatFlow demonstrates microservices, Kafka, Redis, PostgreSQL, WebSockets, observability, Docker, CI/CD, IAM/WIF, Terraform, and GCP without paying for ten independent serverless runtimes plus managed SQL/Redis/Kafka. All application boundaries remain independently containerized, so future migration to GKE, Cloud Run, managed PostgreSQL, or managed Kafka does not require collapsing the domain architecture.

A future migration is justified only when a real requirement appears, such as:

- independent horizontal scaling;
- stronger availability/SLA requirements;
- managed backups/replication;
- operational burden exceeding the benefit of self-hosting;
- sustained traffic that no longer fits one VM.

---

## 12. Phase 10 Deployment Definition of Done

Phase 10 cloud/deployment work is complete when:

- [ ] all JVM services and Angular build into independent non-root images;
- [ ] local and production Compose configurations validate;
- [ ] the full stack runs on the GCP Compute Engine VM;
- [ ] only the HTTPS/WSS edge is publicly reachable;
- [ ] PostgreSQL/Kafka data survive container recreation;
- [ ] WIF authenticates GitHub Actions without JSON keys;
- [ ] images are stored in Artifact Registry with immutable tags;
- [ ] Terraform reproduces the VM/network/IAM/registry/secret infrastructure;
- [ ] production secrets are not committed or emitted in logs;
- [ ] Prometheus/Grafana/Tempo show metrics and traces from the deployed stack;
- [ ] deployment includes health/smoke verification and a documented image rollback procedure;
- [ ] no Cloud Run, Cloud SQL, Memorystore, managed Kafka, GKE, Cloud Armor, or dedicated load balancer resource is required for MVP completion.
