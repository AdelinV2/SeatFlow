# Phase 14 — Admin Analytics & Operations Dashboard

**Status:** `PLANNED`  
**Architecture:** `.ai/architecture/09-post-mvp-evolution.md`  
**Related ADR:** `.ai/decisions/ADR-013-analytics-event-driven-read-model.md`  
**Estimated effort:** ~10–14 focused implementation hours  

---

## 1. Outcome

Add a dedicated event-driven analytics read model and a portfolio-grade admin dashboard while preserving SeatFlow's microservice boundaries.

Hard phase invariants:

- no cross-database SQL or direct operational-schema reads;
- no reservation/payment/ticket/event write path depends on analytics availability;
- analytics is rebuilt from durable domain events and is eventually consistent;
- duplicate/replayed Kafka events cannot inflate projections;
- ADMIN authorization is enforced server-side;
- customer PII is not copied merely for analytics;
- financial values remain integer minor units, currency-separated, and visibly Stripe Test Mode / Demo data.

## 2. New Analytics Service

Create `backend/services/analytics-service`:

- Spring Boot service and Eureka client on port `8089`;
- own PostgreSQL database `seatflow_analytics`;
- Kafka consumers over canonical reservation/payment/ticket/event topic families;
- durable `EventEnvelope.eventId` deduplication;
- query-oriented analytics facts and aggregates;
- ADMIN-only REST APIs;
- Actuator/Prometheus/OpenTelemetry/logging consistent with existing services;
- Docker/Compose/release integration using existing SeatFlow patterns.

## 3. Canonical Projection Design

Do not copy source-service schemas. Phase 14 uses analytics-owned event-derived facts plus two deliberately different aggregate grains.

### 3.1 Event-derived facts

- `analytics_session_facts`
- `analytics_reservation_facts`
- `analytics_payment_facts`
- `analytics_ticket_facts`
- `processed_events` keyed by canonical event-envelope ID

Additional additive fact tables may be introduced only when the **final implemented** Phase 12/13 event shape requires safe delayed correlation, for example a reservation-scoped ticket-revocation fact.

### 3.2 Currency-neutral operational aggregates

- `daily_operational_metrics` keyed by `(metric_date, event_id, event_session_id)`
- `event_session_metrics` keyed by `event_session_id`

Reservation/ticket/session counts are not keyed by currency. This prevents one session with RON + EUR financial activity from duplicating the same operational counts.

### 3.3 Currency-keyed financial aggregates

- `daily_revenue_metrics` keyed by `(metric_date, event_id, event_session_id, currency)`
- `event_session_revenue_metrics` keyed by `(event_session_id, currency)`

Gross/refunded/net revenue is never summed across currencies. Net is derived from exact minor-unit gross minus completed-refund amounts.

Category/section analytics is out of the initial Phase 14 schema unless final canonical events already expose a stable non-PII dimension. Never add a source-service lookup merely to obtain it.

## 4. Event Inputs and Dependency Gate

P14-002/P14-003 must consume the **final implemented** Phase 12/13 contracts rather than assuming roadmap candidate names are exact.

Required semantic families include:

- reservation created/held, confirmed, expired, refunded;
- payment completed, failed, refunded;
- ticket issued, revoked, accepted scan;
- event-session lifecycle metadata.

Before projection implementation, audit final producer class -> `EventEnvelope.eventType` -> topic -> analytics fields.

Rules:

- reuse canonical events rather than creating analytics-only duplicates;
- add an upstream payload field only when the producer owns/trusts it and no safe analytics-side event-derived correlation path exists;
- preserve transactional-outbox publication;
- do not add synchronous analytics callbacks to business services.

## 5. Kafka Processing Contract

The analytics consumer group is stable (`analytics-service-v1`) and follows the repository's existing consumer conventions:

