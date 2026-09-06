# TASK-P14-002: Implement Idempotent Kafka Projection Consumers and Contract Audit

## 1. Task Metadata

- **Task ID:** `TASK-P14-002`
- **Git Branch:** `feat/p14-002-idempotent-projection-consumers`
- **Target Module:** `backend/services/analytics-service`, final P12/P13 producer event contracts only when an additive analytics-safe field is genuinely required
- **Phase:** `Phase 14 - Admin Analytics & Operations Dashboard`
- **Related Specs:** `.ai/tasks/phase-14-admin-analytics/000-phase-overview.md`, `.ai/architecture/05-messaging-and-outbox.md`, `.ai/architecture/09-post-mvp-evolution.md`
- **Related ADRs:** `.ai/decisions/ADR-013-analytics-event-driven-read-model.md`
- **Status:** `READY FOR IMPLEMENTATION`

### Orchestration Metadata

- **Complexity:** `5`
- **Failure Risk:** `Critical`
- **Verification Strength:** `Strong`
- **Required Review Depth:** `Critical`
- **Preferred Workflow:** `critical`
- **Affected Critical Invariants:** `at-least-once delivery safety; EventEnvelope identity; transactional projection; no write-path coupling; schema compatibility; retry safety`

---

## 2. Objective

Implement the Kafka ingestion boundary for analytics so domain events can be consumed with **exactly-once projection effects on the analytics database despite Kafka's at-least-once delivery**.

The core contract is:

```text
Kafka record
  -> deserialize canonical EventEnvelope
  -> validate event type/payload
  -> atomically claim EventEnvelope.eventId in processed_events
  -> execute one analytics projection handler
  -> commit analytics DB transaction
  -> acknowledge Kafka record
```

If projection processing fails, the database transaction must roll back including the `processed_events` claim so the same event can be retried. Duplicate delivery of the same `eventId` must not mutate facts or aggregates a second time.

This task also performs a mandatory upstream contract audit after P12/P13 implementation. Analytics must consume the **actual canonical final events**; it must not create parallel analytics-only copies of domain events simply because a task document used a candidate name.

---

## 3. Critical Invariants & Failure Modes

### 3.1 Invariants

- [ ] Event deduplication key is `EventEnvelope.eventId`, never Kafka offset alone, aggregate ID, payment ID, reservation ID, or timestamp.
- [ ] Event claim and all analytics DB mutations for that event share one local database transaction.
- [ ] A failed projection does not leave `processed_events` committed.
- [ ] Kafka acknowledgement/offset progression occurs only after successful transaction completion or an explicit non-relevant-event decision.
- [ ] Auto-commit is disabled.
- [ ] New consumer group ID is stable and versioned, e.g. `analytics-service-v1`; changing it is an explicit rebuild/operational action, not normal deployment behavior.
- [ ] Initial group behavior is `auto-offset-reset=earliest` so retained event history can seed a new read model.
- [ ] Unknown events on a subscribed shared topic are safely ignored and acknowledged; they are not treated as malformed analytics events.
- [ ] A known analytics event with malformed/missing required payload is **not** silently ignored and **not** marked processed.
- [ ] No consumer performs synchronous REST calls to reservation/payment/ticket/event services while processing a Kafka record.
- [ ] No consumer reads another service database.
- [ ] Cross-topic ordering is assumed nonexistent. Handlers must tolerate dependent facts arriving in either order.
- [ ] Duplicate events and distinct semantic events for the same aggregate are different concepts: only duplicate `eventId` is dropped.
- [ ] Producer contracts continue through each source service's transactional outbox. Do not add a direct `KafkaTemplate.send()` from a business transaction merely for analytics.
- [ ] Existing consumers are not broken by analytics-related additive payload fields.
- [ ] No PII is added to producer event payloads just to power analytics.

### 3.2 Failure Modes to Prevent

- `existsById()` followed by `save()` race allowing concurrent duplicate projection;
- marking an event processed before a handler succeeds;
- acknowledging Kafka before database commit;
- replay inflating revenue/tickets/reservations;
- PaymentCompleted arriving before ReservationHeld and being dropped forever;
- TicketScanned arriving before TicketIssued and being lost;
- consumer crashes on unrelated event type present on a shared topic;
- malformed known payload acknowledged as if successful;
- local DTO copy drifts from the final P12/P13 producer contract;
- analytics requires producer-service availability at consumption time;
- analytics event enrichment weakens write-path availability or outbox atomicity.

---

## 4. Dependencies / Prerequisites

- P14-001 scaffold/schema complete.
- Phase 12 final contracts implemented, especially session-aware reservation/payment/ticket propagation from P12-003/P12-004.
- Phase 13 final refund/revocation contracts implemented, especially `PaymentRefunded` (or final canonical equivalent), reservation refund completion, and ticket revocation.
- Existing `backend/common/common-events` `EventEnvelope`, `EventTopics`, and producer outbox conventions remain authoritative.

