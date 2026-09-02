# ADR-013: Dedicated Analytics Microservice with Event-Driven CQRS Projections

- **Date:** 2026-09-02
- **Status:** `PROPOSED`
- **Driven by:** Phase 14 — Admin Analytics & Operations Dashboard

## 1. Context

The Admin Portal requires executive and operational dashboards including sales revenue time-series, event/session occupancy, ticket scanning attendance rates, conversion funnel statistics, and reservation expiration/abandonment rates.

Performing ad-hoc reporting queries directly across operational microservice databases (`seatflow_reservation`, `seatflow_payment`, `seatflow_ticket`, `seatflow_event`) would:
1. Violate database-per-service encapsulation and microservice autonomy;
2. Introduce cross-database network joins or distributed locking hazards;
3. Impose analytical compute load onto online transactional processing (OLTP) tables on hot paths (e.g. seat holding and payment verification).

## 2. Decision

Introduce a dedicated `analytics-service` (default port `8089`) with its own isolated PostgreSQL database (`seatflow_analytics`).

The Analytics Service operates strictly as an asynchronous **Event-Driven CQRS Read Model / Projection Engine**:
1. **Asynchronous Ingestion:** Subscribes to Kafka domain topics (`ReservationCreated`, `ReservationConfirmed`, `ReservationExpired`, `PaymentCompleted`, `PaymentRefunded`, `TicketIssued`, `TicketRevoked`, `TicketScanned`, `EventSessionCompleted`).
2. **Idempotent Projections:** Consumes events wrapped in `EventEnvelope<T>` and tracks processed message IDs in a `processed_events` deduplication table, ensuring zero double-counting across retries or replays.
3. **Decoupled Write Path:** The analytics service never participates in transactional booking, hold reservation, or payment verification. A complete failure or lag in the analytics service will never degrade checkout or ticketing availability.
4. **Admin-Only REST APIs:** Exposes query-optimized `/api/admin/analytics/*` endpoints with bounded date filtering, aggregation granularity, and CSV export capabilities.

## 3. Alternatives Considered

### Direct cross-database queries or shared analytics read-replicas
- **Pros:** No separate service or event ingestion code needed.
- **Cons:** Violates microservice boundaries, couples analytical queries to evolving operational schemas, and risks transactional connection pool exhaustion.
- **Rejected:** Architectural boundary integrity is non-negotiable.

### Embedding Grafana dashboards in the admin UI
- **Pros:** Fast setup for basic metrics charts.
- **Cons:** Grafana is designed for system/infrastructure observability (Prometheus time-series), not domain business intelligence requiring customer filters, session breakdowns, and CSV exports.
- **Rejected:** Observability and product analytics serve distinct operational purposes.

### Synchronous REST aggregation in API Gateway / BFF
- **Pros:** No extra database required.
- **Cons:** High latency, fan-out network queries across all services on every admin dashboard load, lack of historical time-series aggregation.
- **Rejected:** Unscalable and violates single responsibility.

## 4. Consequences

**Positive:**
- Complete isolation and protection of core transactional OLTP databases.
- Eventual consistency guarantees with zero impact on customer-facing latency or reservation throughput.
- Tailored, denormalized read schemas optimized for fast administrative aggregation.
- Clear separation between infrastructure observability (Prometheus/Grafana) and product business intelligence (Admin Analytics).

**Trade-offs:**
- Requires maintaining an additional microservice, Flyway schema, and Kafka consumer group.
- Projections are eventually consistent (sub-second lag behind Kafka events).

## 5. Implementation Notes

- Service directory: `backend/services/analytics-service`.
- Database name: `seatflow_analytics`.
- Security: All REST endpoints require `ROLE_ADMIN` server-side validation.
- All revenue and payment calculations must be clearly tagged and labeled as `Stripe Test Mode / Demo` in API responses and UI widgets.
