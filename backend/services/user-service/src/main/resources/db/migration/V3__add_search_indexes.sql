-- ====================================================================
-- V3: Add search indexes for user-service entities
-- These back the @Index annotations on the User and OutboxEvent entities.
-- Flyway owns the schema (spring.jpa.hibernate.ddl-auto=validate), so the
-- indexes must be created here rather than relying on Hibernate auto-DDL.
-- ====================================================================

-- Users: faster lookups/filters by phone and by creation time
CREATE INDEX IF NOT EXISTS idx_users_phone ON users (phone);
CREATE INDEX IF NOT EXISTS idx_users_created_at ON users (created_at);

-- Outbox events: faster lookup by aggregate, event type, and publish state
-- (the unpublished-events poller is already served by idx_user_outbox_unpublished)
CREATE INDEX IF NOT EXISTS idx_outbox_aggregate_id ON outbox_events (aggregate_id);
CREATE INDEX IF NOT EXISTS idx_outbox_event_type ON outbox_events (event_type);
CREATE INDEX IF NOT EXISTS idx_outbox_published_at ON outbox_events (published_at);
