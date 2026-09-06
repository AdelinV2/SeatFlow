# SeatFlow Analytics Service

Event-driven CQRS read model and ADMIN-only analytics REST API (Phase 14,
[ADR-013](../../../.ai/decisions/ADR-013-analytics-event-driven-read-model.md)).

- Port `8089`, Eureka client, PostgreSQL database `seatflow_analytics`.
- Kafka consumer group `analytics-service-v1` (`auto.offset.reset=earliest`,
  auto-commit disabled, `read_committed`, RECORD acknowledgement).
- Projections are idempotent via the `processed_events` deduplication table:
  redelivery of an already-processed `EventEnvelope.eventId` is a no-op.
- The service never blocks checkout/ticketing: reservation, payment, ticket, and
  event write paths work with analytics stopped.
- All REST endpoints under `/api/admin/analytics/**` require `ROLE_ADMIN`
  server-side. Revenue is Stripe Test Mode demo data in integer minor units,
  never summed across currencies. No customer PII is stored or exported.

## Query model

- `daily_operational_metrics` — currency-neutral counts per
  `(metric_date, event_id, event_session_id)`.
- `daily_revenue_metrics` — currency-keyed money per
  `(metric_date, event_id, event_session_id, currency)`.
- `event_session_metrics` / `event_session_revenue_metrics` — lifetime aggregates.
- `analytics_*_facts` — event-derived facts, including display snapshots
  (`event_title`, `session_label`) populated only from trusted events.
- CSV export (`GET /api/admin/analytics/export/daily.csv`) is a bounded
  (max 10,000 rows), server-side, formula-injection-safe long-form union of the
  `OPERATIONS` and `REVENUE` grains. Oversized exports fail with
  `ANALYTICS_EXPORT_TOO_LARGE` and no partial file.

## Offline projection rebuild / replay runbook

There is deliberately **no HTTP reset/rebuild endpoint**: truncating analytics
state or resetting consumer offsets must never be one click away from a running
dashboard. Rebuilds follow this controlled operator procedure only:

```text
1. Stop every analytics-service instance.
2. Confirm analytics-service-v1 consumer group has no active analytics member.
3. Clear/recreate only seatflow_analytics read-model tables, including processed_events,
   using documented safe ordering/transaction.
4. Reset analytics-service-v1 offsets for reservation/payment/ticket/event topics to
   earliest retained offsets with Kafka admin tooling.
5. Restart analytics-service.
6. Observe consumer failure/DLQ/processed/lag metrics until replay settles.
7. Verify representative dashboard totals against known event fixtures.
```

Warnings:

- Kafka replays only retained history; events past retention are gone and there
  is no undocumented source-database backfill (cross-database reads are
  forbidden — see ADR-013).
- Consumer-group ID changes are explicit operator decisions, never normal
  deploy behavior.
- Never reset offsets while consumers are active: in-flight claims in
  `processed_events` would turn redelivered events into silent no-ops and the
  replay would converge on an incomplete read model.
- Production rebuilds require normal operational approval and backup practices
  for the `seatflow_analytics` database.
- Rebuild is never automatic at application startup: the service boots against
  whatever projections exist and resumes consuming from its committed offsets.
