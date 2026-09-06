# TASK-P14-003: Build Deterministic Event, Session, Sales, Payment, Refund, and Attendance Projections

## 1. Task Metadata

- **Task ID:** `TASK-P14-003`
- **Git Branch:** `feat/p14-003-business-projections`
- **Target Module:** `backend/services/analytics-service`
- **Phase:** `Phase 14 - Admin Analytics & Operations Dashboard`
- **Related Specs:** `.ai/tasks/phase-14-admin-analytics/000-phase-overview.md`, `.ai/tasks/phase-14-admin-analytics/001-analytics-service-scaffold-schema-and-compose.md`, `.ai/tasks/phase-14-admin-analytics/002-idempotent-kafka-projection-consumers.md`
- **Related ADRs:** `.ai/decisions/ADR-013-analytics-event-driven-read-model.md`
- **Status:** `READY FOR IMPLEMENTATION`

### Orchestration Metadata

- **Complexity:** `5`
- **Failure Risk:** `Critical`
- **Verification Strength:** `Strong`
- **Required Review Depth:** `Critical`
- **Preferred Workflow:** `critical`
- **Affected Critical Invariants:** `financial correctness; no duplicate inflation; out-of-order delivery; event/session identity; refund correctness; attendance uniqueness; multi-currency isolation; aggregate-grain correctness`

---

## 2. Objective

Turn canonical domain events accepted by P14-002 into deterministic analytics-owned fact rows and query-oriented aggregates for:

- reservation creation/confirmation/expiration/refund lifecycle;
- payment success/failure/refund;
- tickets issued/revoked/scanned;
- event/session metadata needed for labels and filters;
- per-session and per-day operational metrics;
- per-session and per-day **currency-specific** financial metrics.

The implementation must favor **correctness under replay, duplicates, retries, and cross-topic reordering** over clever incremental counters.

For the expected SeatFlow portfolio/demo scale, the preferred strategy is:

```text
source event
  -> idempotently update analytics fact row(s)
  -> identify affected session/date keys
  -> identify affected session/date/currency financial keys
  -> deterministically recompute those aggregate rows from analytics-owned facts
```

This avoids `counter = counter + 1` drift when related events arrive late, status changes are replayed, or a producer emits multiple lifecycle events for the same aggregate.

---

## 3. Critical Invariants & Failure Modes

### 3.1 Invariants

- [ ] Aggregates are derivable entirely from `seatflow_analytics` fact tables populated by events.
- [ ] No aggregate query/read requires another service DB or live REST call.
- [ ] Replaying the same logical retained event history from an empty analytics DB produces the same final facts and aggregates.
- [ ] **Operational counts are currency-neutral.** One reservation/ticket/session contributes once regardless of how many financial currencies are present for that session.
- [ ] Financial aggregates are currency-keyed and different currencies are never combined.
- [ ] `gross_revenue_minor` means successful captured/completed payment amount in Stripe Test Mode, not reservation quote, tax preview, hold amount, or frontend total.
- [ ] `refunded_revenue_minor` means completed refund amount, not refund requested/pending amount.
- [ ] `net_revenue_minor = gross_revenue_minor - refunded_revenue_minor` is calculated deterministically; never via floating arithmetic.
- [ ] Failed payment evidence does not reduce gross revenue.
- [ ] A refunded reservation is not treated as new negative revenue unless a completed payment refund event exists.
- [ ] Reservation-created metric uses the project's canonical creation/hold event (currently `ReservationHeld` unless P12 changes it).
- [ ] A reservation contributes at most once to each state-based cohort metric.
- [ ] A ticket contributes at most once to issued, revoked, and attendance facts according to its unique ticket identity.
- [ ] Multiple scan attempts for one ticket cannot inflate attendance above one attendee.
- [ ] Event/session relationship comes from trusted event/session-aware domain payloads, not title matching or timestamp matching.
- [ ] Capacity is nullable unless a trustworthy snapshot exists in the final event contract. Never derive capacity from sold/issued ticket count.
- [ ] Source lifecycle timestamps use `EventEnvelope.occurredAt` or trusted canonical event timestamp; processing time is not substituted for business-event time.
- [ ] Older source events must not overwrite newer fact state merely because they are delivered later.
- [ ] Provisional/unresolved facts may exist, but only fully reconciled financial facts contribute to revenue aggregates.
- [ ] Metrics stay non-negative; a known refund can never be silently clamped to fit a smaller completed amount.

