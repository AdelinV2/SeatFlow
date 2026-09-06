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
- **Affected Critical Invariants:** `financial correctness; operational-vs-financial aggregate grain; replay/idempotency; cross-topic ordering; ADMIN authorization; analytics/write-path isolation; CSV safety; multi-currency correctness; no PII`

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
7. multi-currency money is never mixed **and operational counts are never multiplied by currency-keyed rows**;
8. ADMIN-only APIs and export remain protected;
9. malformed known events are visible, dead-lettered according to P14-002, and do not become falsely processed;
10. analytics can be fully unavailable while reservation/payment/ticket customer flows remain architecturally independent;
11. frontend clearly handles Test Mode, empty/error states, null rates/capacity, filters, races, and export behavior.

No requirement should be marked done only because a unit test mocked the component that embodies the risk.

---

## 3. Critical Invariants & Failure Modes

### 3.1 Test Integrity Invariants

- [ ] PostgreSQL behavior uses PostgreSQL/Testcontainers for SQL constraints, `ON CONFLICT`, transaction rollback, and query semantics. H2 is not accepted as proof.
- [ ] Kafka delivery/retry/group/offset/DLQ behavior uses a real Kafka-compatible test broker/Testcontainers for scenarios that depend on broker semantics.
- [ ] Duplicate tests publish the same `EventEnvelope.eventId`; they do not merely call a mocked service twice.
- [ ] Out-of-order tests truly vary publish order across topic families while respecting producer/topic ordering guarantees where applicable.
- [ ] Rebuild test starts from empty analytics read-model storage and replays canonical events.
- [ ] Money assertions use exact integer minor units.
- [ ] Multi-currency fixture includes at least one **single event session** with financial activity in two currencies so aggregate-grain bugs can be detected.
- [ ] Security tests use real Spring Security request flow with representative JWT authorities or the repository's canonical test support.
- [ ] CSV tests inspect actual response bytes/text, row types, headers, escaping, and injection protection.
- [ ] System isolation evidence does not start analytics and then claim outage tolerance; analytics must genuinely be absent/stopped.
- [ ] Tests do not weaken production config just to pass (for example permitting admin endpoints in test security or enabling Kafka auto-commit).

### 3.2 Failure Modes the Suite Must Catch

A deliberately broken implementation should fail tests if it:

- removes `processed_events` uniqueness;
- acknowledges before DB commit;
- commits the processed marker while the projection rolls back;
- increments revenue on duplicate delivery;
- joins one operational row to multiple currency rows and doubles reservation/ticket/payment counts;
- mixes currencies in summary/top/timeseries;
- counts scan attempts rather than unique scanned tickets;
- counts refund request/pending rather than completed refund;
- lets old expiration overwrite confirmed/refunded outcome;
- drops payment when reservation event arrives later;
- lets a provisional refund create negative/misleading net revenue before payment completion is known;
- returns NaN/Infinity rate;
- makes `/api/admin/analytics/**` accessible to USER;
- exports formula-injection text unsanitized;
- repeats operational CSV counts once per revenue currency;
- silently truncates CSV/filter options without the explicit contract;
- queries a source service during API rendering;
- wires reservation/payment/ticket/event service `depends_on` analytics;
- hides Stripe Test Mode in frontend.

---

## 4. Dependencies / Prerequisites

- P14-001 through P14-006 implementation complete on the task integration branch.
- Phase 12 and 13 producer event contracts/tests complete.
- Local Docker and test infrastructure can start PostgreSQL and Kafka.
- The implementation agent must first inventory existing test utilities so this task reuses shared containers/build conventions rather than creating conflicting parallel infrastructure.

---

## 5. Exact File Inventory

Exact new filenames should follow implemented packages, but the final suite must contain focused equivalents of:

### Backend analytics integration tests

- `[NEW]` `backend/services/analytics-service/src/test/java/com/seatflow/analytics/integration/AnalyticsKafkaProjectionIntegrationTest.java`
- `[NEW]` `backend/services/analytics-service/src/test/java/com/seatflow/analytics/integration/AnalyticsReplayDeterminismIntegrationTest.java`
- `[NEW]` `backend/services/analytics-service/src/test/java/com/seatflow/analytics/integration/AnalyticsOutOfOrderIntegrationTest.java`
- `[NEW]` `backend/services/analytics-service/src/test/java/com/seatflow/analytics/integration/AnalyticsAggregateGrainIntegrationTest.java`
- `[NEW]` `backend/services/analytics-service/src/test/java/com/seatflow/analytics/integration/AdminAnalyticsApiIntegrationTest.java`
- `[NEW]` `backend/services/analytics-service/src/test/java/com/seatflow/analytics/integration/AnalyticsCsvExportIntegrationTest.java`
- `[NEW]` shared fixture/builder classes under analytics test sources for canonical `EventEnvelope` events

