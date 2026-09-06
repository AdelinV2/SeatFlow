-- V2 analytics read-model additions (TASK-P14-003).
--
-- Additive-only evolution over V1. Never edit V1 after it was merged/deployed.
--
-- 1. analytics_reservation_facts.cancelled_at: the verified final reservation contract
--    includes ReservationCancelledEvent (user-initiated release of a PENDING hold), which V1
--    cannot represent. Cancellation is a terminal non-purchase outcome kept separate from
--    expiration: a cancelled reservation counts toward reservations_created only, never toward
--    reservations_confirmed/reservations_expired. This preserves the exact V1 aggregate bucket
--    semantics (expired means actually expired) while retaining terminal-state evidence so a
--    stale ReservationExpiredEvent replay cannot reclassify a cancelled hold.
--
-- 2. analytics_ticket_revocation_facts: retention for reservation-scoped ticket revocation
--    evidence. If the final P13 revocation contract revokes by reservation identity without
--    enumerating ticket IDs, the event is persisted here and reconciled when the actual ticket
--    issue events arrive/replay. Per-ticket revocations (TicketRevoked with ticketId) update
--    analytics_ticket_facts directly and never need this table.

ALTER TABLE analytics_reservation_facts
    ADD COLUMN cancelled_at TIMESTAMPTZ NULL;

CREATE TABLE analytics_ticket_revocation_facts (
    reservation_id       UUID NOT NULL,
    event_session_id     UUID NULL,
    revoked_at           TIMESTAMPTZ NOT NULL,
    source_event_id      VARCHAR(128) NULL,
    last_source_event_at TIMESTAMPTZ NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_analytics_ticket_revocation_facts PRIMARY KEY (reservation_id)
);

CREATE INDEX idx_ticket_revocation_facts_event_session_id
    ON analytics_ticket_revocation_facts(event_session_id);
