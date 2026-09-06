-- P12-004 (TASK-P12-004 / ADR-011): persist an immutable session schedule snapshot
-- on reservations at creation time.
--
-- Strategy is additive-first, mirroring V6:
--   1. This migration adds NULLABLE snapshot columns. Legacy rows (created before
--      P12-004) keep NULLs; new writes always populate session_starts_at /
--      session_ends_at from the trusted event-service booking context resolved in
--      ReservationServiceImpl. session_timezone stays NULL until a trusted source
--      exposes it (nullable IANA ZoneId metadata per task contract); downstream
--      rendering falls back to the stored offset-aware instant.
--   2. Columns are immutable by application convention (updatable = false on the
--      entity, never reassigned after creation). The snapshot captured at hold
--      time is what confirm/payment/ticket/notification flows carry forward, so
--      later catalog edits can never rewrite an issued ticket's showing.
--   3. No backfill is attempted here: Flyway MUST NOT perform cross-service calls.
--      Pre-P12-004 reservations resolve their session through the existing
--      SessionInventoryBackfillService path; confirmation of such rows carries
--      whatever snapshot is stored (possibly NULL times), and ticket issuance
--      prefers the payment-event snapshot with the reservation snapshot as check.

ALTER TABLE reservations
    ADD COLUMN IF NOT EXISTS session_starts_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS session_ends_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS session_timezone VARCHAR(64);

-- Lookup support for session-schedule-aware reservation queries (audit/support).
CREATE INDEX IF NOT EXISTS idx_res_session_starts_at
    ON reservations(session_starts_at);
