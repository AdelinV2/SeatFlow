# 08 — Observability, CI/CD & Deployment Specification

This document details the local development setup, observability architecture (OpenTelemetry, Prometheus, Grafana), CI/CD automation with GitHub Actions, and Google Cloud production deployment.

---

## 1. Local Development Environment (Docker Compose)

The entire platform can be spun up locally using Docker Compose:

```text
docker/
├── docker-compose.yml              # Core infrastructure: Postgres, Kafka, Redis, Eureka, Gateway
├── docker-compose.services.yml     # Microservice backend containers
└── docker-compose.monitoring.yml   # Prometheus, Grafana, OpenTelemetry Collector
```

### Infrastructure Services
- **PostgreSQL 16-alpine:** Port `5432` (Multiple databases created via init script).
- **Apache Kafka (Confluent cp-kafka):** Port `9092` with KRaft mode.
- **Redis 7-alpine:** Port `6379`.
- **Eureka Server:** Port `8761`.
- **API Gateway:** Port `8080`.
- **Prometheus:** Port `9090`.
- **Grafana:** Port `3000` (Pre-provisioned dashboards).

---

## 2. Observability & Monitoring

```text
Microservices (Micrometer + OpenTelemetry Agent)
    │
    ├── Traces (W3C TraceContext / OTLP) ─────────► OpenTelemetry Collector ──► Cloud Trace / Tempo
    ├── Metrics (Prometheus Actuator Endpoints) ──► Prometheus ──────────────► Grafana Dashboards
    └── Logs (Structured JSON + MDC) ────────────► Centralized Cloud Logging
```

### 2.1 Structured Logging Standards
- Format: JSON via `logstash-logback-encoder`.
- MDC Context: Every log entry includes `traceId`, `correlationId`, `serviceName`, `userId`, `reservationId`.
- Sensitive Data Masking: Payment card numbers, Stripe secrets, and JWT tokens are masked automatically.

### 2.2 Business Metrics (Micrometer / Prometheus)
- `seatflow.reservations.created.total{eventId, status}` — Counter of hold creations.
- `seatflow.reservations.conflicts.total{eventId}` — Counter of hold double-booking rejections.
- `seatflow.reservations.expired.total{eventId}` — Counter of holds released by sweeper.
- `seatflow.payments.processed.total{status, currency}` — Counter of payment intents finalized.
- `seatflow.tickets.issued.total{eventId}` — Counter of generated digital tickets.

---

## 3. CI/CD Automation (GitHub Actions)

### 3.1 Pull Request & Validation Pipeline (`.github/workflows/ci.yml`)
- Trigger: Every PR against `main`.
- Steps:
  1. Checkout repository.
  2. Setup Java 21 (Temurin) with Maven caching.
  3. Setup Node 22 with npm caching.
  4. Run `mvn clean verify` on `backend/` (Executes all Unit tests, MapStruct checks, `@DataJpaTest`, and Testcontainers integration tests).
  5. Run `npm run lint` and `npm run test -- --watch=false --browsers=ChromeHeadless` on `frontend/`.
  6. Static code analysis via SonarCloud.

### 3.2 Deployment Pipeline (`.github/workflows/cd.yml`)
- Trigger: Merge into `main`.
- Steps:
  1. Build multi-arch Docker images with Jib or Docker Buildx.
  2. Push images to Google Artifact Registry (`pkg.dev/...`).
  3. Deploy services to Google Cloud (Cloud Run / GKE) with rolling updates.
  4. Run Flyway database migrations before service rollout.

---

## 4. Production Cloud Architecture (Google Cloud)

- **Compute:** Google Cloud Run (Serverless containers) or Google Kubernetes Engine (GKE).
- **Database:** Google Cloud SQL for PostgreSQL 16 (High Availability, Automated Backups).
- **Caching:** Google Cloud Memorystore for Redis.
- **Messaging:** Confluent Cloud on GCP or Managed Kafka.
- **Secrets:** Google Cloud Secret Manager (mounted into containers at runtime).
- **DNS & CDN:** Google Cloud Load Balancing with Cloud Armor WAF and Cloud CDN.
