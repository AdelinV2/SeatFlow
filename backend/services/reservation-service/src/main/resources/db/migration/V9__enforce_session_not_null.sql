-- V9__enforce_session_not_null.sql
--
-- P12-009 (TASK-P12-009 / ADR-011): promote the P12-007 V8 fail-closed data
-- gate to database-enforced NOT NULL on reservations.event_session_id and
-- seat_holds.event_session_id.
--
-- Why: PostgreSQL treats NULLs as distinct in unique indexes, so a NULL-session
-- hold never collides under uq_active_seat_hold_session and session-scoped
-- lock/availability queries (which filter by event_session_id) never match it:
-- a NULL-session write is a double-booking hole. V8 aborted migration while
-- NULLs remained and SessionIntegrityStartupCheck alerts at boot, but only a
-- hard constraint closes future write paths and manual/data-fix inserts.
--
-- Gate + constraint are atomic: Flyway executes each migration in one
-- transaction and PostgreSQL supports transactional DDL, so the re-check below
-- and both ALTERs commit together against committed state. No silent strand:
-- the migration raises before any schema change when orphan rows remain.
--   Orphan reservations: SELECT id FROM reservations WHERE event_session_id IS NULL;
--   Orphan holds:        SELECT id FROM seat_holds WHERE event_session_id IS NULL;
--
-- Deploy ordering: run SessionInventoryBackfillService and verify zero NULLs
-- BEFORE this migration (same freeze as V8 REV-006: freeze legacy
-- event-scoped writers for the deploy window; writers blocked on the
-- migration lock that commit after it would otherwise land post-gate).
-- Post-deploy data-quality queries live in
-- backend/scripts/p12-007-postdeploy-data-quality.sql (must return zero rows).
--
-- Scope: nullability only. event_id columns stay as non-authoritative
-- parent-event audit/display references (P12-007 retention); no index changes
-- here (V6 already owns the session-scoped booking indexes).

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM reservations WHERE event_session_id IS NULL) THEN
        RAISE EXCEPTION 'P12-009 gate: reservations without event_session_id exist; run SessionInventoryBackfillService before V9';
    END IF;
    IF EXISTS (SELECT 1 FROM seat_holds WHERE event_session_id IS NULL) THEN
        RAISE EXCEPTION 'P12-009 gate: seat_holds without event_session_id exist; run SessionInventoryBackfillService before V9';
    END IF;
END
$$;

ALTER TABLE reservations ALTER COLUMN event_session_id SET NOT NULL;

ALTER TABLE seat_holds ALTER COLUMN event_session_id SET NOT NULL;
