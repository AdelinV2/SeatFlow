# TASK-P12-001: Create Event Session Schema and Safe Legacy Backfill

## 1. Task Metadata

- **Task ID:** `TASK-P12-001`
- **Git Branch:** `feat/p12-001-event-session-schema-backfill`
- **Target Module:** `backend/services/event-service`
- **Phase:** `Phase 12 - Multiple Event Sessions / Showings`
- **Related Specs:** `.ai/tasks/phase-12-event-sessions/000-phase-overview.md`, `.ai/architecture/09-post-mvp-evolution.md`
- **Related ADRs:** `.ai/decisions/ADR-011-event-sessions-booking-boundary.md`
- **Status:** `READY FOR IMPLEMENTATION`

### Orchestration Metadata

- **Complexity:** `5`
- **Failure Risk:** `Critical`
- **Verification Strength:** `Strong`
- **Required Review Depth:** `Critical`
- **Preferred Workflow:** `critical`
- **Affected Critical Invariants:** `PostgreSQL source of truth; zero double booking migration prerequisite; data integrity; backward-compatible migration`

---

## 2. Objective

Introduce the canonical `event_sessions` persistence model in event-service and backfill exactly one deterministic session for every legacy event without deleting or mutating the legacy schedule columns yet. After this task, every existing event must have a session representing its current `startsAt`/`endsAt`, and new code must have a stable session identifier to use in later Phase 12 tasks.

This task is schema/backfill only. It MUST NOT migrate reservation inventory, remove legacy event schedule fields, or silently change public booking APIs.

---

## 3. Critical Invariants & Failure Modes

### Invariants to Enforce

- [ ] Every pre-Phase-12 event has exactly one backfilled session after migration.
- [ ] Backfilled `starts_at` and `ends_at` represent the same instants as the legacy event values; no timezone reinterpretation is allowed.
- [ ] `event_sessions.event_id` always references an existing event.
- [ ] `starts_at < ends_at` for every session.
- [ ] If both sale boundaries exist, `sale_starts_at < sale_ends_at`; when `sale_ends_at` exists it must not be after `starts_at`.
- [ ] Existing event rows remain readable by the pre-Phase-12 code path until Task P12-007 removes compatibility.
- [ ] The migration is deterministic and idempotent at the data-model level: rerunning deployment cannot create a second legacy session for an event.

### Primary Failure Modes

- [ ] Duplicate legacy sessions caused by non-deterministic backfill — prevented by a DB uniqueness marker/constraint for the one-time legacy mapping.
- [ ] Lost or shifted timestamps — prevented by `TIMESTAMPTZ`/repository-equivalent instant-preserving storage and parity assertions.
- [ ] Partial migration leaves events without sessions — migration verification must fail the deployment/QA gate when parity is not 1:1.
- [ ] New schema blocks existing traffic for an unsafe period — use additive migration first; do not drop/rename legacy columns in this task.
- [ ] Cascade behavior deletes session data unexpectedly — FK delete semantics must match event lifecycle and be integration-tested.

---

## 4. Dependencies / Prerequisites

- Accepted `ADR-011-event-sessions-booking-boundary.md`.
- Current event schema in `V1__create_events_and_pricing_tables.sql`.
- Current `Event` entity and event-service integration tests.
- No dependent service may treat `eventSessionId` as authoritative until P12-002 exposes validated session contracts.

---

## 5. Exact File Inventory

Expected files:

- `[NEW]` `backend/services/event-service/src/main/resources/db/migration/V3__create_event_sessions_and_backfill.sql`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/model/entity/EventSession.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/model/enums/EventSessionStatus.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/repository/EventSessionRepository.java`
- `[NEW]` `backend/services/event-service/src/test/java/com/seatflow/event/repository/EventSessionRepositoryTest.java`
- `[MODIFY]` `backend/services/event-service/src/test/java/com/seatflow/event/integration/EventServiceIntegrationTest.java`

Do not modify `CreateEventRequest`, `UpdateEventRequest`, public controllers, or remove fields from `Event` in this task.

---

## 6. Technical Specifications & Contracts

### 6.1 Database / Flyway

Create `event_sessions` with the following logical contract (adapt naming/types only if repository conventions require an equivalent representation):

```sql
CREATE TABLE event_sessions (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    sale_starts_at TIMESTAMPTZ NULL,
    sale_ends_at TIMESTAMPTZ NULL,
    status VARCHAR(32) NOT NULL,
    timezone VARCHAR(64) NULL,
    legacy_backfill BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_event_sessions_event FOREIGN KEY (event_id) REFERENCES events(id),
    CONSTRAINT ck_event_session_time_order CHECK (starts_at < ends_at),
    CONSTRAINT ck_event_session_sale_order CHECK (
      sale_starts_at IS NULL OR sale_ends_at IS NULL OR sale_starts_at < sale_ends_at
    ),
    CONSTRAINT ck_event_session_sale_end CHECK (
      sale_ends_at IS NULL OR sale_ends_at <= starts_at
    )
);
CREATE INDEX idx_event_sessions_event_start ON event_sessions(event_id, starts_at);
CREATE UNIQUE INDEX uq_event_sessions_legacy_event
  ON event_sessions(event_id) WHERE legacy_backfill = TRUE;
