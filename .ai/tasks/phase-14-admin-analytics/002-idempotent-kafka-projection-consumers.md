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
- **Affected Critical Invariants:** `at-least-once delivery safety; EventEnvelope identity; transactional projection; no write-path coupling; schema compatibility; retry/DLQ safety`

---

## 2. Objective

Implement the Kafka ingestion boundary for analytics so domain events can be consumed with **exactly-once projection effects on the analytics database despite Kafka's at-least-once delivery**.

Core contract:

```text
Kafka record
  -> deserialize canonical EventEnvelope
  -> classify/validate event type and payload
  -> atomically claim EventEnvelope.eventId in processed_events
  -> execute one analytics projection handler
  -> commit analytics DB transaction
  -> allow RECORD acknowledgement/offset progression
```

If projection processing fails, the analytics DB transaction must roll back including the `processed_events` claim. Duplicate delivery of the same `eventId` must not mutate facts or aggregates a second time.

This task also performs a mandatory upstream contract audit after P12/P13 implementation. Analytics consumes the **actual canonical final events**; it does not create parallel analytics-only copies of domain events merely because roadmap text used a candidate name.

---

## 3. Critical Invariants & Failure Modes

### 3.1 Invariants

- [ ] Event deduplication key is `EventEnvelope.eventId`, never Kafka offset alone, aggregate ID, payment ID, reservation ID, or timestamp.
- [ ] Event claim and all analytics DB mutations for that event share one local database transaction.
- [ ] A failed projection does not leave `processed_events` committed.
- [ ] Kafka RECORD acknowledgement/offset progression happens only after successful processing or successful dead-letter recovery under the explicit policy below.
- [ ] Kafka auto-commit is disabled and `isolation.level=read_committed` matches SeatFlow's existing consumer convention.
- [ ] Stable consumer group ID is `analytics-service-v1`; it is not changed on normal deployments.
- [ ] New group behavior is `auto-offset-reset=earliest` so retained history can seed a new read model.
- [ ] Unknown events on a subscribed shared topic are safely ignored and acknowledged; they are not malformed analytics events.
- [ ] A known analytics event with malformed/missing required payload is never marked processed and is dead-lettered under the explicit policy.
- [ ] No consumer performs synchronous REST calls to reservation/payment/ticket/event services.
- [ ] No consumer reads another service database.
- [ ] Cross-topic ordering is assumed nonexistent. Handlers tolerate related facts arriving in either order.
- [ ] Duplicate events and distinct semantic events for the same aggregate are different: only duplicate `eventId` is dropped.
- [ ] Producer contracts remain transactional-outbox based. Do not add direct best-effort Kafka publishing merely for analytics.
- [ ] Existing consumers are not broken by any additive analytics-safe payload field.
- [ ] No PII is added to producer payloads just to power analytics.

### 3.2 Failure Modes to Prevent

- `existsById()` then `save()` race allowing concurrent duplicate projection;
- marker committed before a failing handler;
- Kafka offset advancing before DB commit;
- replay inflating revenue/tickets/reservations;
- PaymentCompleted arriving before ReservationHeld and being dropped forever;
- TicketScanned arriving before TicketIssued and being lost;
- consumer crashes on an unrelated shared-topic event;
- malformed known payload acknowledged as if successfully projected;
- poison event causes an infinite retry loop;
- local DTO copy drifts from final P12/P13 producer contract;
- analytics requires producer-service availability at consumption time;
- analytics enrichment weakens source write-path availability/outbox atomicity.

---

## 4. Dependencies / Prerequisites

- P14-001 scaffold/schema complete.
- Phase 12 final session-aware contracts implemented, especially P12-003/P12-004.
- Phase 13 final refund/revocation contracts implemented, especially completed payment refund and ticket revocation.
- Existing `backend/common/common-events` `EventEnvelope`, `EventTopics`, and transactional-outbox conventions remain authoritative.
- Reuse the current SeatFlow Kafka consumer failure pattern from `reservation-service`: `AckMode.RECORD`, disabled auto-commit, `read_committed`, `DefaultErrorHandler`, `DeadLetterPublishingRecoverer`, and bounded fixed backoff.

### 4.1 Mandatory Contract-Audit Gate

Before code changes, record a short implementation ledger mapping **final producer class -> EventEnvelope.eventType -> EventTopics topic -> fields analytics uses**.

At minimum audit:

| Analytics semantic | Candidate/final source to verify | Topic family | Required correlation/data |
|---|---|---|---|
| Reservation created/held | current `ReservationHeld` or P12 final equivalent | reservation events | reservationId, eventId, eventSessionId, seat count, occurredAt |
| Reservation confirmed | `ReservationConfirmed` | reservation events | reservationId, eventId/eventSessionId as final contract provides, occurredAt |
| Reservation expired | `ReservationExpired` | reservation events | reservationId, eventSessionId, occurredAt |
| Reservation refunded | P13 final `ReservationRefunded` or equivalent | reservation events | reservationId, eventSessionId, occurredAt |
| Payment success | `PaymentCompleted` | payment events | paymentId, reservationId, completed amount minor, currency, occurredAt |
| Payment failure | `PaymentFailed` | payment events | paymentId, reservationId, occurredAt |
| Refund success | P13 final `PaymentRefunded` | payment events | paymentId, reservationId, refunded amount minor, currency, occurredAt |
| Ticket issue | `TicketIssued` | ticket events | ticketId, reservationId, eventSessionId where final contract provides it, occurredAt |
| Ticket revoke | P13 final revocation event | ticket events | final canonical ticket/reservation identity + occurredAt |
| Ticket scan | final accepted-scan event | ticket events | ticketId, eventSessionId when available, occurredAt |
| Session lifecycle | P12 final event-session event(s) | event events | eventId, eventSessionId, startsAt, endsAt, status, safe display snapshot; capacity only if authoritative snapshot exists |

Rules:

1. Consume an existing trusted canonical field unchanged when sufficient.
2. Prefer analytics-side event-derived correlation, e.g. payment `reservationId` -> analytics reservation fact -> session, over producer payload bloat.
3. Add an upstream field only when no safe event-derived correlation path exists and that producer truly owns/trusts the field.
4. Never introduce a second `ReservationCreated` event if `ReservationHeld` remains the canonical creation semantic.
5. If final event names differ after P12/P13, update analytics handlers/tests to final names; do not force producers back to roadmap candidate names.
6. If a source event is reservation-scoped (e.g. bulk ticket revocation), document that exact shape so P14-003 can persist pending correlation safely.

---

## 5. Exact File Inventory

Expected analytics-service additions; use the actual package names established by P14-001.

- `[NEW]` `.../config/KafkaConsumerConfig.java`
- `[NEW]` `.../messaging/AnalyticsEventConsumer.java`
- `[NEW]` `.../messaging/AnalyticsEventDispatcher.java`
- `[NEW]` `.../messaging/ProjectionEventProcessor.java`
- `[NEW]` `.../messaging/ProjectionHandler.java`
- `[NEW]` `.../messaging/AnalyticsEventValidationException.java`
- `[NEW]` analytics-local payload adapters only where canonical source types are not shareable under current ownership conventions
- `[NEW]` `.../repository/ProcessedEventRepository.java`
- `[NEW]` `.../metrics/AnalyticsConsumerMetrics.java`
- `[MODIFY]` analytics `application*.yaml` Kafka consumer settings
- `[MODIFY]` final source event records/producers only if the contract-audit gate proves an additive trusted field is necessary
- `[NEW]` focused unit/integration tests under analytics messaging test packages

DLQ topic is fixed for this service:

```text
seatflow.analytics.events.dlq
```

Use a local constant in analytics Kafka configuration or the repository's established topic-constant location; do not scatter the literal across listeners/tests.

Do not duplicate every source event into `common-events` as a cleanup side quest.

---

## 6. Technical Specifications & Contracts

### 6.1 Subscribed Topics

Subscribe to canonical `EventTopics` constants for:

- reservation events;
- payment events;
- ticket events;
- event/session events.

Do not subscribe to notification events. Do not subscribe to seat-map events merely to fabricate capacity; capacity stays nullable if no trusted session snapshot exists.

Consumer group:

```text
analytics-service-v1
```

### 6.2 Exact Kafka Consumer Baseline

Match current SeatFlow consumer mechanics unless a repository-wide refactor has already replaced them:

```text
value deserializer       StringDeserializer
key deserializer         StringDeserializer
enable.auto.commit       false
auto.offset.reset        earliest
isolation.level          read_committed
container ack mode       RECORD
```

Analytics parses the String JSON into the canonical common `EventEnvelope` and then an allowlisted typed payload.

### 6.3 Deserialization / Validation Boundary

Retain at least:

```text
eventId
eventType
occurredAt
aggregateId if present in current envelope
payload
```

Do not trust unrestricted Java type headers from producers to instantiate arbitrary classes.

Known relevant event validation requires:

- non-blank `eventId`;
- allowlisted non-blank `eventType`;
- non-null `occurredAt`;
- non-null payload;
- event-specific required IDs/amount/currency fields valid.

