# TASK-P14-007: Prove Projection Idempotency, Replay Determinism, Isolation, and End-to-End Analytics Behavior

## 1. Task Metadata

- **Task ID:** `TASK-P14-007`
- **Git Branch:** `test/p14-007-analytics-integration-verification`
- **Target Module:** `backend/services/analytics-service`, API Gateway, affected P12/P13 producer contract tests, frontend analytics tests, Docker smoke verification
- **Phase:** `Phase 14 - Admin Analytics & Operations Dashboard`
- **Related Specs:** all Phase 14 tasks, `.ai/architecture/05-messaging-and-outbox.md`, `.ai/architecture/09-post-mvp-evolution.md`
- **Related ADRs:** `.ai/decisions/ADR-013-analytics-event-driven-read-model.md`
- **Status:** `READY FOR IMPLEMENTATION`

### Orchestration Metadata

- **Complexity:** `5`
- **Failure Risk:** `Critical`
- **Verification Strength:** `Maximum`
- **Required Review Depth:** `Critical + independent QA`
- **Preferred Workflow:** `critical`
- **Affected Critical Invariants:** `financial correctness; replay/idempotency; cross-topic ordering; ADMIN authorization; analytics/write-path isolation; CSV safety; multi-currency correctness; no PII`

---

## 2. Objective

Close Phase 14 with high-confidence tests that exercise the **real failure modes of an event-driven analytics read model**, not only isolated mapper/controller happy paths.

This task must prove all of the following with executable evidence:

1. duplicate Kafka delivery cannot inflate projections;
2. failure after dedup claim but before projection completion is retryable;
3. crash/redelivery after DB commit is safe;
4. cross-topic event reordering converges to the same final analytics state;
5. rebuilding from the same retained event set is deterministic;
6. refunds and ticket revocations produce correct gross/refunded/net/attendance results;
7. multi-currency metrics are never mixed;
8. ADMIN-only APIs and export remain protected;
9. malformed known events are visible and do not become falsely processed;
10. analytics can be fully unavailable while reservation/payment/ticket customer flows remain architecturally independent;
11. frontend clearly handles Test Mode, empty/error states, null rates/capacity, filters, races, and export behavior.

No requirement should be marked done only because a unit test mocked the component that embodies the risk.

---

## 3. Critical Invariants & Failure Modes

### 3.1 Test Integrity Invariants

- [ ] PostgreSQL behavior uses PostgreSQL/Testcontainers for SQL constraints, `ON CONFLICT`, transaction rollback, and query semantics. H2 is not accepted as proof.
- [ ] Kafka delivery/retry/group/offset behavior uses a real Kafka-compatible test broker/Testcontainers for the integration scenarios that depend on broker semantics.
- [ ] Duplicate tests publish the same `EventEnvelope.eventId`; they do not merely call a mocked service twice.
- [ ] Out-of-order tests truly vary publish order across topic families.
- [ ] Rebuild test starts from empty analytics read-model storage and replays canonical events.
- [ ] Money assertions use exact integer minor units.
- [ ] Multi-currency fixture contains at least two currencies in the same selected date range and, where supported, same event/session.
- [ ] Security tests use real Spring Security request flow with representative JWT authorities or the repository's canonical test support.
- [ ] CSV tests inspect actual response bytes/text, headers, escaping, and injection protection.
- [ ] System isolation evidence does not start analytics and then claim outage tolerance; analytics must genuinely be absent/stopped.
- [ ] Tests do not weaken production config just to pass (e.g. permitting admin endpoints in test security or enabling auto-commit).

### 3.2 Failure Modes the Suite Must Catch

A deliberately broken implementation should fail tests if it:

- removes `processed_events` uniqueness;
- acknowledges before DB commit;
- commits marker while handler rolls back;
- increments revenue on duplicate;
- mixes currencies in summary/top/timeseries;
- counts scan attempts rather than unique scanned tickets;
- counts refund request instead of completed refund;
- lets old expiration overwrite confirmed/refunded outcome;
- drops payment when reservation event arrives later;
- returns NaN/Infinity rate;
- makes `/api/admin/analytics/**` accessible to USER;
- exports formula-injection text unsanitized;
- silently truncates CSV;
- queries a source service during API rendering;
- wires reservation/payment/ticket `depends_on` analytics;
- hides Stripe Test Mode in frontend.

---

## 4. Dependencies / Prerequisites

