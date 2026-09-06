# TASK-P14-001: Scaffold Analytics Service, Read-Model Schema, and Runtime Integration

## 1. Task Metadata

- **Task ID:** `TASK-P14-001`
- **Git Branch:** `feat/p14-001-analytics-scaffold`
- **Target Module:** `backend/services/analytics-service`, backend service aggregation, API Gateway, Docker/runtime database wiring
- **Phase:** `Phase 14 - Admin Analytics & Operations Dashboard`
- **Related Specs:** `.ai/tasks/phase-14-admin-analytics/000-phase-overview.md`, `.ai/architecture/09-post-mvp-evolution.md`
- **Related ADRs:** `.ai/decisions/ADR-013-analytics-event-driven-read-model.md`
- **Status:** `READY FOR IMPLEMENTATION`

### Orchestration Metadata

- **Complexity:** `5`
- **Failure Risk:** `High`
- **Verification Strength:** `Strong`
- **Required Review Depth:** `Critical`
- **Preferred Workflow:** `critical`
- **Affected Critical Invariants:** `database-per-service isolation; analytics/write-path decoupling; additive migrations; admin-only boundary; observability; no PII replication`

---

## 2. Objective

Create the dedicated `analytics-service` skeleton on port `8089`, give it its own PostgreSQL database `seatflow_analytics`, define the durable event-derived read-model schema, and integrate the service into SeatFlow's existing Maven, Eureka, gateway, Docker, migration, health, and observability patterns.

This task establishes infrastructure and persistence only. It must **not** implement business-event projection semantics, public analytics queries, CSV export, or frontend dashboard behavior; those belong to P14-002 through P14-006.

The service is a disposable/rebuildable read model derived from durable domain events. It is never a source of truth for reservation, payment, ticket, event, or seat state.

---

## 3. Critical Invariants & Failure Modes

### 3.1 Invariants

- [ ] `analytics-service` owns only `seatflow_analytics`; it must never connect to another service database.
- [ ] No reservation/payment/ticket/event write path makes an HTTP, database, or blocking Kafka dependency on analytics.
- [ ] No cross-database SQL, foreign-data wrapper, database link, shared JPA entity, or direct schema read is introduced.
- [ ] Analytics identifiers such as `event_id`, `event_session_id`, `reservation_id`, `payment_id`, and `ticket_id` are opaque correlation IDs, not database foreign keys to another service.
- [ ] Read-model storage contains no customer email, name, address, payment method details, JWT claims, or other PII merely for analytics.
- [ ] Monetary values use integral **minor units** (`BIGINT`/Java `long`) plus ISO-style 3-letter currency; floating point is forbidden for money.
- [ ] Different currencies are never combined into one total.
- [ ] All source timestamps are stored as offset/UTC-capable timestamps (`TIMESTAMPTZ`); daily analytics buckets use the UTC calendar date unless a future product ADR explicitly changes that convention.
- [ ] `processed_events` is created here as the idempotency boundary, but event processing behavior is implemented in P14-002.
- [ ] Flyway owns schema creation/evolution. No Hibernate `create`/`update` schema generation in shared, Docker, or production profiles.
- [ ] The service exposes standard health/metrics/tracing and registers with Eureka like existing SeatFlow services.
- [ ] `/api/admin/analytics/**` is routed only to `analytics-service`; downstream service authorization remains authoritative and is implemented/verified before P14-004 is complete.
- [ ] Stripe-derived financial data is analytics over **Stripe Test Mode / Demo** transactions and must retain that semantic in later API/UI contracts.

### 3.2 Primary Failure Modes to Prevent

- accidental query from analytics directly into `seatflow_payment` or `seatflow_reservation`;
- business-service startup or checkout failure when analytics is unavailable;
- duplicate-count-prone schema with no durable event identity;
- precision loss from `double`/`DECIMAL` conversions of minor-unit money;
- totals that silently mix RON/EUR/USD;
- PII copied into a dashboard read model;
- mutable operational rows treated as analytics source of truth;
- container starts locally but is absent from database bootstrap, production compose, migration scripts, or release verification;
- gateway route collision with existing `/api/admin/**` service routes;
- schema that assumes Kafka delivery ordering across topics.