### 4.1 Mandatory Contract-Audit Gate

Before code changes, produce a short implementation note/ledger mapping **final producer class -> EventEnvelope.eventType -> topic -> required analytics fields**. At minimum audit:

| Analytics semantic | Candidate/final source to verify | Topic family | Required correlation/data |
|---|---|---|---|
| Reservation created/held | current `ReservationHeld` or P12 final equivalent | reservation events | reservationId, eventId, eventSessionId, seat count, occurredAt; amount/currency only if canonical |
| Reservation confirmed | `ReservationConfirmed` | reservation events | reservationId, eventId, eventSessionId, occurredAt |
| Reservation expired | `ReservationExpired` | reservation events | reservationId, eventSessionId, occurredAt |
| Reservation refunded | P13 final `ReservationRefunded` or equivalent | reservation events | reservationId, eventSessionId, occurredAt |
| Payment success | `PaymentCompleted` | payment events | paymentId, reservationId, amountMinor/canonical amount, currency, occurredAt |
| Payment failure | `PaymentFailed` | payment events | paymentId, reservationId, occurredAt; amount only if canonical |
| Refund success | P13 final `PaymentRefunded` | payment events | paymentId, reservationId, refunded amount minor, currency, occurredAt |
| Ticket issue | `TicketIssued` | ticket events | ticketId, reservationId, eventSessionId where final contract provides it, occurredAt |
| Ticket revoke | P13 final ticket-revoked event | ticket events | ticketId and/or reservationId, eventSessionId when available, occurredAt |
| Ticket scan | final valid-scan event | ticket events | ticketId, eventSessionId when available, occurredAt |
| Session lifecycle | P12 final event-session event(s) | event events | eventId, eventSessionId, startsAt, endsAt, status, safe display snapshot; capacity only if authoritative snapshot exists |

Rules:

1. If the final producer already exposes enough trusted correlation, consume it unchanged.
2. If correlation can be derived later from another **event-derived analytics fact** (e.g. payment -> reservationId -> reservation fact -> session), do that rather than bloating producer payloads.
3. Add fields to an upstream event only when there is no safe event-derived correlation path and the field is owned/trusted by that producer.
4. Never introduce a second `ReservationCreated` event if `ReservationHeld` is the canonical creation semantic.
5. If exact event names differ from this table after P12/P13, update analytics handlers/tests to the final names; do not force producers back to candidate names solely to match this task.

---

## 5. Exact File Inventory

Expected analytics-service additions; use current package conventions if P14-001 established slightly different names.

- `[NEW]` `backend/services/analytics-service/src/main/java/com/seatflow/analytics/config/KafkaConsumerConfig.java`
- `[NEW]` `backend/services/analytics-service/src/main/java/com/seatflow/analytics/messaging/AnalyticsEventConsumer.java`
- `[NEW]` `backend/services/analytics-service/src/main/java/com/seatflow/analytics/messaging/AnalyticsEventDispatcher.java`
- `[NEW]` `backend/services/analytics-service/src/main/java/com/seatflow/analytics/messaging/ProjectionEventProcessor.java`
- `[NEW]` `backend/services/analytics-service/src/main/java/com/seatflow/analytics/messaging/ProjectionHandler.java`
- `[NEW]` analytics-local payload adapters only if canonical common-event types are not shared; prefer final canonical common types when available
- `[NEW]` `backend/services/analytics-service/src/main/java/com/seatflow/analytics/repository/ProcessedEventRepository.java`
- `[NEW]` `backend/services/analytics-service/src/main/java/com/seatflow/analytics/metrics/AnalyticsConsumerMetrics.java`
- `[MODIFY]` analytics `application*.yaml` Kafka consumer settings
- `[MODIFY]` `backend/common/common-events/src/main/java/com/seatflow/common/events/EventTopics.java` only if a genuine analytics DLT topic constant is adopted
- `[MODIFY]` final source event records/producers **only if** the contract-audit gate proves an additive trusted field is necessary
- `[NEW]` focused unit/integration tests under `backend/services/analytics-service/src/test/java/.../messaging/`

Do not duplicate all source events into `common-events` as a cleanup side quest. Follow the current project's ownership conventions and change only the minimum canonical contract surface needed.

---

## 6. Technical Specifications & Contracts

### 6.1 Subscribed Topics

Subscribe to the canonical constants for:

- reservation events;
- payment events;
- ticket events;
- event/session events.

Do not subscribe to notification events. Do not subscribe to seat-map events unless a later approved requirement proves they are necessary; capacity remains nullable rather than inferred from an unsafe source.