- P14-001 through P14-006 implementation complete on the task integration branch.
- Phase 12 and 13 producer event contracts/tests complete.
- Local Docker and test infrastructure can start PostgreSQL and Kafka.
- The implementation agent must first inventory existing test utilities so this task reuses shared containers/build conventions rather than creating conflicting parallel infrastructure.

---

## 5. Exact File Inventory

Exact new filenames should follow the implemented packages, but the final suite must contain focused equivalents of:

### Backend analytics integration tests

- `[NEW]` `backend/services/analytics-service/src/test/java/com/seatflow/analytics/integration/AnalyticsKafkaProjectionIntegrationTest.java`
- `[NEW]` `backend/services/analytics-service/src/test/java/com/seatflow/analytics/integration/AnalyticsReplayDeterminismIntegrationTest.java`
- `[NEW]` `backend/services/analytics-service/src/test/java/com/seatflow/analytics/integration/AnalyticsOutOfOrderIntegrationTest.java`
- `[NEW]` `backend/services/analytics-service/src/test/java/com/seatflow/analytics/integration/AdminAnalyticsApiIntegrationTest.java`
- `[NEW]` `backend/services/analytics-service/src/test/java/com/seatflow/analytics/integration/AnalyticsCsvExportIntegrationTest.java`
- `[NEW]` shared fixture/builder classes under analytics test sources for canonical `EventEnvelope` events

### Contract/regression tests

- `[MODIFY]` final P12/P13 reservation/payment/ticket/event producer contract tests where needed to assert fields consumed by analytics
- `[MODIFY]` API Gateway route tests if not already fully covered
- `[MODIFY]` frontend analytics service/component specs from P14-005/P14-006

### Optional focused verification script

- `[NEW]` `infra/scripts/verify-analytics-isolation.sh` only if a simple, deterministic repository/runtime check materially improves repeatability. It must verify architecture/configuration facts, not pretend to replace functional tests.

Do not add duplicate test frameworks or a second frontend test runner.

---

## 6. Canonical Integration Fixture

Create one deterministic, reusable event history with fixed UUIDs and `Instant`s. Do not use random IDs/times in assertions unless seeded and printed on failure.

Minimum scenario, with values adjusted only to final event contract names:

### Session S1 / Event E1 / RON

- session S1 metadata event: capacity snapshot 4 if final contract safely supplies it;
- reservation R1 created/held for S1, 2 seats;
- payment P1 completed for R1: `20_000 RON` minor units;
- reservation R1 confirmed;
- tickets T1/T2 issued;
- T1 accepted scan;
- R1 later receives completed full refund `20_000 RON` through P13 flow;
- tickets T1/T2 revoked according to P13 semantics.

Expected history-level facts:

```text
gross = 20_000 RON
refunded = 20_000 RON
net = 0 RON
one successful payment
one completed refund
2 issued tickets
2 revoked tickets
1 uniquely scanned ticket
reservation historically created + confirmed + refunded
```

### Session S2 / Event E1 / RON

- reservation R2 created then expired;
- no completed payment;
- no issued ticket.

Expected:

```text
created += 1
expired += 1
gross += 0
```

### Session S3 / Event E2 / EUR

- reservation R3 created/confirmed;
- payment P3 completed: `5_000 EUR` minor units;
- one ticket issued and scanned.

Expected currency separation:

```text
RON gross remains 20_000
EUR gross is 5_000
no 25_000 mixed-currency total exists
```

### Failure attempt fixture

- payment P4 failure for a reservation, with no successful completion in the fixture unless a dedicated recovery test later completes it.
- failure count increments according to P14-003 semantics; gross remains unchanged.

Use UTC timestamps spanning at least two dates so daily bucket and inclusive date-range behavior are exercised.

---

## 7. Required Test Scenarios

### 7.1 Duplicate Delivery: Same Event ID

For every financial/count-sensitive event family at minimum:

```text
ReservationHeld
PaymentCompleted
PaymentRefunded
TicketIssued
TicketScanned
TicketRevoked
```

publish the exact same envelope twice.

Assert:

- one `processed_events` row for eventId;
- fact state correct;
- aggregate values unchanged by duplicate;
- duplicate metric increments if exposed;
- no exception poisons the consumer for subsequent records.

### 7.2 Concurrent Duplicate Claim

Drive two concurrent processing attempts for the same eventId against real PostgreSQL.

Assert exactly one claim succeeds and exactly one projection effect is visible. The test must exercise the actual `INSERT ... ON CONFLICT DO NOTHING` transaction path, not a synchronized mock.

### 7.3 Handler Failure After Claim

