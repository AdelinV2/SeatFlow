-- P12-004 (TASK-P12-004 / ADR-011): persist session identity and the immutable
-- showing snapshot on payments for audit/correlation.
--
-- Additive and backfill-safe: all columns are NULLABLE so pre-P12-004 payment
-- rows remain valid. New payment pipelines always populate event_session_id
-- from the trusted reservation-service response (client input can never supply
-- session metadata; CreatePaymentIntentRequest carries reservation identity
-- only). The stored snapshot is what webhook/sync paths publish on
-- PaymentCompleted/PaymentFailed, so downstream ticket issuance renders the
-- exact showing without a live mutable-event lookup.
-- Payment authorization is unchanged: server-derived reservation amount/currency.

ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS event_session_id UUID,
    ADD COLUMN IF NOT EXISTS session_starts_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS session_ends_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS session_timezone VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_payments_event_session_id
    ON payments(event_session_id);