Use one analytics consumer group unless a concrete throughput/isolation need justifies per-topic groups. Group ID baseline:

```text
analytics-service-v1
```

### 6.2 Deserialization Boundary

Deserialize the common `EventEnvelope` first, retaining at least:

```text
eventId
eventType
occurredAt
aggregateId (if present in current envelope)
payload
```

Do not trust Java type headers from arbitrary producers to instantiate unrestricted classes. Follow the repository's existing safe JSON deserialization approach, then map the payload for an allowlisted `eventType` to the expected record.

Validate for a relevant event:

- `eventId` non-blank;
- `eventType` non-blank and allowlisted;
- `occurredAt` present;
- payload present;
- event-specific required IDs/amount/currency fields present and valid.

Unknown `eventType`: log at controlled level/metric and acknowledge without inserting a processed marker unless the implementation has an explicit operational reason to record ignored events.

### 6.3 Atomic Idempotency Claim

Do **not** implement:

```text
if (!repository.existsById(eventId)) {
    repository.save(...);
    handler.apply(...);
}
```

That pattern races under concurrent duplicate delivery.

Implement a single-statement claim such as PostgreSQL:

```sql
INSERT INTO processed_events (...)
VALUES (...)
ON CONFLICT (event_id) DO NOTHING;
```

Return affected row count:

- `1` -> this transaction owns first processing; execute handler;
- `0` -> duplicate; return without projection mutation.

The claim and handler call are enclosed by one Spring transaction on `seatflow_analytics`. If the handler throws, the claim rolls back.

### 6.4 Handler Interface

Use a narrow deterministic abstraction. Example shape:

```java
public interface ProjectionHandler {
    String eventType();
    void project(EventEnvelope<?> envelope, ConsumerRecordMetadata metadata);
}
```

or an equivalent typed registry. Requirements:

- one clear handler per semantic event or cohesive aggregate family;
- dispatcher rejects duplicate registrations for the same `eventType` at startup;
- handlers mutate only analytics-owned repositories;
- handlers are side-effect free outside analytics DB (no email, HTTP, Kafka publishing, source DB writes);
- P14-003 owns detailed fact/aggregate mutation logic.

### 6.5 Kafka Offset / Transaction Ordering

The service is not required to implement distributed exactly-once transactions between Kafka and PostgreSQL. Instead guarantee **idempotent database effects**:

1. receive record;
2. open DB transaction;
3. claim `eventId`;
4. apply projection;
5. commit DB;
6. acknowledge/allow offset commit.

Crash after step 5 but before 6 causes redelivery; `processed_events` makes it a no-op. Crash before step 5 rolls back and retry is allowed.

Disable Kafka auto-commit. Use the project's supported listener acknowledgement/container pattern so an exception is visible to the error handler rather than swallowed.

### 6.6 Retry and Dead-Letter Policy

Use a **bounded** retry strategy for processing failures. Baseline unless repository conventions already define a stronger standard:

- 3 processing attempts total or equivalent bounded retries with short backoff;
- serialization/validation failure for a known analytics event is non-transient and should not spin forever;
- infrastructure/transient DB failures may be retried;
- after exhaustion, recover to a dedicated analytics dead-letter path if the current SeatFlow Kafka conventions support DLT safely.

If a DLT is implemented:

```text
seatflow.analytics.dlt
```

and include original topic/partition/offset/eventId/eventType/error category in headers/logging without PII. DLT publication failure must remain visible; do not silently acknowledge data loss.

If the current repo deliberately has no DLT infrastructure, do not invent a weak one in half a task. Instead configure bounded retries + non-acknowledged terminal failure with a clearly failing consumer health/metric and document the operational behavior. The implementation/reviewer must choose one explicit strategy; no silent drop is acceptable.

### 6.7 Out-of-Order Contract

Different Kafka topics have no global ordering. Therefore P14-002 infrastructure must permit these sequences without treating them as impossible:

```text
PaymentCompleted -> ReservationHeld
TicketScanned -> TicketIssued
PaymentRefunded -> ReservationRefunded
Session lifecycle update -> earlier reservation replay
```

The consumer layer must deliver each valid event to P14-003 facts even if its related fact is not present yet. Missing correlation is a temporary read-model state, not a reason to discard the event.

Do not use sleep/retry waiting for another topic. Persist what is known and let deterministic reconciliation in P14-003 complete relationships when the corresponding fact arrives.

### 6.8 Metrics / Logs

Add low-cardinality metrics, for example:

```text
seatflow.analytics.events.processed{event_type}
seatflow.analytics.events.duplicate{event_type}
seatflow.analytics.events.ignored{event_type}
seatflow.analytics.events.failed{event_type,reason_category}
```