---

## 4. Dependencies / Prerequisites

- Phase 12 and Phase 13 task contracts must be treated as the target upstream model. P14 implementation must not invent substitute session/refund semantics while those phases are incomplete.
- Reuse current SeatFlow patterns from:
  - `backend/services/notification-service` for a stateful Kafka-capable Spring Boot service;
  - `backend/services/api-gateway` for explicit route registration;
  - `docker/docker-compose.services.yml` and `docker/docker-compose.prod.yml` for runtime wiring;
  - `docker/init-db/01-init-multiple-dbs.sql` for local database creation;
  - `infra/scripts/run-production-migrations.sh` and `infra/scripts/verify-compose-release.sh` for release integration.
- Before editing, inventory any deployment files added after this task was authored by searching for `notification-service` and apply the same service-list pattern to `analytics-service` where applicable.

---

## 5. Exact File Inventory

### 5.1 Required new analytics-service files

- `[NEW]` `backend/services/analytics-service/pom.xml`
- `[NEW]` `backend/services/analytics-service/Dockerfile`
- `[NEW]` `backend/services/analytics-service/.env.example`
- `[NEW]` `backend/services/analytics-service/src/main/java/com/seatflow/analytics/AnalyticsServiceApplication.java`
- `[NEW]` `backend/services/analytics-service/src/main/java/com/seatflow/analytics/config/SecurityConfig.java`
- `[NEW]` `backend/services/analytics-service/src/main/resources/application.yaml`
- `[NEW]` `backend/services/analytics-service/src/main/resources/application-local.yaml`
- `[NEW]` `backend/services/analytics-service/src/main/resources/application-docker.yaml`
- `[NEW]` `backend/services/analytics-service/src/main/resources/application-prod.yaml`
- `[NEW]` `backend/services/analytics-service/src/main/resources/application-test.yaml`
- `[NEW]` `backend/services/analytics-service/src/main/resources/logback-spring.xml` or the exact shared logging pattern used by sibling services
- `[NEW]` `backend/services/analytics-service/src/main/resources/db/migration/V1__create_analytics_read_model.sql`
- `[NEW]` `backend/services/analytics-service/src/test/java/com/seatflow/analytics/AnalyticsServiceApplicationTests.java`

### 5.2 Required existing integration points

- `[MODIFY]` `backend/services/pom.xml` — add `<module>analytics-service</module>`.
- `[MODIFY]` `backend/services/api-gateway/src/main/java/com/seatflow/gateway/config/GatewayRoutesConfig.java` — explicit route for `/api/admin/analytics/**` -> `lb://analytics-service`.
- `[MODIFY]` `backend/services/api-gateway/src/test/java/com/seatflow/gateway/RouteConfigurationTest.java` — route coverage.
- `[MODIFY]` `docker/init-db/01-init-multiple-dbs.sql` — create `seatflow_analytics` idempotently.
- `[MODIFY]` `docker/docker-compose.services.yml` — add `analytics-service` on `8089`.
- `[MODIFY]` `docker/docker-compose.prod.yml` — add production-equivalent service wiring.
- `[MODIFY]` `docker/README.md` — document the additional database/service count and port.
- `[MODIFY]` `infra/scripts/run-production-migrations.sh` — include analytics database migration mapping.
- `[MODIFY]` `infra/scripts/verify-compose-release.sh` — require analytics in the release topology.

If current deployment/IaC files enumerate every service, update those exact lists too. Do **not** create a new deployment mechanism only for analytics.

---

## 6. Technical Specifications & Contracts

### 6.1 Maven / Spring Boot Module

Mirror the dependency versions and parent hierarchy of existing services. At minimum the service needs the project-standard equivalents for:

- Spring Boot Web;
- Validation;
- Spring Data JPA;
- PostgreSQL;
- Flyway;
- Spring Kafka;
- OAuth2 Resource Server / shared security module;
- Eureka client;
- Actuator + Micrometer/Prometheus;
- OpenTelemetry instrumentation already used by sibling services;
- `common-events`, `common-security`, and `common-exception` only where their APIs are actually required.

Do not duplicate shared JWT role conversion or event-envelope definitions inside analytics-service.