### 3.2 Failure Modes to Prevent

- incrementing revenue twice on duplicate/replay;
- subtracting refund twice;
- duplicating reservation/ticket counts once for RON and once for EUR;
- same reservation counted once as expired and once as confirmed because of stale out-of-order event application;
- PaymentCompleted stored but never linked after ReservationHeld arrives later;
- refund observed before completion creating negative/misleading net revenue;
- scan received before issue event permanently lost;
- session title/status older event overwrites newer snapshot;
- all currencies summed into one KPI;
- refund request counted as completed refund;
- payment with failure evidence later succeeding being treated as if it never succeeded;
- division-by-zero or misleading percentage when denominator is zero;
- occupancy displayed from fabricated capacity;
- rate above 100% caused by mixing unrelated date cohorts.

---

## 4. Dependencies / Prerequisites

- P14-001 schema/runtime complete, including separate operational and financial aggregate tables.
- P14-002 idempotent Kafka boundary complete and final P12/P13 event matrix recorded.
- Phase 12 provides stable `eventSessionId` propagation through reservation/payment/ticket flows.
- Phase 13 provides canonical completed-refund and ticket-revocation events.

If the final P14-001 V1 schema needs an additive column/table/index to support verified final event contracts, create `V2__...sql`; do **not** edit an already applied V1 migration.

---

## 5. Exact File Inventory

Expected implementation areas inside `backend/services/analytics-service`:

- `[NEW]` fact entities/records and repositories for session, reservation, payment, and ticket facts
- `[NEW]` aggregate entities/records and repositories for:
  - `daily_operational_metrics`;
  - `daily_revenue_metrics`;
  - `event_session_metrics`;
  - `event_session_revenue_metrics`.
- `[NEW]` `.../projection/ReservationProjectionHandler.java`
- `[NEW]` `.../projection/PaymentProjectionHandler.java`
- `[NEW]` `.../projection/TicketProjectionHandler.java`
- `[NEW]` `.../projection/EventSessionProjectionHandler.java`
- `[NEW]` `.../projection/AnalyticsProjectionReconciler.java`
- `[NEW]` immutable operational/financial aggregate key abstractions as needed
- `[NEW]` `.../repository/AnalyticsProjectionQueryRepository.java` for deterministic aggregate recomputation SQL where JPA derived queries are insufficient
- `[NEW]` `V2__...sql` only if final contracts require additive schema changes (for example a reservation-scoped ticket revocation correlation fact)
- `[NEW/MODIFY]` P14-002 dispatcher registration
- `[NEW]` unit, repository, and Testcontainers projection tests

Use existing SeatFlow package/entity naming conventions. Do not create service clients to operational services.

---

## 6. Technical Specifications & Contracts

### 6.1 Projection Model: Facts First, Aggregates Second

Fact tables represent the latest analytics-safe state plus trusted lifecycle timestamps. Aggregates are recomputed from those facts for the smallest affected key set.

Avoid fragile logic like:

```text
on PaymentCompleted -> metrics.gross += amount
on PaymentRefunded -> metrics.gross -= amount
```

Prefer:

```text
on PaymentCompleted
  upsert payment fact
  resolve reservation/session if available
  recompute affected operational session/date keys
  recompute affected financial session/date/currency keys
```

If correlation is temporarily unavailable, persist the fact and stop without failing the valid event. When the missing related fact arrives, reconciliation must find and complete the affected projection.

### 6.2 Source-Event Precedence

Each fact stores `last_source_event_at`. Do not let an older delivery blindly replace newer state.

Timestamps alone are insufficient for every lifecycle. Implement explicit semantic precedence per aggregate and preserve independent timestamps where needed.

#### Reservation fact

Maintain independent lifecycle timestamps:

```text
created_at
confirmed_at
expired_at
refunded_at
```

Rules:

- creation/hold event initializes identity/session/seat count;
- confirmation sets `confirmed_at` once to the earliest trusted confirmation occurrence;
- expiration sets `expired_at` only if the final business lifecycle allows that reservation to expire; a later valid confirmation/refund semantic must not be erased by stale expiration replay;
- refund completion sets `refunded_at` only on completed refund outcome;
- if final P13 state machine makes statuses mutually exclusive, derive display/outcome classification from the canonical lifecycle priority rather than overwriting raw evidence timestamps.

For operational aggregates/rates, define one canonical outcome per reservation according to final state:

```text
REFUNDED > CONFIRMED > EXPIRED > CREATED/HELD
```

This precedence is analytics classification only; it does not mutate or redefine the source business state machine.

#### Payment fact

Preserve independent evidence:

```text
completed_at + completed_amount_minor + currency
failed_at (if this payment identity emitted canonical failure evidence)
refunded_at + refunded_amount_minor
```

Phase 14 definition:

- `payments_succeeded` = distinct payment identities with trusted completion evidence;
- `payments_with_failure` = distinct payment identities with trusted failure evidence;
- the same payment can appear in both historical evidence counts if the source lifecycle genuinely failed and later succeeded;
- Phase 14 does **not** attempt to count every provider-level retry attempt unless the final canonical event model explicitly creates distinct payment-attempt identities.

Refund-first replay handling:

- a valid refund event may create a provisional payment fact with refunded amount/time while completion evidence is not yet available;
- that provisional refund does **not** contribute to `daily_revenue_metrics`/`event_session_revenue_metrics` until completion amount + currency are known and `refunded <= completed` is validated;
- when the completion event later arrives, reconciliation validates and then contributes both gross and refund values to their correct dates;
- if later completion proves `refunded > completed`, the new event follows the configured failure/DLQ path and the unresolved payment remains excluded from financial aggregates. Never manufacture a negative net or clamp the refund.

Do not infer a payment refund from reservation status alone.

#### Ticket fact

- issue event sets identity/correlation/issued timestamp;
- revoke event sets revoked timestamp/state;
- first successful/accepted scan event sets `first_scanned_at` only if null;
- subsequent scan events do not move `first_scanned_at` and do not add attendance units;
- if scan arrives before issue, upsert a sparse ticket fact keyed by `ticket_id`; later issue fills correlation and triggers reconciliation.

If P13 emits reservation-scoped revocation instead of per-ticket revocation:

- persist the reservation-scoped revocation evidence in an additive analytics fact table keyed by reservation identity;
- when ticket issue events arrive/replay later, reconcile them against that evidence;
- do not count tickets revoked until actual ticket identities are known, and do not drop the earlier batch revocation event.

### 6.3 Correlation Reconciliation

#### Payment before reservation

When payment event arrives first:

1. store payment by `payment_id` + `reservation_id`;
2. leave `event_session_id` null if unknown;
3. do not fabricate/lookup it;
4. when reservation fact later arrives, find unresolved payment facts by `reservation_id`, fill analytics correlation where safe, and recompute relevant keys.

#### Ticket before issue/reservation

When scan/revoke evidence arrives before ticket issue:

1. create/update sparse analytics ticket/revocation facts using the strongest final canonical identity available;
2. retain scan/revoke timestamp;
3. later issue event fills `reservation_id`/`event_session_id`;
4. if issue has only reservation ID, resolve through analytics reservation fact when it becomes available;
5. recompute session attendance/ticket metrics after correlation becomes known.

No polling or REST retry loop is allowed.

### 6.4 Aggregate Grain Contract

This is a hard correctness boundary.

#### Operational aggregates

```text
daily_operational_metrics: (metric_date, event_id, event_session_id)
event_session_metrics:     (event_session_id)
```

These contain currency-neutral counts only.

#### Financial aggregates

```text
daily_revenue_metrics:          (metric_date, event_id, event_session_id, currency)
event_session_revenue_metrics:  (event_session_id, currency)
```

These contain money and financial counts by currency.

