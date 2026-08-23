# 08 — Observability, CI/CD & Deployment Specification

This document details the local development setup, `.env` configuration architecture, observability stack (OpenTelemetry, Prometheus, Grafana), GitHub Actions CI/CD pipelines (Staging & Production), and Multi-Cloud deployment (Google Cloud Platform compute + Microsoft Azure Entra ID / CIAM).

---

## 1. Environment Variable Architecture (`.env`)

SeatFlow strictly isolates secrets and configuration across environments using standard `.env` patterns.

### 1.1 Local Development (`.env` & `.env.example`)
- Every microservice and frontend module maintains a version-controlled `.env.example` in its directory containing dummy/placeholder values and documentation for all required variables.
- Developers copy `.env.example` to `.env` locally.
- Real `.env` files are strictly added to `.gitignore` and **never committed to Git**.
- Spring Boot microservices read environment variables natively using property placeholders (e.g. `${DB_PASSWORD}`, `${STRIPE_API_KEY}`, `${AZURE_ENTRA_CLIENT_ID}`).

#### Directory Layout for Environment Files:
```text
SeatFlow/
├── .env.example                          # Root/Docker Compose infrastructure defaults
├── backend/
│   ├── services/
│   │   ├── user-service/.env.example
│   │   ├── seat-map-service/.env.example
│   │   ├── event-service/.env.example
│   │   ├── reservation-service/.env.example
│   │   ├── payment-service/.env.example
│   │   ├── ticket-service/.env.example
│   │   ├── realtime-service/.env.example
│   │   └── notification-service/.env.example
│   └── gateway/.env.example
└── frontend/.env.example
```

#### Example Microservice `.env.example` (`payment-service/.env.example`):
```properties
# Server & Eureka
SERVER_PORT=8085
EUREKA_SERVER_URL=http://localhost:8761/eureka

# Database
DB_HOST=localhost
DB_PORT=5432
DB_NAME=seatflow_payment
DB_USERNAME=postgres
DB_PASSWORD=postgres

# Kafka
KAFKA_BOOTSTRAP_SERVERS=localhost:9092

# Microsoft Entra ID (OIDC / JWT)
AZURE_ENTRA_ISSUER_URI=https://seatflow.ciamlogin.com/12345678-1234-1234-1234-123456789abc/v2.0
AZURE_ENTRA_CLIENT_ID=00000000-0000-0000-0000-000000000000

# Stripe Sandbox
STRIPE_API_KEY=sk_test_placeholder_key
STRIPE_WEBHOOK_SECRET=whsec_placeholder_secret
```

### 1.2 Cloud Environments (Staging & Production)
- **Zero `.env` files in Cloud containers:** Containers receive environment variables injected at runtime from **GCP Secret Manager** and **GCP Cloud Run / GKE environment specs**.
- **GitHub Actions Secrets:** Injected via GitHub Repository Environments (`staging` vs `production`).

---

## 2. Local Development Environment (Docker Compose)

The entire platform can be spun up locally using Docker Compose:

```text
docker/
├── docker-compose.yml              # Core infrastructure: Postgres, Kafka, Redis, Eureka, Gateway
├── docker-compose.services.yml     # Microservice backend containers (8 services)
└── docker-compose.monitoring.yml   # Prometheus, Grafana, OpenTelemetry Collector, Tempo
```

### 2.1 Complete Service & Port Catalog

| Category | Service Name | Internal Port | Host Port | Database / Storage |
|---|---|---|---|---|
| **Infrastructure** | PostgreSQL 16 (Multi-DB) | `5432` | `5432` | Volume `pg_data` |
| **Infrastructure** | Apache Kafka (KRaft mode) | `9092` | `9092` | Volume `kafka_data` |
| **Infrastructure** | Redis 7 | `6379` | `6379` | Non-persistent / Cache |
| **Infrastructure** | Eureka Discovery Server | `8761` | `8761` | In-memory registry |
| **Infrastructure** | API Gateway | `8080` | `8080` | Spring Cloud Gateway |
| **Backend Service** | `user-service` | `8081` | `8081` | DB: `seatflow_user` |
| **Backend Service** | `seat-map-service` | `8082` | `8082` | DB: `seatflow_seatmap` |
| **Backend Service** | `event-service` | `8083` | `8083` | DB: `seatflow_event` |
| **Backend Service** | `reservation-service` | `8084` | `8084` | DB: `seatflow_reservation` |
| **Backend Service** | `payment-service` | `8085` | `8085` | DB: `seatflow_payment` |
| **Backend Service** | `ticket-service` | `8086` | `8086` | DB: `seatflow_ticket` |
| **Backend Service** | `realtime-service` | `8087` | `8087` | Redis Pub/Sub |
| **Backend Service** | `notification-service` | `8088` | `8088` | DB: `seatflow_notification` |
| **Frontend** | Angular 22 SPA | `4200` (Dev) / `80` (Nginx) | `4200` | N/A |
| **Observability** | OpenTelemetry Collector | `4317` (gRPC) / `4318` (HTTP) | `4317` / `4318` | In-memory collector |
| **Observability** | Prometheus | `9090` | `9090` | Time-series data |
| **Observability** | Grafana | `3000` | `3000` | Pre-provisioned dashboards |
| **Observability** | Grafana Tempo (Tracing) | `3200` | `3200` | Distributed traces |

---

## 3. Observability & Monitoring

```text
Microservices (Micrometer + OpenTelemetry Agent)
    │
    ├── Traces (W3C TraceContext / OTLP) ─────────► OpenTelemetry Collector ──► Tempo / GCP Cloud Trace
    ├── Metrics (Prometheus Actuator Endpoints) ──► Prometheus ──────────────► Grafana Dashboards
    └── Logs (Structured JSON + MDC) ────────────► OpenTelemetry Collector ──► GCP Cloud Logging
```