Unknown event type on a subscribed topic:

```text
log controlled diagnostic + increment ignored metric + return normally
```

Do not insert a processed marker for unknown/non-relevant events.

Known malformed event:

```text
throw AnalyticsEventValidationException
```

It must not reach the projection transaction and must not be marked processed.

### 6.4 Atomic Idempotency Claim

Forbidden:

```text
if (!repository.existsById(eventId)) {
    repository.save(...);
    handler.apply(...);
}
```

Required single-statement claim equivalent to:

```sql
INSERT INTO processed_events (...)
VALUES (...)
ON CONFLICT (event_id) DO NOTHING;
```

Affected rows:

- `1` -> first owner; run handler;
- `0` -> duplicate; return without projection mutation.

Claim + handler execute inside one Spring transaction on `seatflow_analytics`. If handler throws, both roll back.

### 6.5 Handler / Dispatcher Contract

Use a narrow deterministic handler abstraction. Example:

```java
public interface ProjectionHandler {
    String eventType();
    void project(EventEnvelope<?> envelope, ConsumerRecordMetadata metadata);
}
```

Equivalent typed registries are acceptable if they preserve:

- one clear handler per semantic event/cohesive aggregate family;
- startup failure on duplicate handler registrations for same eventType;
- analytics-DB-only mutations;
- no HTTP/email/Kafka side effects from projection handlers;
- no source-database reads.

P14-003 owns business fact/aggregate semantics.

### 6.6 DB Commit vs Kafka Offset Failure Window

No distributed transaction between Kafka and PostgreSQL is required. Correctness comes from durable idempotency:

1. receive record;
2. begin analytics DB transaction;
3. claim eventId;
4. project;
5. commit DB;
6. listener returns successfully and RECORD offset may advance.

Crash after step 5 but before durable offset progression -> Kafka redelivers -> `processed_events` causes no-op.

Crash/failure before step 5 -> DB rolls back -> retry can claim the event.

Do not catch handler exceptions and return normally.

### 6.7 Exact Retry and Dead-Letter Policy

Use the same style already used by SeatFlow's reservation consumer, with analytics-specific DLQ:

```text
DeadLetterPublishingRecoverer -> seatflow.analytics.events.dlq, same partition
DefaultErrorHandler
transient FixedBackOff = 1000 ms, 3 retries after the initial attempt
AckMode.RECORD
auto-commit disabled
```

Classify errors:

- `AnalyticsEventValidationException`: **non-retryable**, recover directly to analytics DLQ;
- deterministic unsupported-known-contract/mapping errors: non-retryable once clearly classified;
- transient PostgreSQL/Kafka/infrastructure errors: 3 retries with 1-second fixed backoff, then DLQ;
- unknown/non-relevant eventType: not an error, ignore/ack normally.

DLQ publication must preserve original Kafka metadata through standard recoverer headers and add no PII. Log eventId/eventType/topic/partition/error category, not full sensitive payload.

The original record may progress only after successful DLQ recovery. A DLQ publishing failure must remain a visible consumer failure; it must not be swallowed as success.

Tests must verify this policy with the real `DefaultErrorHandler` configuration.

### 6.8 Out-of-Order Contract

Different topics have no global order. These are valid analytics arrival patterns:

```text
PaymentCompleted -> ReservationHeld
TicketScanned -> TicketIssued
PaymentRefunded -> ReservationRefunded
Session lifecycle event -> earlier/later business-topic replay
```

The consumer layer delivers each valid event to P14-003 even if related fact is not yet present.

Missing correlation is temporary read-model state. Do not:

- discard the event;
- block/sleep waiting for another topic;
- perform synchronous REST lookup;
- requeue indefinitely waiting for correlation.

Persist what is known and let P14-003 reconciliation complete relationships when missing facts arrive.

Within a single source topic/partition, tests should respect the producer's canonical ordering guarantees; do not invent impossible same-partition reorder semantics unless testing replay/correction behavior deliberately.

### 6.9 Metrics / Logs

Low-cardinality metrics:

```text
seatflow.analytics.events.processed{event_type}
seatflow.analytics.events.duplicate{event_type}
seatflow.analytics.events.ignored{event_type}
seatflow.analytics.events.failed{event_type,reason_category}
seatflow.analytics.events.dead_lettered{event_type,reason_category}
```

Never use event/reservation/payment/ticket/user IDs or exception text as metric labels.

Logs follow repository correlation policy and must not dump sensitive payloads.

---