Never join financial rows to operational rows in a way that multiplies the operational row by number of currencies and then `SUM`s its counts.

P14-004 session API should query the one operational session row and separately collect its revenue rows as `revenueByCurrency[]`.

### 6.5 Daily Bucket Semantics

`metric_date` is the UTC calendar date of the fact timestamp associated with the metric.

#### `daily_operational_metrics`

| Metric | Date source |
|---|---|
| reservations_created | reservation `created_at` |
| reservations_confirmed | `confirmed_at` |
| reservations_expired | `expired_at` |
| payments_succeeded | payment `completed_at` |
| payments_with_failure | payment `failed_at` |
| refunds_completed | payment `refunded_at` only after completed refund evidence |
| tickets_issued | ticket `issued_at` |
| tickets_revoked | `revoked_at` |
| tickets_scanned | `first_scanned_at` |

#### `daily_revenue_metrics`

| Metric | Date source |
|---|---|
| payments_succeeded | payment `completed_at` |
| gross_revenue_minor | payment `completed_at` |
| refunds_completed | payment `refunded_at` |
| refunded_revenue_minor | refund completion date |

Important: one payment can contribute gross on one date and its refund on a later date. The reconciler therefore may touch more than one financial daily row for one payment fact.

Do not move a sale into the reservation creation date merely to make charts prettier.

### 6.6 Session Aggregate Semantics

#### `event_session_metrics`

For one session:

- `reservations_created`: distinct reservation facts;
- `reservations_confirmed`: distinct reservations whose canonical analytics outcome is confirmed or refunded;
- `reservations_expired`: distinct reservations whose canonical final analytics outcome is expired;
- `payments_succeeded`: distinct completed payments correlated to session;
- `payments_with_failure`: distinct payment identities with failure evidence correlated to session;
- `refunds_completed`: distinct completed full refunds/payments under P13 scope;
- `tickets_issued`: distinct tickets with `issued_at != null`;
- `tickets_revoked`: distinct tickets with `revoked_at != null`;
- `tickets_scanned`: distinct tickets with `first_scanned_at != null`;
- `capacity_snapshot`: trusted session capacity snapshot when available, else null.

These counts appear once, not once per currency.

#### `event_session_revenue_metrics`

For one `(event_session_id, currency)`:

- `payments_succeeded`: distinct completed payments in that currency;
- `refunds_completed`: distinct completed refunds in that currency;
- `gross_revenue_minor`: exact sum of completed payment minor amounts;
- `refunded_revenue_minor`: exact sum of completed refund minor amounts;
- net is derived as gross - refunded.

A refunded purchase remains historically a successful payment and its gross revenue remains gross; refund is represented separately.

### 6.7 Rate Definitions for Later API Use

Implement repository/query support so P14-004 can calculate these without ambiguous denominators.

#### Reservation-to-payment conversion rate

Use a **reservation-created cohort** for the requested date range:

```text
denominator = distinct reservations whose created_at is in [from,to]
numerator   = those same reservations with a correlated successful payment at any later known time
rate        = numerator / denominator
```

This avoids `>100%` caused by dividing payments completed today by reservations created today when purchases span dates.

Return `null`/`notAvailable` when denominator is zero; do not return NaN/Infinity.

#### Expiration rate

```text
denominator = reservations created in selected cohort
numerator   = those cohort reservations whose canonical final analytics outcome is EXPIRED
```

#### Refund rate

Use successful-payment cohort by completion date in selected range:

```text
denominator = successful payments completed in range
numerator   = those payments with completed refund known
```

P13 is full-reservation/full-payment refund scope. If later partial refunds are added, this definition must be revisited via contract/ADR rather than silently reusing it.

#### Attendance rate

For a session where meaningful denominator exists:

```text
denominator = distinct issued tickets not revoked before the relevant attendance boundary
numerator   = distinct tickets with first_scanned_at
```

If revocation/scan chronology makes denominator ambiguous or zero, return unavailable rather than a misleading percentage. Attendance count remains available.

#### Occupancy rate

```text
active/eligible issued ticket count / capacity_snapshot
```

