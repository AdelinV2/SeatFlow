# TASK-P12-007: Remove Legacy Event-Schedule Booking Semantics and Compatibility Paths

## 1. Task Metadata

- **Task ID:** `TASK-P12-007`
- **Git Branch:** `feat/p12-007-remove-legacy-event-schedule`
- **Target Module:** `event-service`, `reservation-service`, `payment-service`, `ticket-service`, `notification-service`, `realtime-service`, `frontend`
- **Phase:** `Phase 12 - Multiple Event Sessions / Showings`
- **Related ADRs:** `ADR-011-event-sessions-booking-boundary.md`
- **Status:** `READY FOR IMPLEMENTATION`

### Orchestration Metadata

- **Complexity:** `5`
- **Failure Risk:** `Critical`
- **Verification Strength:** `Strong`
- **Required Review Depth:** `Critical`
- **Preferred Workflow:** `critical`
- **Affected Critical Invariants:** `Single booking identity; migration integrity; no silent compatibility fallback`

---

## 2. Objective

After all downstream services and frontends are session-aware, remove legacy event-level schedule writes/reads and event-scoped booking/realtime compatibility. The completed system must have exactly one semantic source for showing identity: `EventSession`.

---

## 3. Critical Invariants & Failure Modes

- [ ] `Event.startsAt/endsAt` are no longer read or written as booking schedule after cleanup.
- [ ] Event create/update APIs cannot accept legacy schedule fields.
- [ ] Reservation/availability APIs cannot accept `eventId` as a booking key or infer a session from it.
- [ ] Old event-scoped WebSocket topics are removed.
- [ ] Consumers cannot infer `eventSessionId` from `eventId`.
- [ ] Legacy DB columns are dropped only after code and data verification prove zero readers/writers and every dependent row has a session ID.
- [ ] No compatibility adapter chooses “only”, “first”, “nearest”, or “next” session.

Failure modes: old frontend still writing schedule, hidden repository query on `event_id`, old Kafka consumer accepts event-only message, DB drop before deployment order safe, legacy API route silently maps to a session.

---

## 4. Dependencies / Prerequisites

- P12-001 through P12-006 completed and integrated in dependency order.
- Migration verification reports zero null/missing session IDs across dependent data.
- Contract grep/inventory produced before destructive migration.

---

## 5. Exact File Inventory

Expected:

- `[NEW]` event-service Flyway migration `V4__remove_legacy_event_schedule.sql` (exact next version must be rechecked at execution time; do not reuse an occupied Flyway version).
- `[MODIFY]` event `Event.java`, `CreateEventRequest.java`, `UpdateEventRequest.java`, `EventMapper.java`, response DTOs/repository queries/tests to remove schedule ownership.
- `[NEW]` reservation-service cleanup migration after V6 to remove/deprecate legacy booking `event_id` columns/indexes where they are no longer needed for audit/display; preserve derived `eventId` only if an explicit non-booking business need remains.
- `[MODIFY/DELETE]` legacy event-scoped reservation routes, realtime topic builders, payload fallback branches, frontend compatibility fields.
- Contract/static-analysis tests that prevent reintroduction.

Before deleting any file/column, prove it has no remaining consumer by repository search plus tests.

---

## 6. Technical Specifications & Contracts

### 6.1 Destructive Migration Gate

Do not run DROP migrations until all are true:

1. Event-service session count/parity is valid.
2. Reservation/hold/ticket/payment records that require session identity have non-null valid `eventSessionId`.
3. Current public frontend does not send legacy schedule/booking fields.
4. All current Kafka consumers accept session-aware payloads.
5. Realtime client uses `/topic/sessions/{id}/seats`.
6. CI/static grep finds no booking-semantic uses of `eventId` in prohibited areas.

### 6.2 API Behavior

Legacy request fields must not be silently ignored. Depending on Jackson/API conventions, either fail unknown legacy fields with established validation or remove old route entirely. Old event-scoped booking route should be `404` because it no longer exists, or an explicit documented `410 Gone` if the project has a deprecation mechanism. It must never redirect/map to an inferred session.

### 6.3 Event Model

After cleanup, event owns catalog metadata and parent venue/hall/pricing configuration only. Session owns `startsAt`, `endsAt`, sale windows, session status/timezone.

Event search ordering/filtering formerly based on `Event.startsAt` must be rewritten with explicit session-aware semantics (e.g. next visible future session) without duplicating event result rows. This derived “next session” value is display/search metadata, not a booking key.

### 6.4 Repository/Static Gate

Add a documented verification grep/script that flags suspicious booking usages such as:

```text
reservation.*eventId
seatHold.*eventId
/topic/events/.*/seats
CreateEventRequest.*startsAt
UpdateEventRequest.*startsAt
```

Allowlist legitimate catalog/audit `eventId` use rather than blindly banning the identifier globally.

---

## 7. Step-by-Step Implementation Sequence

1. Produce legacy-use inventory categorized: catalog, audit, booking, compatibility.
2. Remove frontend legacy writes/routes first or in same compatible release.
3. Remove backend compatibility branches and old realtime destination.
4. Update event model/DTOs/mappers/search derived schedule behavior.
5. Run data integrity gates.
6. Apply destructive Flyway migrations last.
7. Add static regression gate + full contract tests.

---

## 8. Test Requirements

- [ ] Event create/update with legacy schedule fields is rejected/not supported.
- [ ] Old event-scoped availability/reservation route cannot book.
- [ ] Old event-scoped STOMP destination receives no seat updates.
- [ ] Search still returns one event and correctly derives next visible session.
- [ ] Existing migrated event remains bookable via its backfilled session.
- [ ] DB migration refuses/QA blocks cleanup when orphan/null session references exist.
- [ ] repository search/static check shows zero unauthorized event-level booking semantics.
- [ ] all service/front-end contract tests pass together.

---

## 9. Verification Commands

```bash
cd backend
./mvnw test
cd ../frontend
npm run lint
npm test -- --watch=false
npm run build
```

Also run the Phase 12 legacy-contract grep/check created by this task.

---

## 10. Independent Review Focus

Destructive migration ordering, hidden legacy readers/writers, search semantics, accidental fallback/inference, and whether any audit/catalog `eventId` was incorrectly removed.

---

## 11. Acceptance Criteria

- [ ] No authoritative booking path accepts/uses event-level schedule identity.
- [ ] Legacy schedule columns/contracts are removed only after integrity proof.
- [ ] No implicit session inference remains.
- [ ] Search/event detail still behaves correctly.
- [ ] Full backend/frontend suites pass.
- [ ] Critical review and final QA pass.

---

## 12. Execution Entry Point

```text
Implement TASK-P12-007 using the SeatFlow autonomous orchestration workflow.
```
