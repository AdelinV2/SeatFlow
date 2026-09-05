-- V4__remove_legacy_event_schedule.sql
--
-- Phase 12 (TASK-P12-007 / ADR-011): remove legacy event-level schedule ownership.
-- After P12-001..006 every event owns >= 1 session, session-scoped reservation
-- inventory (V6), checkout snapshots (V7), session realtime topics, and
-- session-aware frontends are live. Event retains catalog identity only
-- (venue/pricing/category/status); EventSession owns startsAt/endsAt/sale windows.
--
-- DESTRUCTIVE GATE (fail-closed): the DO block below aborts the migration when
-- any event lacks a session, proving the parity precondition BEFORE the legacy
-- column is dropped. Code+data verification preconditions (see task):
--   1. every event has >= 1 session;
--   2. reservation/hold/ticket/payment rows requiring session identity carry it;
--   3. frontend sends no legacy schedule/booking fields;
--   4. Kafka consumers accept session-aware payloads (fail-closed, no inference);
--   5. realtime client uses /topic/sessions/{id}/seats only;
--   6. static grep finds no booking-semantic eventId use in prohibited areas.
--
-- PARITY VERIFICATION QUERIES (must return zero rows before/after):
--   Events without any session:
--   SELECT e.id FROM events e LEFT JOIN event_sessions s ON s.event_id = e.id
--     WHERE s.id IS NULL;
--   Legacy timestamp mismatches (pre-drop, informational):
--   SELECT s.id FROM event_sessions s JOIN events e ON e.id = s.event_id
--     WHERE s.legacy_backfill = TRUE AND s.starts_at IS DISTINCT FROM e.event_date;
--
-- DEPLOY ORDERING / FREEZE (REV-006): land P12-001..006 (sessions,
-- session inventory, snapshots, session topics, session-aware frontends)
-- first, run the data-integrity gates, then freeze legacy event-schedule
-- writers for the deploy window (old frontends writing startsAt/endsAt,
-- backfill/repair jobs). Flyway runs this migration in one transaction
-- (Postgres transactional DDL makes gate + DROP COLUMN atomic against
-- committed state), but writers blocked on the DDL lock commit AFTER the
-- migration: a sessionless event committed concurrently with the deploy
-- window lives on without event_date and is invisible to the gate above.
-- Re-run the parity queries after the deploy window closes; the
-- post-deploy data-quality query set lives in
-- backend/scripts/p12-007-postdeploy-data-quality.sql.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM events e
        LEFT JOIN event_sessions s ON s.event_id = e.id
        WHERE s.id IS NULL
    ) THEN
        RAISE EXCEPTION 'P12-007 gate: events without sessions exist; refusing to drop event_date';
    END IF;
END
$$;

DROP INDEX IF EXISTS idx_events_status_date;
DROP INDEX IF EXISTS idx_events_category_date;

ALTER TABLE events
    DROP COLUMN IF EXISTS event_date;

-- Session-aware catalog support: admin listing by status/recency.
CREATE INDEX IF NOT EXISTS idx_events_status_created
    ON events(status, created_at DESC);

-- Session-aware search support: next visible future session derivation.
CREATE INDEX IF NOT EXISTS idx_event_sessions_status_start
    ON event_sessions(status, starts_at ASC);
