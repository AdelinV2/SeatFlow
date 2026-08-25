-- ====================================================================
-- V3: Create outbox_events table for Transactional Outbox Pattern
-- Database: seatflow_seatmap
-- Spec: .ai/architecture/05-messaging-and-outbox.md (Section 3)
-- ADR: ADR-002 (outbox polling index)
-- ====================================================================

CREATE TABLE outbox_events (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    aggregate_id  UUID         NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    payload       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    retry_count   INT          NOT NULL DEFAULT 0,

    CONSTRAINT pk_seatmap_outbox PRIMARY KEY (id),
    CONSTRAINT chk_seatmap_outbox_retry CHECK (retry_count >= 0 AND retry_count <= 5)
);

-- Partial index for high-throughput outbox publisher polling
CREATE INDEX idx_seatmap_outbox_unpub ON outbox_events(created_at ASC) WHERE published_at IS NULL;
CREATE INDEX idx_seatmap_outbox_aggregate ON outbox_events(aggregate_id, created_at DESC);
