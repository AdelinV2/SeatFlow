-- ====================================================================
-- V4: Align database object names with ADR-002 naming conventions
-- (pk_<table>, uq_<table>_<columns>, chk_<table>_<rule>,
--  idx_<service>_outbox_unpub for the Transactional Outbox polling index)
--
-- Non-destructive: renames the auto/explicit names produced by V1/V2 into
-- ADR-compliant names. Safe to apply whether or not V1/V2 were already run,
-- because it targets the names Flyway/V1/V2 actually created.
-- ====================================================================

-- Users table
ALTER TABLE users RENAME CONSTRAINT users_pkey TO pk_users;
ALTER TABLE users RENAME CONSTRAINT users_external_id_key TO uq_users_external_id;
ALTER TABLE users RENAME CONSTRAINT users_email_key TO uq_users_email;

-- Outbox events table
ALTER TABLE outbox_events RENAME CONSTRAINT outbox_events_pkey TO pk_outbox_events;
ALTER TABLE outbox_events RENAME CONSTRAINT max_retries TO chk_outbox_events_retry_count;

-- Transactional Outbox polling partial index (ADR-002 §3.2.4)
ALTER INDEX idx_user_outbox_unpublished RENAME TO idx_user_outbox_unpub;