Use a controlled test handler/failure injection that throws **after** `processed_events` claim and before projection transaction commit.

Assert:

1. transaction rolls back;
2. eventId is absent from `processed_events` after failed attempt;
3. source fact/aggregate mutation is absent;
4. retry of same eventId succeeds exactly once.

Do not implement a production-only failure switch; use test configuration/spy at the handler boundary.

### 7.4 Commit Then Redelivery Window

Simulate/construct the equivalent state where DB transaction committed but Kafka record is redelivered because offset acknowledgement/commit was not durable yet.

Assert second delivery sees processed marker and cannot change aggregate.

This is the key proof that distributed Kafka/Postgres atomicity is not required for correctness.

### 7.5 Out-of-Order Convergence

Run the same logical fixture in at least these orders from separate clean analytics DB states:

**Order A — normal-ish**

```text
ReservationHeld -> PaymentCompleted -> ReservationConfirmed -> TicketIssued -> TicketScanned
```

**Order B — cross-topic reordered**

```text
PaymentCompleted -> TicketScanned -> ReservationConfirmed -> TicketIssued -> ReservationHeld
```

Where domain-topic ordering constraints make a specific same-topic reversal impossible, retain valid order inside that producer/topic but move entire topic records relative to other topics.

Assert final facts/aggregates are equivalent for all order-independent semantics.

Also test refund/reservation-refund/ticket-revoke cross-topic variations.

### 7.6 Older Event Cannot Overwrite Newer State

Deliver newer session metadata then older metadata; confirmed/refunded reservation evidence then stale earlier lifecycle event as allowed by replay conditions.

Assert projected current/session metadata and canonical analytics outcome remain correct while historical timestamps remain consistent.

### 7.7 Scan Uniqueness

Test:

- same scan envelope duplicate;
- two distinct scan-attempt events for same ticket if final scanner contract emits them;
- accepted scan then ticket revocation.

Assert `tickets_scanned` represents unique accepted attendance, not event count.

### 7.8 Financial / Refund Correctness

Using exact fixture:

- successful completion -> gross exact;
- failure -> gross unchanged;
- completed full refund -> refunded exact, gross preserved, net exact;
- duplicate refund -> no change;
- refund greater than completed amount -> known malformed/contract failure path, never negative net by silent clamping;
- refund request/pending alone -> no refunded revenue;
- RON/EUR remain separate in DB/API/chart fixture.

### 7.9 Rebuild Determinism

Test process:

1. start empty analytics schema;
2. publish canonical retained event set;
3. wait deterministically for processing (poll DB/condition with bounded timeout; no arbitrary long sleep);
4. capture normalized facts/aggregates/API response excluding generated processing timestamps;
5. clear/recreate only analytics read model and reset/replay test consumer state as appropriate;
6. replay same event set;
7. capture same normalized snapshot;
8. assert equality.

The test should compare meaningful data, not auto-generated DB IDs/timestamps that are intentionally different.

### 7.10 Malformed Known vs Unknown Event

- unknown event type on subscribed shared topic -> ignored/acked safely;
- known event missing required field -> failure policy activated, no processed marker, no aggregate mutation;
- subsequent valid event still processes according to configured error/DLT semantics.

### 7.11 API Security and Semantics

With real projected fixture, assert:

- USER -> forbidden;
- ADMIN -> summary/timeseries/sessions/top success;
- 30-day default under fixed Clock;
- invalid/range-too-large rejected;
- zero denominator -> null ratio;
- multiple currency arrays/series preserved;
- Test Mode flags true;
- no PII JSON fields;
- freshness/eventual consistency fields present;
- no query triggers an HTTP source-service dependency.

### 7.12 CSV Security / Contract

Assert actual endpoint response:

- ADMIN-only;
- content type and safe `Content-Disposition`;
- exact column order;
- stable row order;
- money minor units/currency/Test Mode;
- no PII;
- commas/quotes/newlines valid;
- formula-leading text neutralized;
- row limit fails explicitly, never truncates.

### 7.13 Frontend Contract / State Regression

Frontend specs must collectively prove:

- API models/service paths match backend;
- multi-currency financial cards/series remain separate;
- visible Stripe Test Mode;
- null rates/capacity -> unavailable;
- all-zero chart -> no NaN/invalid SVG;
- empty != error != loading;
- filter reset/session dependency behavior;
- A -> B request race leaves B visible;
- CSV Blob download/error behavior;
- existing Admin Portal user/venue/health tests remain green.

