# TASK-P12-003: Migrate Reservation Inventory from Event to Event Session

## 1. Task Metadata

- **Task ID:** `TASK-P12-003`
- **Git Branch:** `feat/p12-003-session-aware-reservation-inventory`
- **Target Module:** `backend/services/reservation-service`
- **Phase:** `Phase 12 - Multiple Event Sessions / Showings`
- **Related Specs:** `000-phase-overview.md`, `.ai/architecture/09-post-mvp-evolution.md`
- **Related ADRs:** `ADR-011-event-sessions-booking-boundary.md`
- **Status:** `READY FOR IMPLEMENTATION`

### Orchestration Metadata

- **Complexity:** `5`
- **Failure Risk:** `Critical`
- **Verification Strength:** `Strong`
- **Required Review Depth:** `Critical`
- **Preferred Workflow:** `critical`
- **Affected Critical Invariants:** `Zero double booking; max 10 seats; 15-minute hold; PostgreSQL source of truth; transactional outbox; idempotency`

---

## 2. Objective

Make `eventSessionId` the authoritative inventory/booking key throughout reservation-service. The same physical seat may be independently held/reserved in two sessions of the same event, while concurrent attempts for the same seat in the same session retain the existing zero-double-booking guarantee.

---

## 3. Critical Invariants & Failure Modes

- [ ] Inventory identity is `(event_session_id, seat_id)`, never `(event_id, seat_id)` after cutover.
- [ ] Maximum 10 seats per reservation remains server-enforced.
- [ ] Hold duration remains exactly the existing authoritative 15-minute contract.
- [ ] PostgreSQL constraints + locking remain the authority; Redis/WebSocket/client state cannot decide availability.
- [ ] Reservation creation is idempotent according to the existing idempotency contract.
- [ ] `eventSessionId` is resolved against event-service; reservation-service derives the parent event and hall/pricing context from that trusted response.
- [ ] A client-supplied `eventId` is not accepted as a booking key. If a temporary compatibility DTO must contain it, mismatch with resolved session is a hard failure and it is removed in P12-007.
- [ ] Outbox events persist in the same DB transaction as reservation state.

Failure modes: cross-session false blocking, cross-session seat leakage, same-session double booking, stale legacy `event_id` query, mixed-session cart, session from wrong event/hall, hold expiry accidentally releasing another session's seat, migration rows with null session IDs.

---

## 4. Dependencies / Prerequisites

- P12-001 and P12-002 completed.
- Event-service booking-context endpoint available through Eureka + LoadBalancer.
- Current reservation schema V1-V5 and existing concurrency tests understood.

---

## 5. Exact File Inventory

At minimum:

- `[NEW]` `backend/services/reservation-service/src/main/resources/db/migration/V6__add_event_session_inventory_key.sql`
- `[MODIFY]` `.../model/entity/Reservation.java`
- `[MODIFY]` `.../model/entity/SeatHold.java`
- `[MODIFY]` `.../repository/ReservationRepository.java`
- `[MODIFY]` `.../repository/SeatHoldRepository.java`
- `[MODIFY]` `.../repository/projection/ActiveSeatHoldProjection.java`
- `[MODIFY]` `.../repository/projection/SeatHoldProjection.java`
- `[MODIFY]` `.../client/EventClient.java`
- `[MODIFY]` `.../client/impl/EventClientImpl.java`
- `[NEW/MODIFY]` booking-context client DTOs under `.../client/dto/`
- `[MODIFY]` `.../service/ReservationService.java`
- `[MODIFY]` `.../service/impl/ReservationServiceImpl.java`
- `[MODIFY]` `.../web/controller/ReservationController.java`
- `[MODIFY]` `.../web/dto/request/CreateReservationRequest.java`
- `[MODIFY]` `.../web/dto/response/EventSeatStatusResponse.java`
- `[MODIFY]` `.../web/dto/response/ReservationResponse.java`
- `[MODIFY]` `.../web/dto/response/SeatAvailabilityResponse.java`
- `[MODIFY]` `.../web/dto/response/SeatHoldResponse.java`
- `[MODIFY]` reservation outbox event payloads (`ReservationHeldEvent`, `ReservationConfirmedEvent`, `ReservationCancelledEvent`, `ReservationExpiredEvent`)
- `[MODIFY]` existing repository/service/controller/messaging/integration/concurrency tests.

---

## 6. Technical Specifications & Contracts

### 6.1 Database Migration

V6 must be additive first:

1. Add nullable `event_session_id UUID` to `reservations` and `seat_holds` (and any separate reservation-seat table if current schema inspection reveals one).
2. Add new indexes needed by active-hold/availability queries keyed by session.
3. Backfill existing rows from the deterministic legacy event -> session mapping created by P12-001. Because event-service owns a separate database boundary, Flyway MUST NOT perform an unsafe cross-service/network call. Use a reviewed, idempotent migration utility/deployment step that resolves each distinct legacy `event_id` through event-service, writes `event_session_id`, records failures, and can be rerun safely.
4. Verification gate: zero existing rows may remain with `event_session_id IS NULL` before NOT NULL constraints are added.
5. Add NOT NULL constraints only after verified backfill.
6. Keep legacy `event_id` columns temporarily for compatibility/audit; they are removed or made non-authoritative in P12-007.
7. Replace every active uniqueness/partial index or locking query that currently partitions by event with the session key. Do not merely add a new column while leaving old uniqueness semantics active.

The migration utility must fail closed if an event resolves to zero or more than one `legacy_backfill` session. It must never guess the first session.

### 6.2 Booking API

Canonical request must contain:

```text
eventSessionId: UUID, required
seats/pricing selections: existing contract
```

Remove `eventId` as the authoritative request field. Reservation response and availability/hold responses include `eventSessionId`. Keeping `eventId` as derived display metadata is allowed only if it is populated from trusted session context, never client input.

Availability route must be session-explicit. Prefer:

```text
GET /api/event-sessions/{eventSessionId}/seats/availability
```

or the repository's equivalent clearly named route. Do not keep an ambiguous `/events/{id}/...` route where `{id}` actually means a session.

### 6.3 Service / Locking Contract

For create/hold:

1. validate request count <=10 and non-empty exactly as current behavior;
2. resolve `eventSessionId` through event-service booking context;
3. reject non-bookable session/event/sale-window state;
4. validate requested seats belong to the parent event's hall/layout and pricing contract;
5. acquire/query locks scoped by `eventSessionId` + seat IDs using deterministic ordering to avoid deadlocks;
6. evaluate active holds/reservations for that session only;
7. persist reservation/holds with the session ID;
8. persist outbox event in same transaction.

Hold expiration/cancel/confirm operations must use the session stored on the reservation/hold. They MUST NOT accept a second session ID from the client and use it to release/confirm inventory.

### 6.4 Concurrency Oracle

- Two concurrent holds for seat A1 in **the same session**: at most one succeeds.
- Two concurrent holds for seat A1 in **different sessions of the same event**: both may succeed independently.
- Expiring/cancelling session A's A1 cannot change session B's A1 state.

These properties require real PostgreSQL/Testcontainers tests; mocks are insufficient.

### 6.5 Messaging

All reservation lifecycle events must add `eventSessionId`. During the compatibility window they may retain derived `eventId` for consumers, but consumers must be migrated in P12-004/P12-005 before P12-007 removes legacy assumptions.

---

## 7. Step-by-Step Implementation Sequence

1. Inventory all SQL indexes/queries containing `event_id` and classify booking vs display/audit usage.
2. Add additive V6 schema and migration utility.
3. Extend EventClient booking-context lookup.
4. Update entities/repositories/projections and all locking/availability queries.
5. Update request/response/controller/service semantics.
6. Update lifecycle outbox payloads.
7. Update expiration/cancel/confirm flows.
8. Add same-session and cross-session concurrency tests.
9. Run full reservation module + dependent contract tests.

---

## 8. Test Requirements

- [ ] A1 held in session A is still AVAILABLE in session B.
- [ ] A1 can be reserved independently in A and B.
- [ ] 20+ concurrent same-session A1 attempts yield exactly one authoritative winner and no duplicate committed booking.
- [ ] concurrent A1 attempts split between two sessions can yield one winner per session.
- [ ] hold expiry/cancel for A does not release B.
- [ ] >10 seats rejected unchanged.
- [ ] hold expiry remains 15 minutes under the existing clock contract.
- [ ] invalid/non-bookable session rejected before inventory mutation.
- [ ] spoofed event/session relationship cannot influence hall/pricing validation.
- [ ] migration leaves zero null session IDs and fails closed on ambiguous legacy mapping.
- [ ] outbox row and reservation change are atomic.

---

## 9. Verification Commands

```bash
cd backend
./mvnw -pl services/reservation-service -am test
```

Run the module's Testcontainers concurrency/integration suite explicitly if excluded from the default profile.

---

## 10. Independent Review Focus

Every `eventId` occurrence in reservation-service must be classified. Reviewer must prove no availability, uniqueness, locking, expiry, confirm, cancel, or outbox routing path still uses event identity as inventory partition. Review SQL under real PostgreSQL semantics.

---

## 11. Acceptance Criteria

- [ ] Session ID is the sole booking/inventory partition.
- [ ] Same-seat cross-session isolation and same-session exclusivity are proven by Testcontainers.
- [ ] All existing max-seat/hold/idempotency/outbox invariants remain green.
- [ ] Legacy data is backfilled without guessing.
- [ ] Critical independent review and final QA pass.

---

## 12. Execution Entry Point

```text
Implement TASK-P12-003 using the SeatFlow autonomous orchestration workflow.
```
