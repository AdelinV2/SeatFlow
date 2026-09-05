-- V3__create_event_sessions_and_backfill.sql
--
-- Phase 12 (TASK-P12-001): introduce the canonical event_sessions table and backfill
-- exactly one deterministic session for every pre-Phase-12 legacy event.
--
-- ADDITIVE MIGRATION ONLY: this script MUST NOT alter, rename or drop any column
-- of the events table. Pre-Phase-12 code keeps reading events.event_date until
-- a later Phase 12 task removes the compatibility path.
--
-- BACKFILL RULES (deterministic, documented, non-destructive):
--   1. starts_at copies the legacy events.event_date instant verbatim. Both columns
--      are TIMESTAMPTZ so the instant is preserved exactly. No timezone
--      reinterpretation is performed.
--   2. Legacy events only carry a single instant (no duration). ends_at is derived
--      as starts_at plus a fixed 2-hour window (event_date + INTERVAL '2 hours').
--      This is a deterministic backfill default, not a product duration promise.
--      The ck_event_session_time_order check below guarantees starts_at < ends_at.
--   3. Status mapping is explicit and preserves terminal state only:
--        CANCELLED  -> CANCELLED (terminal state preserved)
--        COMPLETED  -> COMPLETED (terminal state preserved)
--        anything else (DRAFT, PUBLISHED) -> SCHEDULED
--      No booking, refund or cancellation semantics are invented. Whether a
--      backfilled session lies in the past is preserved by its timestamps.
--      Cancellation and refund orchestration are out of scope for this task.
--   4. timezone stays NULL for migrated rows because the original IANA zone cannot
--      be reconstructed safely from an instant. Never fabricate a city timezone.
--   5. sale_starts_at and sale_ends_at stay NULL for migrated rows. Legacy events
--      carry no sales window, and NULL keeps the sale-window checks permissive.
--   6. IDs use gen_random_uuid(), the repository-approved UUID mechanism already
--      used by V1. No extra extension is required (built into PostgreSQL 13+).
--
-- DUPLICATE PROTECTION: the partial unique index uq_event_sessions_legacy_event
-- permits at most one row with legacy_backfill = TRUE per event. Flyway executes
-- this migration once, and the index additionally guarantees at the data-model
-- level that a redeploy can never create a second legacy session for one event.
--
-- PARITY VERIFICATION QUERIES (run by the QA gate and the Testcontainers suite):
--   Every legacy event has exactly one backfilled session
--   SELECT COUNT(*) FROM events
--   SELECT COUNT(DISTINCT event_id) FROM event_sessions WHERE legacy_backfill = TRUE
--   Events without a backfilled session (must return zero rows)
--   SELECT e.id FROM events e
--     LEFT JOIN event_sessions s
--       ON s.event_id = e.id AND s.legacy_backfill = TRUE
--     WHERE s.id IS NULL
--   Timestamp mismatches between legacy dates and backfilled starts (must be zero)
--   SELECT s.id FROM event_sessions s
--     JOIN events e ON e.id = s.event_id
--     WHERE s.legacy_backfill = TRUE AND s.starts_at IS DISTINCT FROM e.event_date
--   Backfilled duration drift (must return zero rows)
--   SELECT s.id FROM event_sessions s
--     WHERE s.legacy_backfill = TRUE
--       AND s.ends_at IS DISTINCT FROM s.starts_at + INTERVAL '2 hours'

CREATE TABLE event_sessions (
    id               UUID         NOT NULL DEFAULT gen_random_uuid(),
    event_id         UUID         NOT NULL,
    starts_at        TIMESTAMPTZ  NOT NULL,
    ends_at          TIMESTAMPTZ  NOT NULL,
    sale_starts_at   TIMESTAMPTZ,
    sale_ends_at     TIMESTAMPTZ,
    status           VARCHAR(32)  NOT NULL,
    timezone         VARCHAR(64),
    legacy_backfill  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_event_sessions PRIMARY KEY (id),
    CONSTRAINT fk_event_sessions_event FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE,
    CONSTRAINT chk_event_session_time_order CHECK (starts_at < ends_at),
    CONSTRAINT chk_event_session_sale_order CHECK (
        sale_starts_at IS NULL OR sale_ends_at IS NULL OR sale_starts_at < sale_ends_at
    ),
    CONSTRAINT chk_event_session_sale_end CHECK (
        sale_ends_at IS NULL OR sale_ends_at <= starts_at
    )
);

CREATE INDEX idx_event_sessions_event_start ON event_sessions(event_id, starts_at ASC);

CREATE UNIQUE INDEX uq_event_sessions_legacy_event
    ON event_sessions(event_id) WHERE legacy_backfill = TRUE;

INSERT INTO event_sessions (
    id, event_id, starts_at, ends_at,
    sale_starts_at, sale_ends_at, status, timezone,
    legacy_backfill, created_at, updated_at
)
SELECT
    gen_random_uuid(),
    e.id,
    e.event_date,
    e.event_date + INTERVAL '2 hours',
    NULL,
    NULL,
    CASE WHEN e.status IN ('CANCELLED', 'COMPLETED') THEN e.status ELSE 'SCHEDULED' END,
    NULL,
    TRUE,
    now(),
    now()
FROM events e;