### 7.14 Analytics Isolation / Outage Smoke

Prove absence of reverse dependency.

Static/config checks:

- reservation/payment/ticket/event services contain no client/base URL/service-discovery dependency on `analytics-service`;
- their Docker `depends_on` lists do not include analytics;
- analytics may depend on Kafka/Postgres/Eureka but business services do not depend on analytics.

Runtime smoke, using the lightest existing deterministic flow that does not require production Stripe secrets:

1. keep `analytics-service` stopped/absent;
2. start required business infrastructure/services;
3. exercise an existing reservation/checkout/payment test-mode integration path or the closest deterministic service integration flow already covered by the repo;
4. assert business result is unchanged by analytics absence;
5. start analytics later and verify new/retained events project asynchronously.

Do not weaken this requirement into only checking that business application contexts start.

If a full Stripe test-mode external flow is unsuitable for automated CI, use the repository's existing mocked Stripe gateway/integration seam while keeping analytics genuinely unavailable; the key assertion is no reverse dependency.

---

## 8. Test Data / Synchronization Rules

- use fixed UUIDs and UTC instants;
- isolate test DB/topic/group state between scenarios;
- unique consumer group suffix per integration test only when necessary for isolation; production config remains `analytics-service-v1`;
- wait on observable condition (processed event count, repository state, Awaitility-equivalent already in repo) with bounded timeout;
- do not use multi-second blind sleeps as the primary synchronization mechanism;
- clean only analytics-owned data;
- never truncate another service's database from analytics tests;
- test logs must not leak secret config or PII.

---

## 9. Verification Commands

At minimum:

```bash
cd backend
./mvnw -pl services/analytics-service,services/api-gateway -am test
```

If P12/P13 event contracts were changed for analytics compatibility:

```bash
./mvnw -pl services/event-service,services/reservation-service,services/payment-service,services/ticket-service,services/analytics-service,services/api-gateway -am test
```

Frontend:

```bash
cd ../frontend
npm test -- --watch=false
npm run build
```

Compose/release:

```bash
cd ../docker
docker compose -f docker-compose.yml -f docker-compose.services.yml config
docker compose -f docker-compose.prod.yml config

cd ../
bash infra/scripts/verify-compose-release.sh
```

Run any new `verify-analytics-isolation.sh` if created.

Final QA evidence must list the exact commands actually executed and distinguish successful automated tests from any manual smoke checks.

---

## 10. Independent Review and QA Focus

Use an independent reviewer from implementation where orchestration permits. The reviewer must reason through failure windows and recalculate the canonical fixture manually.

Mandatory review questions:

1. Can any duplicate event increase a count/money value?
2. Can a failed projection leave its event marked processed?
3. Can payment/ticket events arriving before reservation/session data be lost?
4. Can gross/refund/net mix currencies?
5. Can a failed/refund-pending transaction affect revenue?
6. Can repeated scans inflate attendance?
7. Can stale events overwrite newer session/lifecycle state?
8. Can a non-admin access analytics or CSV?
9. Can a CSV text field execute as a spreadsheet formula?
10. Can analytics outage block or degrade checkout because of an explicit dependency?
11. Can frontend mistake error/unavailable data for legitimate zero metrics?
12. Does any dashboard money look like production rather than Test Mode?

Any unresolved answer is a Phase 14 blocker.

---

## 11. Acceptance Criteria

- [ ] Real PostgreSQL/Kafka integration tests cover duplicate, transaction failure, redelivery, and out-of-order scenarios.
- [ ] Same retained event set rebuilds to the same normalized analytics state.
- [ ] Canonical fixture proves exact refund/gross/net and unique attendance semantics.
- [ ] Multi-currency is separated from persistence through API/frontend.
- [ ] Malformed known events cannot become falsely processed.
- [ ] ADMIN APIs/export and CSV injection protections are integration-tested.
- [ ] Analytics outage/isolation is proven with configuration and runtime evidence.
- [ ] Frontend analytics and existing Admin Portal tests/build pass.
- [ ] No PII or cross-service database/API coupling is introduced.
- [ ] Independent critical review and final QA have no unresolved blocker.

---

## 12. Execution Entry Point

```text
Implement TASK-P14-007 using the SeatFlow autonomous orchestration workflow.
Treat this as a verification task, not an opportunity for unrelated refactoring. Build tests that would fail for duplicate inflation, transaction-window bugs, cross-topic ordering bugs, mixed currency, weak authorization, unsafe CSV, or reverse analytics dependency.
```
