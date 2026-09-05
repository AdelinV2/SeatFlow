-- V8__enforce_session_inventory_key.sql
--
-- P12-007 (TASK-P12-007 / ADR-011): fail-closed session-integrity gate.
--
-- Scope decision (documented retention, not a silent leftover):
--   * reservations.event_id / seat_holds.event_id are RETAINED as
--     non-authoritative parent-event audit/display references, always derived
--     from the trusted session booking context. They are never booking keys;
--     inventory is partitioned by event_session_id only.
--   * No event-scoped booking index is recreated here (V6 already retired
--     uq_active_seat_hold / idx_holds_event_* / idx_res_event_status).
--   * This migration is a DATA gate, not a nullability change: it aborts the
--     migration when orphan rows remain, so cleanup can never silently strand
--     reservations/holds without session identity. A hard NOT NULL on
--     event_session_id is tracked follow-up TASK-P12-009
--     (.ai/tasks/phase-12-event-sessions/009-session-not-null-hardening.md):
--     deferred because the backfill verification suites
--     (SessionScopedInventoryIntegrationTest orders 11-13) intentionally
--     persist legacy-NULL rows through JPA on the production migration chain
--     to verify SessionInventoryBackfillService itself; V9-style NOT NULL
--     would break those inserts until they move to a staged pre-constraint
--     schema. Until then, SessionIntegrityStartupCheck alerts on NULL session
--     rows at boot (fail-open; enforcement at migrate time stays here).
--
-- DEPLOY ORDERING / FREEZE (REV-006): run the SessionInventoryBackfillService
-- deployment step and verify zero NULLs BEFORE this migration runs. Flyway
-- executes each migration in one transaction (Postgres transactional DDL
-- makes gate + outcome atomic against committed state), but writers blocked
-- on the migration lock commit AFTER it: freeze legacy event-scoped writers
-- for the deploy window, otherwise a sessionless row committed
-- concurrently with the deploy lands post-gate with no session identity and
-- is only caught afterwards by SessionIntegrityStartupCheck.
-- Post-deploy data-quality queries live in
-- backend/scripts/p12-007-postdeploy-data-quality.sql (must return zero rows).
--
-- FAIL-CLOSED GATE: raises before any schema change when NULLs remain.
--   Orphan reservations: SELECT id FROM reservations WHERE event_session_id IS NULL;
--   Orphan holds:        SELECT id FROM seat_holds WHERE event_session_id IS NULL;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM reservations WHERE event_session_id IS NULL) THEN
        RAISE EXCEPTION 'P12-007 gate: reservations without event_session_id exist; run SessionInventoryBackfillService before V8';
    END IF;
    IF EXISTS (SELECT 1 FROM seat_holds WHERE event_session_id IS NULL) THEN
        RAISE EXCEPTION 'P12-007 gate: seat_holds without event_session_id exist; run SessionInventoryBackfillService before V8';
    END IF;
END
$$;

-- No DDL change: the gate above is the enforcement. Session writes always
-- carry event_session_id (ReservationServiceImpl derives it from the trusted
-- session booking context; the session is the sole booking key since P12-007).
SELECT 1;