### 6.2 Application Identity and Port

```text
spring.application.name = analytics-service
server.port = 8089
DB_NAME = seatflow_analytics
```

Local, Docker, test, and production profiles must follow the same datasource, Eureka, Kafka, JWT, actuator, tracing, pool-size, and environment-variable conventions as sibling services.

For a brand-new analytics consumer group, use `auto-offset-reset=earliest` so available retained history can seed the read model. Offset/consumer details are finalized in P14-002; do not enable auto-commit as a shortcut.

### 6.3 V1 Read-Model Schema

`V1__create_analytics_read_model.sql` must create the following analytics-owned tables. Column names may be adapted to existing SQL naming conventions, but semantics must remain exact.

#### `processed_events`

Purpose: durable EventEnvelope deduplication.

Required columns:

```text
event_id              VARCHAR(128) PRIMARY KEY
event_type            VARCHAR(128) NOT NULL
source_topic           VARCHAR(255) NOT NULL
source_partition       INTEGER NULL
source_offset          BIGINT NULL
occurred_at            TIMESTAMPTZ NOT NULL
processed_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
```

Required indexes: `occurred_at`, `processed_at`, and optionally `(event_type, occurred_at)` if justified by operational queries.

#### `analytics_session_facts`

One analytics snapshot per event session, populated only from events in later tasks.

```text
event_session_id       UUID PRIMARY KEY
event_id               UUID NOT NULL
venue_id               UUID NULL
event_title             VARCHAR(255) NULL
session_label           VARCHAR(255) NULL
starts_at               TIMESTAMPTZ NULL
ends_at                 TIMESTAMPTZ NULL
status                  VARCHAR(64) NULL
capacity_snapshot       INTEGER NULL
last_source_event_at    TIMESTAMPTZ NOT NULL
updated_at              TIMESTAMPTZ NOT NULL
```

Rules:
- `capacity_snapshot >= 0` when present.
- `capacity_snapshot` is nullable. Never synthesize capacity from sold count or a mutable unrelated table.
- Event/session labels are display snapshots only, not source-of-truth catalog fields.

#### `analytics_reservation_facts`

One row per reservation, no PII.

```text
reservation_id          UUID PRIMARY KEY
event_id                UUID NOT NULL
event_session_id        UUID NOT NULL
created_at              TIMESTAMPTZ NOT NULL
confirmed_at            TIMESTAMPTZ NULL
expired_at              TIMESTAMPTZ NULL
refunded_at             TIMESTAMPTZ NULL
seat_count              INTEGER NOT NULL
currency                VARCHAR(3) NULL
quoted_total_minor      BIGINT NULL
last_source_event_at    TIMESTAMPTZ NOT NULL
updated_at              TIMESTAMPTZ NOT NULL
```

Rules:
- `seat_count > 0`.
- `quoted_total_minor >= 0` when present.
- This is a correlation/read-model fact, not an authoritative reservation replica.

#### `analytics_payment_facts`

One row per payment identity. It may exist before its reservation fact because Kafka topics have no global ordering.

```text
payment_id               UUID PRIMARY KEY
reservation_id           UUID NOT NULL
event_session_id         UUID NULL
status                   VARCHAR(64) NOT NULL
currency                 VARCHAR(3) NOT NULL
completed_amount_minor   BIGINT NOT NULL DEFAULT 0
refunded_amount_minor    BIGINT NOT NULL DEFAULT 0
completed_at             TIMESTAMPTZ NULL
failed_at                TIMESTAMPTZ NULL
refunded_at              TIMESTAMPTZ NULL
last_source_event_at     TIMESTAMPTZ NOT NULL
updated_at               TIMESTAMPTZ NOT NULL
```

Rules:
- money columns `>= 0`;
- `refunded_amount_minor <= completed_amount_minor` once both are known;
- do not store card/customer/Stripe-secret data;
- Stripe provider IDs are unnecessary unless a later debugging requirement is explicitly approved.

#### `analytics_ticket_facts`

One row per ticket. It may temporarily lack reservation/session correlation if a scan event arrives before the issue event.

