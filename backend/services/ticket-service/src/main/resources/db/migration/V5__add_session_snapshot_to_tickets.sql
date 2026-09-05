-- P12-004 (TASK-P12-004 / ADR-011): persist an immutable session schedule snapshot
-- on every issued ticket.
--
-- Additive and backfill-safe: all columns are NULLABLE so pre-P12-004 ticket
-- rows remain valid. Ticket issuance always populates the snapshot from the
-- trusted PaymentCompleted payload (cross-checked against the stored
-- reservation snapshot); the columns are immutable by application convention
-- (updatable = false, never reassigned). Ticket/API/QR/PDF rendering reads the
-- stored snapshot and must never lazily look up mutable event state to learn
-- which showing a ticket represents.

ALTER TABLE tickets
    ADD COLUMN IF NOT EXISTS event_session_id UUID,
    ADD COLUMN IF NOT EXISTS session_starts_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS session_ends_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS session_timezone VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_tickets_event_session_status
    ON tickets(event_session_id, status);