Never use event ID, reservation ID, payment ID, ticket ID, user ID, email, or exception text as metric labels.

Logs may include correlation/event IDs according to repository policy but must not dump full payloads containing sensitive data.

---

## 7. Step-by-Step Implementation Sequence

1. Complete the P12/P13 producer/consumer event-contract audit and record final event names/fields.
2. Identify any truly missing trusted correlation fields and prefer analytics-side event-derived joins over producer expansion.
3. Configure stable Kafka group, earliest initial offset behavior, disabled auto-commit, retry/error handling, and deserialization.
4. Implement atomic `processed_events` claim using one SQL statement.
5. Implement dispatcher/handler registry with startup duplicate-registration protection.
6. Implement listener(s) over the four topic families.
7. Wire relevant event types to P14-003-ready handler stubs/interfaces without inventing aggregate calculations here.
8. Add metrics/logging for processed/duplicate/ignored/failed outcomes.
9. Add tests proving transaction rollback, redelivery safety, unknown-event behavior, malformed-known-event behavior, and cross-topic independence.
10. Run analytics-service tests with real PostgreSQL and Kafka/Testcontainers where the test exercises broker semantics.

---

## 8. Test Requirements

### 8.1 Idempotency

- [ ] Same Kafka record/eventId delivered twice -> handler side effect executes once.
- [ ] Two concurrent threads attempt same eventId -> exactly one claims it; no duplicate projection.
- [ ] Same aggregate ID with two **different** eventIds -> both semantic events are processed.
- [ ] Duplicate event after application restart remains a no-op due to durable DB marker.

### 8.2 Transaction Failure

- [ ] Handler throws after event claim -> `processed_events` row is rolled back.
- [ ] Redelivery after that failure can claim and process successfully.
- [ ] Simulated crash-equivalent after DB commit but before offset advancement -> redelivery sees duplicate marker and does not mutate projection again.

### 8.3 Event Classification

- [ ] Unrelated/unknown event on subscribed topic is acknowledged safely and does not crash the consumer.
- [ ] Known event with missing required field is not marked processed and follows configured failure/DLT policy.
- [ ] Invalid money/currency payload cannot reach fact mutation.
- [ ] Event contract test proves final P12/P13 `eventSessionId` propagation is consumed rather than inferred from `eventId` alone.

### 8.4 Cross-Topic / Ordering

- [ ] `PaymentCompleted` can be accepted before reservation fact exists.
- [ ] `TicketScanned` can be accepted before ticket issue fact exists.
- [ ] Consumer never blocks waiting synchronously for a related service or event.

### 8.5 Kafka Integration

Use a real broker/Testcontainers for at least:

- [ ] subscription to canonical topic constants;
- [ ] retry/redelivery behavior;
- [ ] duplicate delivery after offset replay;
- [ ] consumer group `analytics-service-v1` starts from retained earliest records when no committed offset exists.

---

## 9. Verification Commands

```bash
cd backend
./mvnw -pl services/analytics-service -am test
```

If analytics contract changes touched producers, also run every affected producer/consumer module in the same command, for example:

```bash
./mvnw -pl services/reservation-service,services/payment-service,services/ticket-service,services/event-service,services/analytics-service -am test
```

No source-service suite may be skipped when its event contract changed.

---

## 10. Independent Review Focus

Review must focus on failure windows, not happy-path style:

- race-free event claim;
- DB transaction boundary relative to Kafka acknowledgement;
- retry/DLT behavior with no silent data loss;
- duplicate vs distinct-semantic-event distinction;
- cross-topic out-of-order handling;
- safe payload deserialization;
- actual final P12/P13 contract usage;
- no synchronous source-service lookup;
- no business-service write-path changes that depend on analytics;
- low-cardinality observability.

---

## 11. Acceptance Criteria

- [ ] Final upstream analytics event matrix is verified against implemented P12/P13 producer code.
- [ ] Analytics consumes canonical `EventEnvelope` events from reservation/payment/ticket/event topics.
- [ ] Database effects are idempotent by `eventId` under duplicate and concurrent delivery.
- [ ] Event claim rolls back whenever projection fails.
- [ ] Kafka acknowledgement cannot make an uncommitted analytics event disappear.
- [ ] Unknown events are safe; malformed known events are visible failures, not silent drops.
- [ ] Cross-topic out-of-order arrival is accepted without REST/DB coupling to source services.
- [ ] Retry/dead-letter behavior is explicit and tested.
- [ ] Critical independent review passes.

---

## 12. Execution Entry Point

```text
Implement TASK-P14-002 using the SeatFlow autonomous orchestration workflow.
First audit the final P12/P13 event contracts. Do not assume candidate event names from the roadmap are exact, and do not create analytics-only duplicate domain events.
```