```text
ticket_id                UUID PRIMARY KEY
reservation_id           UUID NULL
event_session_id         UUID NULL
issued_at                TIMESTAMPTZ NULL
revoked_at               TIMESTAMPTZ NULL
first_scanned_at         TIMESTAMPTZ NULL
status                   VARCHAR(64) NOT NULL
last_source_event_at     TIMESTAMPTZ NOT NULL
updated_at               TIMESTAMPTZ NOT NULL
```

A ticket contributes at most one attendance unit regardless of repeated scans.

#### `daily_sales_metrics`

Aggregate row by UTC date + event + session + currency.

```text
metric_date                 DATE NOT NULL
event_id                    UUID NOT NULL
event_session_id            UUID NOT NULL
currency                    VARCHAR(3) NOT NULL
reservations_created        BIGINT NOT NULL DEFAULT 0
reservations_confirmed      BIGINT NOT NULL DEFAULT 0
reservations_expired        BIGINT NOT NULL DEFAULT 0
payments_succeeded          BIGINT NOT NULL DEFAULT 0
payment_failures            BIGINT NOT NULL DEFAULT 0
refunds_completed           BIGINT NOT NULL DEFAULT 0
tickets_issued              BIGINT NOT NULL DEFAULT 0
tickets_revoked             BIGINT NOT NULL DEFAULT 0
tickets_scanned             BIGINT NOT NULL DEFAULT 0
gross_revenue_minor         BIGINT NOT NULL DEFAULT 0
refunded_revenue_minor      BIGINT NOT NULL DEFAULT 0
updated_at                  TIMESTAMPTZ NOT NULL
PRIMARY KEY (metric_date, event_id, event_session_id, currency)
```

All counters and amounts have `CHECK >= 0` constraints.

#### `event_session_metrics`

Lifetime/current aggregate per session + currency, rebuilt from analytics facts.

```text
event_session_id            UUID NOT NULL
event_id                    UUID NOT NULL
currency                    VARCHAR(3) NOT NULL
capacity_snapshot            INTEGER NULL
reservations_created        BIGINT NOT NULL DEFAULT 0
reservations_confirmed      BIGINT NOT NULL DEFAULT 0
reservations_expired        BIGINT NOT NULL DEFAULT 0
payments_succeeded          BIGINT NOT NULL DEFAULT 0
payment_failures            BIGINT NOT NULL DEFAULT 0
refunds_completed           BIGINT NOT NULL DEFAULT 0
tickets_issued              BIGINT NOT NULL DEFAULT 0
tickets_revoked             BIGINT NOT NULL DEFAULT 0
tickets_scanned             BIGINT NOT NULL DEFAULT 0
gross_revenue_minor         BIGINT NOT NULL DEFAULT 0
refunded_revenue_minor      BIGINT NOT NULL DEFAULT 0
last_projected_event_at     TIMESTAMPTZ NULL
updated_at                  TIMESTAMPTZ NOT NULL
PRIMARY KEY (event_session_id, currency)
```

Never materialize a single mixed-currency row.

### 6.4 Schema Design Rules

- Do not add foreign keys to IDs owned by another service.
- Local relationships between analytics tables may use indexes rather than FK constraints when out-of-order delivery requires temporarily incomplete correlation.
- Add indexes for `event_id`, `event_session_id`, `reservation_id`, `metric_date`, `starts_at`, and query/filter combinations justified by P14-004.
- Use additive Flyway migrations after V1. Never edit an applied migration once merged/deployed.
- Category/section metrics are deliberately **not** in V1. Add them later only if final P12/P13 event payloads contain stable, non-PII dimensions; never infer them by cross-service lookup.

### 6.5 Gateway and Security Boundary

Add an explicit API Gateway route:

```text
/api/admin/analytics/** -> lb://analytics-service
```

Place it so it cannot be swallowed by another service's `/api/admin/...` matcher. Add route tests.

Create analytics-service security with the repository's shared JWT converter. Health/info behavior should follow sibling services. Any `/api/admin/analytics/**` handler introduced now or later must require `ROLE_ADMIN`; P14-004 adds endpoint-level tests.

### 6.6 Runtime / Compose

Add `analytics-service` with:

- container name `seatflow-analytics`;
- port `${ANALYTICS_SERVICE_PORT:-8089}:8089` locally;
- `DB_NAME=seatflow_analytics`;
- Kafka/Eureka/JWT/OpenTelemetry environment variables matching sibling services;
- PostgreSQL, Kafka, Eureka dependencies;
- actuator healthcheck;
- project network and log rotation matching other services;
- reasonable JVM memory consistent with other read-heavy services.

Analytics being unhealthy must **not** make any business service unhealthy through `depends_on` or network calls.

---

## 7. Step-by-Step Implementation Sequence

1. Inventory current stateful service/bootstrap patterns and note any repo changes since task authoring.
2. Add the Maven module and minimal application class/configuration.
3. Add V1 schema exactly as an analytics-owned read model; validate constraints and indexes in PostgreSQL.
4. Configure local/docker/prod/test profiles without schema auto-generation.
5. Add Dockerfile and environment template.
6. Add `seatflow_analytics` to local DB bootstrap and production migration mapping.
7. Add analytics-service to service/prod Compose and release verification lists.
8. Add the explicit API Gateway route and route test.
9. Add health/Prometheus/OTel/Eureka integration using existing SeatFlow conventions.
10. Prove that the service boots with an empty DB and that existing services boot/run with analytics stopped.
11. Run backend/gateway tests and Compose validation.

---

## 8. Test Requirements

### 8.1 Unit / Application Context

- [ ] Analytics application context starts under test profile.
- [ ] Security configuration loads shared JWT role conversion rather than a local duplicate.
- [ ] No Hibernate DDL mutation is enabled.

### 8.2 Database / Flyway

Using PostgreSQL/Testcontainers, not H2:

- [ ] V1 migrates successfully on an empty database.
- [ ] Rerunning Flyway is a no-op.
- [ ] duplicate `processed_events.event_id` is rejected.
- [ ] negative counters/amounts are rejected by schema constraints.
- [ ] `capacity_snapshot` accepts `NULL` but rejects negative values.
- [ ] two currencies can coexist for the same session/date without a uniqueness collision.
- [ ] analytics tables contain no PII columns.

### 8.3 Gateway / Runtime

- [ ] `/api/admin/analytics/**` resolves to `lb://analytics-service` in route configuration tests.
- [ ] existing gateway routes still resolve to their original services.
- [ ] Compose config validates with `analytics-service` and `seatflow_analytics` present.
- [ ] analytics actuator health is reachable according to existing policy.
- [ ] stopping analytics does not make reservation/payment/ticket services depend on it or fail health solely because it is absent.

---

## 9. Verification Commands

```bash
cd backend
./mvnw -pl services/analytics-service,services/api-gateway -am test

cd ../docker
docker compose -f docker-compose.yml -f docker-compose.services.yml config

docker compose -f docker-compose.prod.yml config

cd ../
bash infra/scripts/verify-compose-release.sh
```

Also run the repository's current production-migration verification command if the script exposes a dry-run/validation mode.

---

## 10. Independent Review Focus

The reviewer must specifically inspect:

- DB-per-service isolation and absence of cross-database access;
- schema ability to tolerate out-of-order event correlation;
- money/currency safety;
- no PII replication;
- Flyway immutability/additivity;
- gateway route precedence;
- runtime topology completeness;
- absence of any business-service dependency on analytics;
- parity with current logging/metrics/tracing/security conventions.

---

## 11. Acceptance Criteria

- [ ] `analytics-service` is a first-class Maven/Spring Boot service on `8089` with DB `seatflow_analytics`.
- [ ] V1 read-model schema and constraints are present and PostgreSQL-tested.
- [ ] Analytics has no access to another service's database.
- [ ] Gateway route exists without regressing existing routes.
- [ ] Docker/local/prod/migration/release lists include analytics consistently.
- [ ] Standard health, metrics, tracing, Eureka, JWT plumbing matches sibling services.
- [ ] No business write path depends on analytics availability.
- [ ] Focused tests and critical independent review pass.

---

## 12. Execution Entry Point

```text
Implement TASK-P14-001 using the SeatFlow autonomous orchestration workflow.
Before writing code, re-read Phase 14 overview and ADR-013 and inventory current runtime/deployment patterns. Do not implement P14-002 projection semantics in this task.
```