- `auto.offset.reset=earliest` for a new read model;
- auto-commit disabled;
- `read_committed` isolation;
- RECORD acknowledgement;
- atomic PostgreSQL `processed_events` claim + projection in one local transaction;
- bounded retry and analytics-specific DLQ for known failures;
- unknown non-relevant shared-topic events are ignored safely;
- cross-topic ordering is never assumed.

Crash after DB commit but before durable Kafka offset progression is safe because redelivery sees the processed event ID and becomes a no-op.

## 6. Admin API

Provide ADMIN-only endpoints for:

- global KPI summary;
- daily revenue/count time series;
- paginated event/session summaries;
- reservation conversion/expiration/refund rates using documented cohorts;
- scan/attendance metrics;
- deterministic top sessions/events;
- bounded filter options;
- bounded safe CSV export.

All endpoints use bounded UTC date ranges. Operational counts and currency-keyed money are queried separately so API joins cannot multiply counts.

## 7. Frontend Dashboard

Extend Admin Portal with an isolated analytics child feature:

- KPI cards;
- accessible native SVG/CSS charting for Phase 14 instead of adding an arbitrary chart dependency;
- date/event/session filters;
- server pagination;
- independent loading/refresh/empty/error/unavailable states;
- explicit eventual-consistency/freshness wording;
- clear persistent `Stripe Test Mode — demo transactions only` disclosure;
- responsive layout.

Grafana is not embedded as the product/business dashboard.

## 8. CSV and Rebuild Safety

CSV export is server-side, ADMIN-only, bounded, PII-free, formula-injection protected, and preserves aggregate grain:

- OPERATIONS rows contain currency-neutral counts;
- REVENUE rows contain currency-specific money/financial counts;
- exports never silently truncate.

Do **not** add a destructive HTTP rebuild/reset endpoint. Rebuild is an offline operator procedure: stop analytics consumers, clear only analytics-owned read-model state, reset only the analytics consumer group's offsets to retained earliest records, then replay and verify.

Kafka retention limits what can be rebuilt; do not use cross-database reads as an undocumented backfill fallback.

## 9. Task Execution Order

Execute sequentially unless the orchestration workflow proves an isolated parallel stage safe:

1. `001-analytics-service-scaffold-schema-and-compose.md`
2. `002-idempotent-kafka-projection-consumers.md`
3. `003-event-session-sales-and-payment-projections.md`
4. `004-admin-analytics-rest-contracts.md`
5. `005-admin-kpi-dashboard-and-charts.md`
6. `006-filters-csv-and-operational-states.md`
7. `007-projection-idempotency-and-integration-tests.md`

Dependency notes:

- P14-001 can establish infrastructure/schema before P12/P13 code is complete.
- P14-002 onward must re-audit and bind to the final implemented P12/P13 event contracts before changing producers or writing projection handlers.
- P14-004 depends on P14-003 aggregate semantics being stable.
- P14-005 consumes the implemented P14-004 API contract exactly.
- P14-006 extends P14-004/P14-005; it does not redefine them.
- P14-007 is the final acceptance gate and must exercise real PostgreSQL/Kafka semantics for the risks that depend on them.

## 10. Definition of Done

- [ ] No analytics query crosses another service's database or synchronously fans out to source services.
- [ ] Duplicate/replayed events do not double-count facts, counts, attendance, or money.
- [ ] Cross-topic reordering converges through event-derived reconciliation.
- [ ] Operational counts cannot be multiplied by multi-currency financial rows.
- [ ] Gross/refunded/net money is exact, minor-unit, and currency-separated.
- [ ] Checkout/business services work with analytics stopped.
- [ ] ADMIN-only API/export authorization is integration-tested.
- [ ] Dashboard exposes useful event/session metrics with honest eventual-consistency and Test Mode labels.
- [ ] CSV is bounded, deterministic, PII-free, and spreadsheet-formula safe.
- [ ] New service participates in existing observability/deployment patterns.
- [ ] Rebuild from the same retained canonical event set is deterministic within Kafka retention limits.
- [ ] Phase 14 critical independent review and final QA have no unresolved blocker.
