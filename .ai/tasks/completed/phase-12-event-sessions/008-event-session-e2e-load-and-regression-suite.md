# TASK-P12-008: Prove Event Session Correctness with E2E, Concurrency, Migration, and Load Regression

## 1. Task Metadata

- **Task ID:** `TASK-P12-008`
- **Git Branch:** `test/p12-event-session-regression-suite`
- **Target Module:** `backend`, `frontend`, integration/E2E test infrastructure`
- **Phase:** `Phase 12 - Multiple Event Sessions / Showings`
- **Related ADRs:** `ADR-011-event-sessions-booking-boundary.md`
- **Status:** `READY FOR IMPLEMENTATION`

### Orchestration Metadata

- **Complexity:** `5`
- **Failure Risk:** `Critical`
- **Verification Strength:** `Strong`
- **Required Review Depth:** `Critical`
- **Preferred Workflow:** `critical`
- **Affected Critical Invariants:** `Zero double booking; 10-seat maximum; 15-minute hold; idempotency; payment enforcement; transactional outbox; session isolation`

---

## 2. Objective

Create the final Phase 12 verification suite and evidence proving that multiple sessions work end-to-end under realistic database, Kafka, WebSocket, browser, retry, migration, and concurrency conditions. This task does not add new product features; failures must result in fixes to the owning task/service, not weakened assertions.

---

## 3. Critical Invariants & Failure Modes

The suite must prove, not merely mock:

- [ ] Same seat + same session cannot double-book.
- [ ] Same seat + different sessions is independent.
- [ ] 10-seat maximum remains enforced.
- [ ] 15-minute hold expiry remains authoritative.
- [ ] PostgreSQL remains source of truth when realtime/cache is stale/unavailable.
- [ ] Reservation/payment creation idempotency survives retries.
- [ ] Transactional outbox never loses the state/event coupling under tested failure modes.
- [ ] Payment/ticket/notification preserve exact selected session.
- [ ] Realtime has no cross-session leakage.
- [ ] Legacy data migrates deterministically.
- [ ] Authorization/ownership prevents cross-organizer session mutation.

---

## 4. Dependencies / Prerequisites

- P12-001 through P12-007 complete.
- Existing Docker/Testcontainers/Kafka/Stripe-test/browser test infrastructure available.

---

## 5. Exact Test Scenarios

### Scenario A — Basic Two-Session Customer Flow

1. Organizer creates event E with sessions A and B in same hall.
2. Customer opens E; sees one event detail and both sessions.
3. Customer selects A, sees A availability, holds A1, pays/confirms according to test payment contract.
4. Ticket and notification identify A and exact A date/time.
5. Customer selects B; A1 is independently available unless another B booking exists.

### Scenario B — Cross-Session Isolation

- Hold A1 in A.
- Query B: A1 must remain available.
- WebSocket A topic receives A update; B topic receives none.
- Hold/reserve A1 in B succeeds.
- Expire/cancel A hold: B reservation remains unchanged.

### Scenario C — Same-Session Concurrency

Run a coordinated barrier with at least 20 contenders for the same `(A, A1)` against real PostgreSQL. Assert exactly one authoritative successful hold/reservation under the current API contract; all losers receive deterministic conflict/unavailable outcomes; DB contains no duplicate active booking.

### Scenario D — Parallel Sessions Under Load

Run concurrent traffic across >=2 sessions of the same event and shared hall. Assert contention/locks are partitioned by session and no global event-level lock serializes unrelated sessions beyond unavoidable DB work. Record p50/p95/p99 for the tested local environment as evidence, but do not encode hardware-specific pass/fail latency thresholds unless the repo already defines them.

### Scenario E — Hold Expiry

Use controllable/test clock where supported. Prove 15-minute expiry boundary, no early release, release after expiry, and no cross-session release.

### Scenario F — Retry / Idempotency

Repeat create/checkout/payment webhook messages using same idempotency key/event IDs. Assert no duplicate reservation/payment/ticket and safe duplicate notification handling per current dedupe semantics.

### Scenario G — Migration

Start from a representative pre-P12 database snapshot/fixtures with legacy events and active historical reservation/hold rows supported by migration policy. Run all migrations and migration utility. Assert one session/event, exact timestamp parity, zero null dependent session IDs, and preserved booking/ticket relationships.

### Scenario H — Security

Organizer A cannot create/update/delete sessions under Organizer B's event; customer cannot reach organizer mutation endpoints; event/session path mismatch cannot disclose or mutate another object.

### Scenario I — Stale Client / Reconnect

Customer switches A -> B while an A availability HTTP response and/or WebSocket event is delayed. Late A data must not mutate B UI. Disconnect/reconnect while B selected: subscribe B only, authoritative REST refresh reconciles state.

### Scenario J — Legacy API Rejection

Attempt pre-P12 event-scoped booking and realtime routes/legacy schedule writes. Assert no request can silently infer/choose a session.

---

## 6. Test Implementation Rules

- PostgreSQL concurrency/migration properties require Testcontainers or the repository's real integration DB, not H2/mocks.
- Kafka retry/outbox tests use real broker/Testcontainers where existing infrastructure supports it.
- Browser state/race tests use the existing Angular E2E/browser harness.
- External Stripe network calls are not required; use the project's established test/webhook simulation while preserving signature/idempotency logic under test.
- Every concurrency test has a deterministic start barrier and inspects final DB state, not only HTTP responses.
- Tests must clean up/namespace data and be repeatable locally/CI.
- Do not “fix” flakiness with arbitrary sleeps; use latches/poll-until with bounded deterministic timeouts.

---

## 7. Risk-to-Test Traceability

| Risk | Required proof |
|---|---|
| cross-session false blocking | Scenario B/C/D DB assertions |
| same-session double booking | Scenario C |
| wrong showing on ticket | Scenario A/F |
| stale realtime crossover | Scenario B/I |
| migration data loss | Scenario G |
| legacy fallback survives | Scenario J |
| ownership/IDOR | Scenario H |
| 10-seat/15-minute regressions | Scenario E + explicit >10 test |
| retry duplicates | Scenario F |
| outbox divergence | failure-path integration assertions |

---

## 8. Verification Commands

Run targeted modules first, then full regression:

```bash
cd backend
./mvnw test
cd ../frontend
npm run lint
npm test -- --watch=false
npm run build
```

Run repository Docker/integration/E2E commands required for Kafka/WebSocket/browser coverage. Record exact commands and results in the final task completion evidence.

---

## 9. Independent Review Focus

Test oracle quality, whether tests actually exercise real DB locking/constraints, false positives caused by mocks, race-test determinism, migration realism, cross-session topic assertions, and any weakened production invariant introduced to make tests pass.

---

## 10. Acceptance Criteria

- [ ] All scenarios A-J pass.
- [ ] Same-seat cross-session independence and same-session exclusivity are proven against PostgreSQL.
- [ ] Exact session appears in paid/confirmed ticket and notification flow.
- [ ] Migration parity and zero-null reference checks pass.
- [ ] Legacy booking/session inference paths are rejected.
- [ ] Full backend + frontend build/test gates pass.
- [ ] Critical independent review finds no unresolved P0/P1 issues.
- [ ] Final QA is `PASS` or valid `PASS WITH NON-BLOCKING NOTES`.
- [ ] No active `.ai/tmp/review-*.md` remains.

---

## 11. Completion Evidence

When completed, record in the task completion/final orchestration output:

- exact commit SHA;
- test commands and pass counts;
- concurrency contender count and final DB assertion;
- migration fixture counts before/after;
- browser/E2E scenario result;
- actual provider/model/effort used for implementation/review/QA per SeatFlow orchestration rules.

---

## 12. Execution Entry Point

```text
Implement TASK-P12-008 using the SeatFlow autonomous orchestration workflow.
```