### 3.1 Structured Logging Standards
- Format: JSON via `logstash-logback-encoder`.
- MDC Context: Every log entry includes `traceId`, `correlationId`, `serviceName`, `userId`, `reservationId`.
- Sensitive Data Masking: Payment card numbers, Stripe secrets, and JWT tokens are masked automatically.

### 3.2 Key Business Metrics
- `seatflow.reservations.created.total{eventId, status}` — Counter of hold creations.
- `seatflow.reservations.conflicts.total{eventId}` — Counter of hold double-booking rejections.
- `seatflow.reservations.expired.total{eventId}` — Counter of holds released by sweeper.
- `seatflow.payments.processed.total{status, currency}` — Counter of payment intents finalized.
- `seatflow.tickets.issued.total{eventId}` — Counter of generated digital tickets.

---

## 4. Multi-Cloud CI/CD Architecture (GitHub Actions)

### 4.1 Branching Model & Environments

```
┌──────────────────────────────────────────────────────────────────────────┐
│                            FEATURE BRANCHES                              │
│         feat/<task-id>-<desc>   fix/<issue-name>   docs/<topic>          │
└────────────────────────────────────┬─────────────────────────────────────┘
                                     │ (PR + Automated CI Matrix Validation)
                                     ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                        BRANCH `develop` (Staging)                        │
│  • Auto-deploy pe GCP Staging (Cloud Run / GKE Staging Cluster)          │
│  • Conectat la:                                                          │
│    - Azure Entra ID (Staging/Sandbox Tenant)                             │
│    - Cloud SQL PostgreSQL (Staging Instance)                             │
│    - Stripe Sandbox / Test Keys                                          │
└────────────────────────────────────┬─────────────────────────────────────┘
                                     │ (Release PR / Tagged Release v1.x.x)
                                     ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                         BRANCH `main` (Production)                       │
│  • Protected Branch: Zero direct pushes, necesită PR + Green Checks      │
│  • Auto-deploy pe GCP Production cu Zero-Downtime Traffic Migration      │
│  • Conectat la:                                                          │
│    - Azure Entra ID (Production CIAM Tenant)                             │
│    - Cloud SQL PostgreSQL (High Availability Multi-Zone)                 │
│    - Stripe Live/Production Keys                                         │
└──────────────────────────────────────────────────────────────────────────┘
```

### 4.2 GitHub Actions Workflows

| Workflow File | Trigger | Actions & Verification |
|---|---|---|
| **`.github/workflows/ci-pr-check.yml`** | PR against `develop` or `main` | 1. Matrix build: Java 21 (Temurin) & Node 22.<br>2. Backend: `mvn clean verify` (Unit tests, `@DataJpaTest`, Testcontainers integration & concurrency tests).<br>3. Frontend: `npm run lint` & `npm run test -- --watch=false --browsers=ChromeHeadless`.<br>4. Docker build dry-run (Jib / Docker Buildx). |
| **`.github/workflows/cd-staging.yml`** | Push / Merge to `develop` | 1. Passwordless authentication to GCP via **Workload Identity Federation (WIF)**.<br>2. Build & Push Docker images to Google Artifact Registry tagged `:staging-${GITHUB_SHA}`.<br>3. Run Flyway database migrations on GCP Cloud SQL Staging.<br>4. Deploy services to GCP Cloud Run (Staging Environment) with Staging Azure Entra ID and Stripe Sandbox secrets. |
| **`.github/workflows/cd-production.yml`** | Push / Merge to `main` or Tag `v*.*.*` | 1. Environment Gate: Requires Manual Approval from designated reviewers.<br>2. Authenticate to GCP Production via WIF.<br>3. Build & Tag production images `:latest` and `:vX.Y.Z`.<br>4. Run Flyway migrations on GCP Cloud SQL Production.<br>5. Deploy to GCP Cloud Run Production with gradual canary traffic split (10% -> 50% -> 100%). |

### 4.3 Workload Identity Federation (WIF) Security Standard
To prevent storing long-lived cloud credentials in GitHub, CI/CD uses **OIDC token exchange**:
- GitHub Actions requests an OIDC token from `token.actions.githubusercontent.com`.
- Google Cloud Workload Identity Pool validates the token against the repository (`AdelinV2/SeatFlow`).
- GCP grants short-lived IAM credentials to impersonate the deployment Service Account.

---

## 5. Multi-Cloud Production Architecture

### 5.1 Google Cloud Platform (Compute, Data, Storage & Networking)
- **Compute:** Google Cloud Run (Serverless microservices with horizontal autoscaling).
- **Database:** Google Cloud SQL for PostgreSQL 16 (High Availability multi-zone failover, automated point-in-time recovery).
- **Caching:** Google Cloud Memorystore for Redis.
- **Messaging:** Managed Apache Kafka on GCP / Confluent Cloud.
- **File Storage:** Google Cloud Storage (PDF ticket attachments, venue layout SVGs).
- **Secrets Management:** Google Cloud Secret Manager (mounted into Cloud Run containers as environment variables at startup).
- **Networking & Edge:** Cloud Load Balancing + Cloud Armor (DDoS protection, rate limiting) + Cloud CDN.

### 5.2 Microsoft Azure (Identity & Access Management)
- **Identity Provider:** Microsoft Entra External ID (CIAM).
- **Federation:** Google OAuth & Email/Password customer accounts.
- **Token Verification:** Microservices validate JWT signatures against Microsoft's public JWKS endpoint (`https://seatflow.ciamlogin.com/.../discovery/v2.0/keys`).
- **Role Enforcement:** Custom security attributes mapped into `roles` claim and converted via `JwtRoleConverter` into Spring Security authorities.

