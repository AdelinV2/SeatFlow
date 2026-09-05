-- P12-003 (TASK-P12-003 / ADR-011): make event_session_id the authoritative inventory key.
--
-- Strategy is additive-first:
--   1. This migration adds NULLABLE event_session_id columns plus session-keyed lookup
--      indexes and REPLACES the event-scoped active uniqueness with the session-scoped
--      one. Legacy event_id columns stay for compatibility/audit (removed in P12-007).
--   2. Existing rows are backfilled by SessionInventoryBackfillService (reviewed,
--      idempotent, rerunnable), which resolves each distinct legacy event_id through
--      event-service booking-context. Flyway MUST NOT perform cross-service calls.
--   3. NOT NULL constraints are added only after the backfill gate proves zero NULLs.
--
-- Index/query classification (booking vs display/audit):
--   BOOKING (session-scoped after this migration):
--     uq_active_seat_hold_session  replaces uq_active_seat_hold (zero double booking per session)
--     idx_holds_session_seat       replaces idx_holds_event_seat (availability/locking lookups)
--     idx_holds_session_status     replaces idx_holds_event_status (active-hold evaluation)
--     idx_res_session_status       replaces idx_res_event_status (reservation lookup by session)
--   DISPLAY/AUDIT (kept on legacy event_id until P12-007):
--     idx_res_customer_email, idx_res_created_at, idx_res_user_status,
--     idx_res_pending_expires_at (15-minute sweeper, not event-scoped)
--
-- NOTE on the transition window: PostgreSQL treats NULLs as distinct in unique
-- indexes, so legacy rows with NULL event_session_id cannot collide with each other
-- under the new constraint, and new writes always carry a session id (enforced in
-- ReservationServiceImpl via trusted booking-context). Run the backfill deployment
-- step immediately after this migration; until zero NULLs are verified the legacy
-- event-scoped guarantee for old rows is not enforced by the database.

ALTER TABLE reservations
    ADD COLUMN IF NOT EXISTS event_session_id UUID;

ALTER TABLE seat_holds
    ADD COLUMN IF NOT EXISTS event_session_id UUID;

-- Session-keyed lookup indexes for active-hold/availability queries.
CREATE INDEX IF NOT EXISTS idx_res_session_status
    ON reservations(event_session_id, status);

CREATE INDEX IF NOT EXISTS idx_holds_session_seat
    ON seat_holds(event_session_id, seat_id);

CREATE INDEX IF NOT EXISTS idx_holds_session_status
    ON seat_holds(event_session_id, status);

-- Replace the event-scoped active uniqueness with the session-scoped one.
-- (event_session_id, seat_id) is now the inventory identity (ADR-011).
DROP INDEX IF EXISTS uq_active_seat_hold;

CREATE UNIQUE INDEX IF NOT EXISTS uq_active_seat_hold_session
    ON seat_holds(event_session_id, seat_id)
    WHERE status IN ('HELD', 'SOLD');

-- Retire superseded event-scoped lookup indexes; session indexes above take over.
DROP INDEX IF EXISTS idx_holds_event_seat;
DROP INDEX IF EXISTS idx_holds_event_status;
DROP INDEX IF EXISTS idx_res_event_status;