## 7. Step-by-Step Implementation Sequence

1. Complete final P12/P13 producer/consumer event-contract audit.
2. Identify truly missing correlation fields; prefer analytics event-derived joins over producer expansion.
3. Configure exact consumer group, earliest offset behavior, disabled auto-commit, read-committed isolation, RECORD ack.
4. Configure fixed analytics DLQ/error policy: 1s x 3 transient retries, validation non-retryable.
5. Implement safe envelope classification/deserialization/validation.
6. Implement atomic `processed_events` claim using one SQL statement.
7. Implement dispatcher/handler registry with duplicate-registration startup protection.
8. Implement listeners over four topic families.
9. Wire relevant event types to P14-003-ready handler interfaces without aggregate calculations here.
10. Add processed/duplicate/ignored/failed/DLQ metrics.
11. Add real PostgreSQL/Kafka tests for transaction rollback, redelivery, DLQ, unknown/malformed event behavior, and cross-topic independence.

---

## 8. Test Requirements

### 8.1 Idempotency

- [ ] same eventId delivered twice -> projection handler side effect once;
- [ ] two concurrent attempts same eventId -> exactly one claims it;
- [ ] same aggregate ID with different eventIds -> both semantic events processed;
- [ ] duplicate after application restart remains no-op.

### 8.2 Transaction Failure

- [ ] handler throws after claim -> `processed_events` row rolls back;
- [ ] retry of same eventId can process successfully;
- [ ] DB-committed event redelivered due offset window -> no second projection mutation.

### 8.3 Event Classification

- [ ] unknown event -> ignored/acked, ignored metric increments, no processed marker;
- [ ] known malformed event -> validation exception, no marker, sent directly to analytics DLQ without transient retries;
- [ ] transient handler/DB failure -> exactly 3 retries after initial, then analytics DLQ;
- [ ] invalid money/currency cannot reach fact mutation;
- [ ] final P12/P13 session/refund contract fields are consumed rather than guessed.

### 8.4 DLQ

Using real Kafka/Testcontainers:

- [ ] DLQ topic is `seatflow.analytics.events.dlq`;
- [ ] recovered record preserves original topic/partition/offset metadata via standard headers;
- [ ] successful DLQ recovery lets consumer continue to a subsequent valid record;
- [ ] no `processed_events` marker exists for dead-lettered malformed/failing event;
- [ ] simulated DLQ publication failure is visible and not treated as success where practical with current test seam.

### 8.5 Cross-Topic / Group

- [ ] `PaymentCompleted` accepted before reservation fact exists;
- [ ] `TicketScanned` accepted before issue correlation exists;
- [ ] consumer never synchronously waits for source service;
- [ ] new `analytics-service-v1` group consumes retained earliest records when no committed offset exists.

---

## 9. Verification Commands

```bash
cd backend
./mvnw -pl services/analytics-service -am test
```

If analytics compatibility changed source contracts:

```bash
./mvnw -pl services/event-service,services/reservation-service,services/payment-service,services/ticket-service,services/analytics-service -am test
```

No affected producer/consumer suite may be skipped.

---

## 10. Independent Review Focus

Review failure windows, not style only:

- race-free event claim;
- DB transaction relative to RECORD acknowledgement;
- exact retry/non-retryable/DLQ policy;
- duplicate vs distinct semantic event distinction;
- cross-topic out-of-order handling;
- safe payload deserialization;
- actual final P12/P13 contracts;
- no synchronous source-service lookup;
- no write-path coupling;
- low-cardinality observability.

---

## 11. Acceptance Criteria

- [ ] Final upstream analytics event matrix is verified against implemented P12/P13 producer code.
- [ ] Analytics consumes canonical EventEnvelope events from reservation/payment/ticket/event topics.
- [ ] Database effects are idempotent by eventId under duplicate and concurrent delivery.
- [ ] Event claim rolls back whenever projection fails.
- [ ] Kafka acknowledgement cannot make an uncommitted analytics event disappear.
- [ ] Unknown events are safe; malformed known events go to the explicit analytics DLQ and are not falsely processed.
- [ ] Retry policy is exactly documented/configured/tested.
- [ ] Cross-topic out-of-order arrival is accepted without REST/DB coupling to source services.
- [ ] Critical independent review passes.

---

## 12. Execution Entry Point

```text
Implement TASK-P14-002 using the SeatFlow autonomous orchestration workflow.
First audit the final P12/P13 event contracts. Match SeatFlow's existing RECORD + DLQ consumer pattern, use analytics-service-v1, and do not create analytics-only duplicate domain events.
```