only when `capacity_snapshot > 0` and its source contract is trusted. Otherwise occupancy is unavailable. Never use issued count itself as both numerator and denominator.

### 6.8 Recompute Strategy

For each accepted source event:

1. upsert/merge fact state idempotently;
2. collect all affected **old and new** session/date keys if correlation/state changed;
3. recompute affected `event_session_metrics` from currency-neutral facts;
4. recompute affected `daily_operational_metrics` rows from currency-neutral facts;
5. collect affected financial dates/currencies (completion and refund can be different dates);
6. recompute affected `event_session_revenue_metrics` rows from reconciled payment facts grouped by currency;
7. recompute affected `daily_revenue_metrics` rows from reconciled payment facts grouped by date/currency;
8. delete an aggregate row only when deterministic recomputation proves no contributing facts remain; otherwise upsert replacement values;
9. execute all of this inside the P14-002 event transaction.

If correlation changes from unknown -> known, recompute the newly known key. If a later valid correction moves a fact from session A -> B, recompute both A and B so stale counts/money do not remain.

Use database-side aggregate queries where practical. Do not load an unbounded event history into Java memory to sum it.

### 6.9 Session Metadata

P12 final event/session lifecycle events may provide:

```text
eventId
eventSessionId
startsAt
endsAt
status
safe event/session display label
optional venueId
optional trusted capacity snapshot
```

Update `analytics_session_facts` using source-event ordering/semantic version if available. If `capacity_snapshot` is absent, keep null and ensure later APIs/UI expose unavailable.

Do not fetch event titles/venues synchronously from Event Service at query time. Missing display snapshot falls back to stable IDs in UI.

### 6.10 Multi-Currency Safety

If a payment's currency changes across canonical completion/refund evidence, treat that as a contract violation and fail/park the conflicting event rather than moving money silently between currency buckets.

Operational counts may span all currencies because they are currency-neutral. Financial endpoints always return grouped values.

---

## 7. Step-by-Step Implementation Sequence

1. Re-read the final P14-002 event contract matrix.
2. Implement fact entities/repositories and any additive migration required by verified contracts.
3. Implement reservation fact reducer and tests for held/confirmed/expired/refunded orderings.
4. Implement payment fact reducer including provisional refund-first state and unresolved reservation correlation.
5. Implement ticket fact reducer including scan-before-issue and the final P13 revocation event shape.
6. Implement session metadata reducer with nullable capacity.
7. Implement deterministic reconciler for **currency-neutral operational** session/daily keys.
8. Implement deterministic reconciler for **currency-keyed financial** session/daily keys.
9. Add cohort/rate repository queries without exposing REST yet.
10. Register handlers with P14-002 dispatcher.
11. Add replay, reorder, correction, multi-currency, refund, and attendance tests.
12. Run the same canonical event set in multiple delivery orders and assert identical final facts/aggregates where domain semantics permit.

---

## 8. Test Requirements

### 8.1 Replay Determinism

- [ ] Apply canonical event history once -> snapshot facts/aggregates.
- [ ] Re-deliver every same eventId -> snapshot unchanged.
- [ ] Rebuild empty analytics DB from same retained events -> same final snapshot.

### 8.2 Cross-Topic Ordering

- [ ] Reservation then payment and payment then reservation converge to same completed payment/session revenue.
- [ ] Ticket issue then scan and scan then issue converge to one issued + one scanned ticket.
- [ ] Refund/payment and reservation refund/ticket revoke cross-topic variations converge to correct facts.
- [ ] provisional refund received before completion contributes no financial aggregate until completion arrives and validates it.

### 8.3 Reservation Lifecycle

- [ ] Held -> confirmed counts one created + one confirmed.
- [ ] Held -> expired counts one created + one expired.
- [ ] stale expiration delivered after valid confirmation does not reclassify a confirmed reservation incorrectly.
- [ ] refunded reservation remains historically confirmed/successful but records refund separately.

### 8.4 Aggregate Grain / Multi-Currency

- [ ] same session with RON + EUR has **one** `event_session_metrics` row.
- [ ] same session/date has one operational row regardless of currency count.
- [ ] session has two financial revenue rows, RON and EUR.
- [ ] querying/summing operational counts does not multiply them by currency rows.