```

Migration rules:

1. Add the table/indexes/constraints without changing the `events` table.
2. Insert one session for every existing event from the legacy schedule columns.
3. Use the existing event status only through an explicit mapping documented in migration comments/tests; do not invent a booking state from ambiguous values. The initial backfill status must be a non-destructive scheduled/completed representation that preserves whether the session is in the past. Cancellation/refund orchestration is out of scope.
4. `timezone` may remain `NULL` for migrated rows because the original IANA zone cannot be reconstructed safely from an instant/offset. Never fabricate a city timezone.
5. Generate IDs in PostgreSQL using the repository-approved UUID mechanism. If the DB currently lacks an extension required by a generator, generate deterministic UUIDs in an application migration only after review; do not add an extension casually.
6. After backfill, verification queries must prove `COUNT(events) == COUNT(distinct event_sessions.event_id WHERE legacy_backfill)` and zero timestamp mismatches.

### 6.2 Entity Contract

`EventSession` must own session schedule fields. Do not add booking/inventory state to `Event`.

Required fields: `id`, `eventId`/event relationship, `startsAt`, `endsAt`, nullable `saleStartsAt`, nullable `saleEndsAt`, `status`, nullable `timezone`, audit timestamps. `legacyBackfill` is migration metadata and must not be exposed in customer DTOs.

### 6.3 Repository Contract

Repository operations required for later tasks:

- list by event ordered by `startsAt`, then `id` for deterministic ties;
- find by `id`;
- find by `id` + `eventId` when validating an event/session pair;
- count/list legacy sessions for migration verification.

No repository method may infer an arbitrary session when an event has more than one session.

---

## 7. Step-by-Step Implementation Sequence

1. Read existing V1 event DDL and timestamp/audit conventions.
2. Add V3 migration additively.
3. Add `EventSessionStatus` and `EventSession` entity matching DB constraints.
4. Add repository queries with deterministic ordering.
5. Add repository/Testcontainers coverage for constraints and query semantics.
6. Extend integration startup test to execute V1 -> V2 -> V3 from an empty DB.
7. Add a migration parity test starting from representative legacy rows, including past/future events and boundary timestamps.
8. Run event-service test suite and inspect generated schema parity.

---

## 8. Test Requirements

### Repository / Migration

- [ ] V3 migrates a DB containing multiple legacy events and creates exactly one session per event.
- [ ] Backfilled timestamps equal the original instants exactly.
- [ ] Duplicate `legacy_backfill=TRUE` for one event is rejected by PostgreSQL.
- [ ] `ends_at <= starts_at` is rejected by PostgreSQL.
- [ ] invalid sale-window ordering is rejected by PostgreSQL.
- [ ] deleting/inserting invalid parent relationships cannot create orphan sessions.
- [ ] ordering by `startsAt,id` is deterministic.

### Compatibility

- [ ] Existing event reads still pass unchanged immediately after V3.
- [ ] Existing event create/update behavior remains unchanged in P12-001.

---

## 9. Verification Commands

```bash
cd backend
./mvnw -pl services/event-service -am test
```

If the repository uses system Maven rather than the wrapper, use the established equivalent command from `backend/AGENTS.md`.

Expected observable result: Flyway migration succeeds from clean and pre-V3 schemas; parity tests prove one legacy session per event; all event-service tests pass.

---

## 10. Independent Review Focus

- Timestamp type and instant preservation.
- Partial/duplicate backfill behavior.
- Constraint correctness and index selectivity.
- Whether any legacy column was prematurely removed or made unreadable.
- Whether the chosen status mapping invents semantics not supported by existing state.

---

## 11. Acceptance Criteria

- [ ] `event_sessions` exists with DB-enforced temporal and parent integrity.
- [ ] Every legacy event is backfilled exactly once with matching schedule instants.
- [ ] Existing event behavior remains operational.
- [ ] No booking API/runtime fallback is introduced here.
- [ ] Migration and repository Testcontainers tests pass.
- [ ] Critical independent review and final QA pass.
- [ ] No active `.ai/tmp/review-*.md` remains.

---

## 12. Execution Entry Point

```text
Implement TASK-P12-001 using the SeatFlow autonomous orchestration workflow.
```
