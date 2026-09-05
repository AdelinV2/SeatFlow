# Phase 14 — Admin Analytics & Operations Dashboard

**Status:** `PLANNED`  
**Architecture:** `.ai/architecture/09-post-mvp-evolution.md`  
**Related ADR:** `.ai/decisions/ADR-013-analytics-event-driven-read-model.md`  
**Estimated effort:** ~10–14 focused implementation hours  

---

## 1. Outcome

Add a dedicated event-driven analytics read model and a useful admin dashboard. This phase must demonstrate clean microservice boundaries: no cross-database SQL and no business write path depending on analytics availability.

## 2. New Analytics Service

Create `backend/services/analytics-service`:

- Spring Boot service, Eureka client, common modules;
- own PostgreSQL database `seatflow_analytics`;
- Kafka consumers for relevant domain events;
- idempotent projection updates;
- admin-only REST APIs;
- Actuator/Prometheus/OTel like other services;
- Docker/Compose/GCP integration using existing patterns.

Suggested default port: `8089`.

## 3. Projection Design

Create query-oriented tables rather than copying source-service schemas. Candidate projections:

- `daily_sales_metrics` by date/event/session;
- `event_session_metrics` with capacity/sold/refunded/scanned/reservation counts;
- `payment_metrics` success/failure/refund aggregates;
- `category_or_section_metrics` where events provide sufficient event payload data;
- processed-event deduplication table keyed by event envelope ID.

Do not store customer emails/names just to build aggregate analytics.

## 4. Event Inputs

Consume existing/current contracts and add payload fields only where justified:

- ReservationCreated/Expired/Confirmed or equivalent;
- PaymentCompleted/Failed/Refunded;
- TicketCreated/Revoked/Scanned;
- Event/Session lifecycle changes.

If historical backfill is needed for demo data, provide an explicit rebuild/backfill command or admin endpoint protected from normal public use. Do not query other databases directly.

## 5. Admin API

Provide ADMIN-only endpoints for:

- global KPI summary;
- revenue/sales time series;
- event/session table summaries;
- conversion and expiration rates;
- refund rate;
- scan/attendance count;
- top events/sessions;
- optional CSV aggregate export.

All endpoints support bounded date ranges and sensible pagination.

## 6. Frontend Dashboard

Extend Admin Portal with:

- KPI cards;
- line/bar charts using a lightweight Angular-compatible chart library or native SVG if already preferred;
- date range filter;
- event/session filters;
- empty/loading/error states;
- clear `Test Mode` label on revenue/payment numbers;
- responsive layout.

Grafana is not embedded as the business dashboard.

## 7. Resilience

Analytics consumer lag or downtime must not block checkout. Projection updates are eventually consistent. Duplicate Kafka events must not inflate counts.

## 8. Suggested Atomic Tasks

1. `001-analytics-service-scaffold-schema-and-compose.md`
2. `002-idempotent-kafka-projection-consumers.md`
3. `003-event-session-sales-and-payment-projections.md`
4. `004-admin-analytics-rest-contracts.md`
5. `005-admin-kpi-dashboard-and-charts.md`
6. `006-filters-csv-and-operational-states.md`
7. `007-projection-idempotency-and-integration-tests.md`

## 9. Definition of Done

- [ ] No analytics query crosses another service's database.
- [ ] Duplicate events do not double count.
- [ ] Checkout works if Analytics Service is down.
- [ ] Admin dashboard shows useful event/session business metrics.
- [ ] Revenue is clearly demo/Test Mode.
- [ ] New service participates in observability and deployment patterns.