### 8.5 Financial Correctness

- [ ] successful payment adds exact minor units to gross in its currency.
- [ ] payment with failure evidence adds zero gross solely because of failure.
- [ ] completed refund adds exact minor units to refunded revenue and leaves gross unchanged.
- [ ] net calculation is exact integer subtraction.
- [ ] duplicate/replay cannot change amounts.
- [ ] invalid refund > known completed amount is rejected/parked, not clamped silently.
- [ ] refund-first unresolved fact never exposes negative net; after matching completion arrives it reconciles correctly.

### 8.6 Ticket / Attendance

- [ ] duplicate scan eventId -> one attendance.
- [ ] multiple accepted-attempt semantics for same ticket still result in one `first_scanned_at` attendance unit.
- [ ] revoked ticket is represented separately from issued history.
- [ ] scan-before-issue is retained and correlated later.
- [ ] reservation-scoped revocation, if that is the final P13 contract, is retained until ticket identities are known.

### 8.7 Rates

- [ ] cohort conversion denominator uses reservation-created range, not payment-completion range.
- [ ] denominator zero -> unavailable/null, never division by zero.
- [ ] no rate exceeds logical bounds for valid source history.
- [ ] occupancy unavailable when capacity null/zero.

### 8.8 Correction / Reconciliation

- [ ] late correlation from unknown reservation/session causes aggregate creation exactly once.
- [ ] valid correction moving correlation A -> B recomputes both buckets with no stale A contribution.
- [ ] older session metadata delivery cannot overwrite newer starts/status snapshot.
- [ ] completion/refund on different UTC dates recomputes both financial daily rows.

Use PostgreSQL/Testcontainers for repository/constraint behavior.

---

## 9. Verification Commands

```bash
cd backend
./mvnw -pl services/analytics-service -am test
```

Run any dedicated Kafka/Testcontainers integration-test profile introduced by P14-002/P14-003. Verification evidence must include at least one duplicate replay test, one cross-topic out-of-order convergence test, and one multi-currency aggregate-grain test.

---

## 10. Independent Review Focus

Critical review must recalculate representative examples manually and inspect:

- fact-to-aggregate derivation;
- operational vs financial aggregate grains;
- duplicate/replay behavior;
- event-state precedence under out-of-order delivery;
- provisional refund-first behavior;
- gross vs refund vs net semantics;
- cohort denominators;
- ticket attendance uniqueness;
- multi-currency separation;
- nullable capacity behavior;
- SQL query bounds/index usage;
- absence of source DB/API coupling.

Reviewer should specifically try to construct a sequence that duplicates an operational count through a currency join, makes a counter negative, duplicates revenue, makes a rate exceed 100%, or leaves stale contribution in an old session bucket.

---

## 11. Acceptance Criteria

- [ ] Reservation/payment/refund/ticket/session events populate analytics-owned facts without PII.
- [ ] Operational counts live in currency-neutral aggregates; financial money lives in currency-keyed aggregates.
- [ ] Daily and session aggregates are deterministic and replay-safe.
- [ ] Gross/refunded/net revenue semantics are correct in minor units and never mixed across currencies.
- [ ] Cross-topic out-of-order events reconcile without source-service lookups.
- [ ] Provisional refund-first facts cannot create negative/misleading revenue.
- [ ] Duplicate/repeated ticket scans cannot inflate attendance.
- [ ] Session occupancy remains explicitly unavailable when trusted capacity is absent.
- [ ] Cohort definitions for conversion/expiration/refund are implemented and tested.
- [ ] PostgreSQL and Kafka-oriented projection tests pass.
- [ ] Critical independent review passes with no unresolved financial/idempotency/aggregate-grain finding.

---

## 12. Execution Entry Point

```text
Implement TASK-P14-003 using the SeatFlow autonomous orchestration workflow.
Optimize for deterministic replay and correctness, not incremental-counter cleverness. Keep operational counts currency-neutral, financial aggregates currency-keyed, and use only analytics-owned event-derived facts.
```
