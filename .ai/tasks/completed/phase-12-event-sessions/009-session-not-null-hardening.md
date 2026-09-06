# TASK-P12-009: Harden Session Inventory Key with NOT NULL Constraints

## 1. Task Metadata

- **Task ID:** `TASK-P12-009`
- **Git Branch:** `feat/p12-009-session-not-null-hardening` (to be created from `develop` at execution time)
- **Target Module:** `reservation-service`
- **Phase:** `Phase 12 - Multiple Event Sessions / Showings`
- **Related ADRs:** `ADR-011-event-sessions-booking-boundary.md`
- **Related Task:** `TASK-P12-007` (REV-004 deferred this hardening; V8 is a migrate-time gate only)
- **Status:** `PROPOSED`

### Orchestration Metadata

- **Complexity:** `3`
- **Failure Risk:** `High`
- **Verification Strength:** `Strong`
- **Required Review Depth:** `Standard`
- **Preferred Workflow:** `standard`
- **Affected Critical Invariants:** `Zero double booking; migration integrity`

---

## 2. Objective

Promote the P12-007 V8 fail-closed data gate to database-enforced `NOT NULL` on
`reservations.event_session_id` and `seat_holds.event_session_id` (e.g. a V9
migration with a gate re-check), so no future write path or manual/data-fix
insert can create a NULL-session hold that session-scoped lock/availability
queries would never match (double-booking hole).

Runtime posture until then: V8 aborts migration while NULLs remain, and
`SessionIntegrityStartupCheck` alerts on NULL session rows at boot without
blocking startup.

---

## 3. Preconditions (all must hold before the constraint lands)

1. `SessionIntegrityStartupCheck` reports zero NULLs in every environment the
   migration will run against (or the backfill is re-run first).
2. `SessionScopedInventoryIntegrationTest` orders 11-13 (which intentionally
   persist legacy-NULL rows via JPA to verify `SessionInventoryBackfillService`)
   are moved to a staged pre-constraint schema, following the staged-schema
   pattern already demonstrated by `V8SessionGateMigrationTest`. The live-schema
   concurrency oracle must keep its single-database fidelity.
3. No other suite persists NULL `event_session_id` rows on the migrated schema.

---

## 4. Implementation Sequence

1. Add the `NOT NULL` migration with a V8-style gate re-check (fail closed on
   remaining NULLs; transactional DDL keeps gate + constraint atomic).
2. Move legacy-NULL-writing suites to the staged pre-constraint schema.
3. Update `Reservation` / `SeatHold` entity columns to `nullable = false` and
   trim the deferral comments to point at the landed migration.
4. Add a migration test asserting a NULL insert is rejected post-migration.

---

## 5. Acceptance Criteria

- [ ] NULL `event_session_id` insert is rejected at the DB level on both tables.
- [ ] Migration still refuses when orphan rows exist (no silent strand).
- [ ] Backfill path keeps dedicated staged-schema verification coverage.
- [ ] Full reservation-service suite passes.
