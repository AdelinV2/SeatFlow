-- ====================================================================
-- V2: Create outbox_events table for Transactional Outbox Pattern
-- ====================================================================

CREATE TABLE outbox_events (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id  UUID         NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    payload       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    retry_count   INT          NOT NULL DEFAULT 0,
    CONSTRAINT max_retries CHECK (retry_count <= 5)
);

-- Partial index for efficient polling of unpublished events
CREATE INDEX idx_user_outbox_unpublished ON outbox_events(created_at) WHERE published_at IS NULL;