### Contract/regression tests

- `[MODIFY]` final P12/P13 reservation/payment/ticket/event producer contract tests where needed to assert fields consumed by analytics
- `[MODIFY]` API Gateway `RouteConfigurationTest` if not already fully covered by P14-001/P14-004
- `[MODIFY]` frontend analytics service/component specs from P14-005/P14-006

### Optional focused verification script

- `[NEW]` `infra/scripts/verify-analytics-isolation.sh` only if a simple, deterministic repository/runtime check materially improves repeatability. It verifies architecture/configuration facts; it does not replace functional tests.

Do not add duplicate test frameworks or a second frontend test runner.

---

## 6. Canonical Integration Fixture

Create deterministic reusable event histories with fixed UUIDs and `Instant`s. Do not use random IDs/times in assertions unless seeded and printed on failure.

Minimum baseline scenario, with event names adjusted only to final canonical contracts:

### Session S1 / Event E1 / RON

- session S1 metadata event: capacity snapshot 4 if final contract safely supplies it;
- reservation R1 created/held for S1, 2 seats;
- payment P1 completed for R1: `20_000 RON` minor units;
- reservation R1 confirmed;
- tickets T1/T2 issued;
- T1 accepted scan;
- R1 later receives completed full refund `20_000 RON` through P13 flow;
- tickets T1/T2 revoked according to final P13 semantics.

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

Expected:

```text
EUR gross = 5_000
```

### Same-session multi-currency grain fixture: Session S4 / Event E3

Use this specifically to prove counts are not duplicated by financial currency rows:

- reservation R4 created/confirmed for S4, one ticket, payment P4 = `10_000 RON`;
- reservation R5 created/confirmed for **the same S4**, one ticket, payment P5 = `2_000 EUR`;
- no refunds in this fixture.

Expected persisted/query state:

```text
exactly 1 event_session_metrics row for S4
reservations_created = 2
reservations_confirmed = 2
payments_succeeded = 2
tickets_issued = 2

exactly 2 event_session_revenue_metrics rows for S4:
  RON gross = 10_000
  EUR gross = 2_000

no mixed-currency 12_000 total
no operational count equals 4 because of a two-currency join
```

Use both currencies on the same UTC date as well so `daily_operational_metrics` vs `daily_revenue_metrics` grain is tested.

### Failure evidence fixture

- payment P6 emits canonical failure evidence for a reservation, with no successful completion unless a dedicated recovery test later completes it;
- `payments_with_failure` increases according to P14-003 semantics;
- gross remains unchanged solely because of failure evidence.

Use UTC timestamps spanning at least two dates so daily bucket and inclusive date-range behavior are exercised.

---

## 7. Required Test Scenarios

### 7.1 Duplicate Delivery: Same Event ID

For every financial/count-sensitive event family at minimum:

```text
ReservationHeld or final creation equivalent
PaymentCompleted
PaymentRefunded
TicketIssued
accepted TicketScanned equivalent
final TicketRevoked equivalent
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

Assert exactly one claim succeeds and exactly one projection effect is visible. Exercise the actual `INSERT ... ON CONFLICT DO NOTHING` transaction path, not a synchronized mock.

### 7.3 Handler Failure After Claim

Use a controlled test handler/failure injection that throws **after** `processed_events` claim and before projection transaction commit.

Assert:

1. transaction rolls back;
2. eventId is absent from `processed_events` after failed attempt;
3. fact/aggregate mutation is absent;
4. retry of same eventId can succeed exactly once.

Do not implement a production-only failure switch; use test configuration/spy at the handler boundary.

### 7.4 Commit Then Redelivery Window

Simulate the equivalent state where DB transaction committed but the Kafka record is redelivered because offset acknowledgement/commit was not durable yet.

Assert the second delivery sees the processed marker and cannot change facts/aggregates.

This proves distributed Kafka/PostgreSQL atomicity is not required for projection correctness.

### 7.5 Retry and Analytics DLQ Contract

Exercise the actual P14-002 `DefaultErrorHandler`/recoverer configuration:

- known validation error -> no transient retry, dead-letter to `seatflow.analytics.events.dlq`;
- transient handler/DB failure -> initial attempt + exactly 3 fixed-backoff retries before DLQ;
- successful DLQ recovery allows later valid records to continue;
- dead-lettered event has no `processed_events` marker;
- original topic/partition/offset metadata is preserved by recoverer headers;
- simulated DLQ publishing failure remains visible and is not acknowledged as success where the existing test seam permits.

### 7.6 Out-of-Order Convergence

Run the same logical fixture in separate clean analytics states with alternate cross-topic orders.

Example:

**Order A**

```text
ReservationHeld -> PaymentCompleted -> ReservationConfirmed -> TicketIssued -> TicketScanned
```

**Order B**

```text
PaymentCompleted -> TicketScanned -> ReservationConfirmed -> TicketIssued -> ReservationHeld
```

Where same-topic producer ordering makes a reversal impossible, preserve that ordering and move records only relative to other topic families.

Assert final facts/aggregates are equivalent for all order-independent semantics.

Also test:

- refund before/after reservation-refund evidence;
- scan before ticket issue;
- final P13 revocation evidence before ticket correlation where the contract allows it;
- refund observed before payment completion during replay, then later completion reconciliation.

### 7.7 Older Event Cannot Overwrite Newer State

Deliver newer session metadata then older metadata; confirmed/refunded reservation evidence then stale earlier lifecycle evidence where replay can surface it.

Assert current projected metadata/canonical analytics outcome remain correct while trusted historical timestamps remain consistent.

### 7.8 Scan Uniqueness

Test:

- same scan envelope duplicate;
- multiple distinct accepted/scan-attempt events for the same ticket if the final scanner contract can emit them;
- accepted scan then ticket revocation.

Assert `tickets_scanned` represents one unique accepted attendance unit per ticket, not event count.

### 7.9 Financial / Refund Correctness

Using fixed fixtures:

- successful completion -> gross exact;
- failure evidence -> gross unchanged;
- completed full refund -> refunded exact, gross preserved, net exact;
- duplicate refund -> no change;
- refund greater than known completed amount -> configured malformed/contract failure path, never silent clamping/negative net;
- refund request/pending alone -> no refunded revenue;
- refund-first provisional fact -> no financial aggregate until completion/currency become known and valid;
- RON/EUR remain separate through DB, API, CSV, and frontend fixture.

### 7.10 Operational-vs-Financial Aggregate Grain

Use the same-session S4 RON+EUR fixture.

Persisted assertions:

- exactly one `event_session_metrics` row for S4;
- exactly one `daily_operational_metrics` row per S4 operational date grain;
- exactly two currency-keyed revenue rows where RON and EUR occur on the same session/date;
- reservation/ticket/payment operational counts equal source facts, not source facts multiplied by number of currencies.

API assertions:

- paged `/sessions` contains S4 exactly once and `totalElements` is unaffected by two revenue rows;
- S4 `revenueByCurrency[]` has RON and EUR;
- summary/timeseries count metrics are currency-neutral and not duplicated;
- financial sort/top requires/uses one explicit currency according to P14-004.

This test is mandatory because a naïve SQL join can pass all single-currency tests while corrupting counts in a multi-currency session.

### 7.11 Rebuild Determinism

1. start empty analytics schema;
2. publish canonical retained event set;
3. wait on observable DB/consumer condition with bounded timeout, not arbitrary long sleep;
4. capture normalized facts/aggregates/API response excluding intentionally generated processing timestamps;
5. clear/recreate only analytics read-model state and reset/replay test consumer state as appropriate;
6. replay the exact same event set;
7. capture the normalized snapshot again;
8. assert equality.

### 7.12 Malformed Known vs Unknown Event

- unknown event type on a subscribed shared topic -> ignored/acknowledged safely, no processed marker;
- known event missing a required field -> no marker/aggregate mutation and P14-002 non-retryable DLQ policy;
- subsequent valid event still processes.

### 7.13 API Security and Semantics

With real projected fixtures, assert:

- USER -> forbidden;
- ADMIN -> summary/timeseries/sessions/top success;
- 30-day default under fixed Clock;
- invalid/range-too-large rejected;
- zero denominator -> null ratio;
- same-session multi-currency arrays/series preserved without count multiplication;
- financial sort/top without required currency rejected;
- Test Mode flags true;
- no PII JSON fields;
- freshness/eventual consistency fields present;
- no query triggers an HTTP source-service dependency.

### 7.14 CSV Security / Aggregate-Grain Contract

Assert actual P14-006 endpoint response:

- ADMIN-only;
- exact content type and safe `Content-Disposition`;
- exact stable column order;
- deterministic row order;
- S4 same-session dual-currency export has one `OPERATIONS` row for a matching operational date grain and separate RON/EUR `REVENUE` rows;
- reservation/ticket counts occur only on `OPERATIONS` rows and therefore cannot be double-counted through revenue currency;
- money/financial counts occur only on `REVENUE` rows with currency + Test Mode;
- no mixed-currency total;
- no PII;
- commas/quotes/newlines valid;
- formula-leading text neutralized;
- exactly configured max rows accepted;
- max+1 fails explicitly with no partial/truncated file.

### 7.15 Filter-Option Bounds

- <=500 projected options -> full list, `truncated=false`;
- 501 matching options -> exactly 500 deterministic items, `truncated=true`, correct `totalProjected`;
- frontend shows the narrowing warning;
- no hidden source-service query is introduced to resolve labels.

### 7.16 Frontend Contract / State Regression

Frontend specs collectively prove:

- API models/service paths match backend;
- same-session multi-currency financial cards/series remain separate;
- operational KPI counts remain source counts, not currency-multiplied values;
- visible Stripe Test Mode;
- null rates/capacity -> unavailable;
- all-zero chart -> no NaN/invalid SVG;
- loading != valid empty != unavailable/error;
- filter reset/session dependency behavior;
- A -> B request race leaves B visible;
- truncated-options warning behavior;
- CSV Blob download/error/too-large behavior;
- existing Admin Portal user/venue/health tests remain green.

### 7.17 Analytics Isolation / Outage Smoke

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
5. start analytics later and verify retained/new events project asynchronously.

Do not weaken this into only checking that business application contexts start.

If a full external Stripe Test Mode flow is unsuitable for automated CI, use the repository's existing mocked Stripe gateway/integration seam while keeping analytics genuinely unavailable. The key assertion is no reverse dependency.

---

## 8. Test Data / Synchronization Rules

- use fixed UUIDs and UTC instants;
- isolate test DB/topic/group state between scenarios;
- unique consumer group suffix per integration test only when necessary for isolation; production config remains `analytics-service-v1`;
- wait on an observable condition (processed event count, repository state, current repo Awaitility-equivalent) with bounded timeout;
- do not use multi-second blind sleeps as primary synchronization;
- clean only analytics-owned data;
- never truncate another service database from analytics tests;
- test logs must not leak secrets or PII.

---

## 9. Verification Commands

At minimum:

```bash
cd backend
./mvnw -pl services/analytics-service,services/api-gateway -am test
```

If P12/P13 event contracts changed for analytics compatibility:

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

Run `verify-analytics-isolation.sh` if P14-007 creates it.

Final QA evidence must list the exact commands actually executed and distinguish successful automated tests from manual smoke checks.

---

## 10. Independent Review and QA Focus

Use an independent reviewer from implementation where orchestration permits. The reviewer must reason through failure windows and recalculate fixed fixtures manually.

Mandatory review questions:

1. Can any duplicate event increase a count/money value?
2. Can a failed projection leave its event marked processed?
3. Can payment/ticket/refund events arriving before related facts be lost?
4. Can one session with two currencies duplicate operational counts through schema/query/API/CSV joins?
5. Can gross/refund/net mix currencies?
6. Can failure/refund-pending/provisional-refund state affect revenue incorrectly?
7. Can repeated scans inflate attendance?
8. Can stale events overwrite newer session/lifecycle state?
9. Can a non-admin access analytics or CSV?
10. Can a CSV text field execute as a spreadsheet formula?
11. Can export/filter options truncate silently?
12. Can analytics outage block/degrade checkout due to an explicit dependency?
13. Can frontend mistake error/unavailable data for legitimate zero metrics?
14. Does any dashboard money look like production rather than Test Mode?

Any unresolved answer is a Phase 14 blocker.

---

## 11. Acceptance Criteria

- [ ] Real PostgreSQL/Kafka integration tests cover duplicate, transaction failure, redelivery, retry/DLQ, and out-of-order scenarios.
- [ ] Same retained event set rebuilds to the same normalized analytics state.
- [ ] Canonical fixtures prove exact refund/gross/net and unique attendance semantics.
- [ ] Same-session RON+EUR fixture proves one operational aggregate grain and separate currency revenue rows with no count multiplication.
- [ ] Multi-currency separation holds from persistence through API, CSV, and frontend.
- [ ] Malformed known events cannot become falsely processed and follow explicit DLQ behavior.
- [ ] ADMIN APIs/export, filter bounds, and CSV injection/grain protections are integration-tested.
- [ ] Analytics outage/isolation is proven with configuration and runtime evidence.
- [ ] Frontend analytics and existing Admin Portal tests/build pass.
- [ ] No PII or cross-service database/API coupling is introduced.
- [ ] Independent critical review and final QA have no unresolved blocker.

---

## 12. Execution Entry Point

```text
Implement TASK-P14-007 using the SeatFlow autonomous orchestration workflow.
Treat this as the final verification gate, not an opportunity for unrelated refactoring. Build tests that fail for duplicate inflation, transaction-window/DLQ bugs, cross-topic ordering bugs, operational-count multiplication through currency joins, mixed money, weak authorization, unsafe CSV, or reverse analytics dependency.
```
