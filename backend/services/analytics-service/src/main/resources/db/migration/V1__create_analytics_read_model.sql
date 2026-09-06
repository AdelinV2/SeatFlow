-- V1 analytics read model (TASK-P14-001).
--
-- Disposable/rebuildable event-derived projection state owned exclusively by
-- analytics-service (database seatflow_analytics). Never a source of truth for
-- reservation, payment, ticket, event, or seat state.
--
-- Design rules enforced here:
-- - opaque correlation IDs only (event_id, event_session_id, reservation_id,
--   payment_id, ticket_id); no foreign keys to another service's tables;
-- - money in integral minor units (BIGINT) + 3-letter currency; no floating point;
-- - operational counts are currency-neutral (no currency in key);
-- - financial aggregates are currency-keyed (currency is part of key);
-- - no PII columns (no email/name/address/payment-method/JWT data);
-- - UTC timestamps (TIMESTAMPTZ); daily buckets use the UTC calendar date;
-- - additive Flyway evolution only; never edit this migration once merged/deployed.

-- ---------------------------------------------------------------------------
-- processed_events: durable EventEnvelope deduplication boundary.
-- Projection behavior itself is implemented in P14-002.
-- ---------------------------------------------------------------------------
CREATE TABLE processed_events (
    event_id         VARCHAR(128) NOT NULL,
    event_type       VARCHAR(128) NOT NULL,
    source_topic     VARCHAR(255) NOT NULL,
    source_partition INTEGER NULL,
    source_offset    BIGINT NULL,
    occurred_at      TIMESTAMPTZ NOT NULL,
    processed_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_processed_events PRIMARY KEY (event_id)
);

CREATE INDEX idx_processed_events_occurred_at ON processed_events(occurred_at);
CREATE INDEX idx_processed_events_processed_at ON processed_events(processed_at);
CREATE INDEX idx_processed_events_type_occurred_at ON processed_events(event_type, occurred_at);

-- ---------------------------------------------------------------------------
-- analytics_session_facts: one analytics snapshot per event session.
-- Populated only from events in later tasks.
-- ---------------------------------------------------------------------------
CREATE TABLE analytics_session_facts (
    event_session_id    UUID NOT NULL,
    event_id            UUID NOT NULL,
    venue_id            UUID NULL,
    event_title         VARCHAR(255) NULL,
    session_label       VARCHAR(255) NULL,
    starts_at           TIMESTAMPTZ NULL,
    ends_at             TIMESTAMPTZ NULL,
    status              VARCHAR(64) NULL,
    capacity_snapshot   INTEGER NULL,
    last_source_event_at TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_analytics_session_facts PRIMARY KEY (event_session_id),
    CONSTRAINT chk_session_facts_capacity CHECK (capacity_snapshot IS NULL OR capacity_snapshot >= 0)
);

CREATE INDEX idx_session_facts_event_id ON analytics_session_facts(event_id);
CREATE INDEX idx_session_facts_starts_at ON analytics_session_facts(starts_at);
CREATE INDEX idx_session_facts_status ON analytics_session_facts(status);

-- ---------------------------------------------------------------------------
-- analytics_reservation_facts: one row per reservation, no PII.
-- Currency/quote are optional analytics evidence only; currency-neutral
-- reservation counts must not depend on them.
-- ---------------------------------------------------------------------------
CREATE TABLE analytics_reservation_facts (
    reservation_id       UUID NOT NULL,
    event_id             UUID NOT NULL,
    event_session_id     UUID NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL,
    confirmed_at         TIMESTAMPTZ NULL,
    expired_at           TIMESTAMPTZ NULL,
    refunded_at          TIMESTAMPTZ NULL,
    seat_count           INTEGER NOT NULL,
    currency             VARCHAR(3) NULL,
    quoted_total_minor   BIGINT NULL,
    last_source_event_at TIMESTAMPTZ NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_analytics_reservation_facts PRIMARY KEY (reservation_id),
    CONSTRAINT chk_reservation_facts_seat_count CHECK (seat_count > 0),
    CONSTRAINT chk_reservation_facts_quoted_total CHECK (quoted_total_minor IS NULL OR quoted_total_minor >= 0)
);

CREATE INDEX idx_reservation_facts_event_id ON analytics_reservation_facts(event_id);
CREATE INDEX idx_reservation_facts_event_session_id ON analytics_reservation_facts(event_session_id);
CREATE INDEX idx_reservation_facts_created_at ON analytics_reservation_facts(created_at);

-- ---------------------------------------------------------------------------
-- analytics_payment_facts: one row per payment identity.
-- May exist before its reservation fact (no global cross-topic ordering).
-- Provisional refund-before-completion is tolerated at the schema level while
-- completion is unknown; projection logic (P14-003) requires
-- refunded_amount_minor <= completed_amount_minor once both are known and
-- fails/parks invalid source history instead of clamping it.
-- ---------------------------------------------------------------------------
CREATE TABLE analytics_payment_facts (
    payment_id             UUID NOT NULL,
    reservation_id         UUID NOT NULL,
    event_session_id       UUID NULL,
    latest_status          VARCHAR(64) NULL,
    currency               VARCHAR(3) NULL,
    completed_amount_minor BIGINT NULL,
    refunded_amount_minor  BIGINT NULL,
    completed_at           TIMESTAMPTZ NULL,
    failed_at              TIMESTAMPTZ NULL,
    refunded_at            TIMESTAMPTZ NULL,
    last_source_event_at   TIMESTAMPTZ NOT NULL,
    updated_at             TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_analytics_payment_facts PRIMARY KEY (payment_id),
    CONSTRAINT chk_payment_facts_completed_amount CHECK (completed_amount_minor IS NULL OR completed_amount_minor >= 0),
    CONSTRAINT chk_payment_facts_refunded_amount CHECK (refunded_amount_minor IS NULL OR refunded_amount_minor >= 0)
);

CREATE INDEX idx_payment_facts_reservation_id ON analytics_payment_facts(reservation_id);
CREATE INDEX idx_payment_facts_event_session_id ON analytics_payment_facts(event_session_id);
CREATE INDEX idx_payment_facts_latest_status ON analytics_payment_facts(latest_status);

-- ---------------------------------------------------------------------------
-- analytics_ticket_facts: one row per ticket.
-- May temporarily lack reservation/session correlation when a scan event
-- arrives before the issue event. A ticket contributes at most one
-- attendance unit regardless of repeated scans.
-- ---------------------------------------------------------------------------
CREATE TABLE analytics_ticket_facts (
    ticket_id            UUID NOT NULL,
    reservation_id       UUID NULL,
    event_session_id     UUID NULL,
    issued_at            TIMESTAMPTZ NULL,
    revoked_at           TIMESTAMPTZ NULL,
    first_scanned_at     TIMESTAMPTZ NULL,
    status               VARCHAR(64) NOT NULL,
    last_source_event_at TIMESTAMPTZ NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_analytics_ticket_facts PRIMARY KEY (ticket_id)
);

CREATE INDEX idx_ticket_facts_reservation_id ON analytics_ticket_facts(reservation_id);
CREATE INDEX idx_ticket_facts_event_session_id ON analytics_ticket_facts(event_session_id);
CREATE INDEX idx_ticket_facts_status ON analytics_ticket_facts(status);

-- ---------------------------------------------------------------------------
-- daily_operational_metrics: currency-neutral counts by UTC date + event + session.
-- Exactly one row per (date, event, session); never duplicated per currency.
-- ---------------------------------------------------------------------------
CREATE TABLE daily_operational_metrics (
    metric_date            DATE NOT NULL,
    event_id               UUID NOT NULL,
    event_session_id       UUID NOT NULL,
    reservations_created   BIGINT NOT NULL DEFAULT 0,
    reservations_confirmed BIGINT NOT NULL DEFAULT 0,
    reservations_expired   BIGINT NOT NULL DEFAULT 0,
    payments_succeeded     BIGINT NOT NULL DEFAULT 0,
    payments_with_failure  BIGINT NOT NULL DEFAULT 0,
    refunds_completed      BIGINT NOT NULL DEFAULT 0,
    tickets_issued         BIGINT NOT NULL DEFAULT 0,
    tickets_revoked        BIGINT NOT NULL DEFAULT 0,
    tickets_scanned        BIGINT NOT NULL DEFAULT 0,
    updated_at             TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_daily_operational_metrics PRIMARY KEY (metric_date, event_id, event_session_id),
    CONSTRAINT chk_daily_ops_created CHECK (reservations_created >= 0),
    CONSTRAINT chk_daily_ops_confirmed CHECK (reservations_confirmed >= 0),
    CONSTRAINT chk_daily_ops_expired CHECK (reservations_expired >= 0),
    CONSTRAINT chk_daily_ops_pay_ok CHECK (payments_succeeded >= 0),
    CONSTRAINT chk_daily_ops_pay_fail CHECK (payments_with_failure >= 0),
    CONSTRAINT chk_daily_ops_refunds CHECK (refunds_completed >= 0),
    CONSTRAINT chk_daily_ops_tix_issued CHECK (tickets_issued >= 0),
    CONSTRAINT chk_daily_ops_tix_revoked CHECK (tickets_revoked >= 0),
    CONSTRAINT chk_daily_ops_tix_scanned CHECK (tickets_scanned >= 0)
);

CREATE INDEX idx_daily_ops_metric_date ON daily_operational_metrics(metric_date);
CREATE INDEX idx_daily_ops_event_session_id ON daily_operational_metrics(event_session_id);
CREATE INDEX idx_daily_ops_event_id ON daily_operational_metrics(event_id);

-- ---------------------------------------------------------------------------
-- daily_revenue_metrics: currency-specific financial aggregate
-- by UTC date + event + session + currency. Never sum across currencies.
-- net_revenue_minor is derived (gross - refunded), not stored.
-- ---------------------------------------------------------------------------
CREATE TABLE daily_revenue_metrics (
    metric_date            DATE NOT NULL,
    event_id               UUID NOT NULL,
    event_session_id       UUID NOT NULL,
    currency               VARCHAR(3) NOT NULL,
    payments_succeeded     BIGINT NOT NULL DEFAULT 0,
    refunds_completed      BIGINT NOT NULL DEFAULT 0,
    gross_revenue_minor    BIGINT NOT NULL DEFAULT 0,
    refunded_revenue_minor BIGINT NOT NULL DEFAULT 0,
    updated_at             TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_daily_revenue_metrics PRIMARY KEY (metric_date, event_id, event_session_id, currency),
    CONSTRAINT chk_daily_rev_pay_ok CHECK (payments_succeeded >= 0),
    CONSTRAINT chk_daily_rev_refunds CHECK (refunds_completed >= 0),
    CONSTRAINT chk_daily_rev_gross CHECK (gross_revenue_minor >= 0),
    CONSTRAINT chk_daily_rev_refunded CHECK (refunded_revenue_minor >= 0)
);

CREATE INDEX idx_daily_rev_metric_date ON daily_revenue_metrics(metric_date);
CREATE INDEX idx_daily_rev_event_session_id ON daily_revenue_metrics(event_session_id);
CREATE INDEX idx_daily_rev_event_id ON daily_revenue_metrics(event_id);

-- ---------------------------------------------------------------------------
-- event_session_metrics: currency-neutral lifetime operational aggregate.
-- Counters appear only once per session (no currency key).
-- ---------------------------------------------------------------------------
CREATE TABLE event_session_metrics (
    event_session_id       UUID NOT NULL,
    event_id               UUID NOT NULL,
    capacity_snapshot      INTEGER NULL,
    reservations_created   BIGINT NOT NULL DEFAULT 0,
    reservations_confirmed BIGINT NOT NULL DEFAULT 0,
    reservations_expired   BIGINT NOT NULL DEFAULT 0,
    payments_succeeded     BIGINT NOT NULL DEFAULT 0,
    payments_with_failure  BIGINT NOT NULL DEFAULT 0,
    refunds_completed      BIGINT NOT NULL DEFAULT 0,
    tickets_issued         BIGINT NOT NULL DEFAULT 0,
    tickets_revoked        BIGINT NOT NULL DEFAULT 0,
    tickets_scanned        BIGINT NOT NULL DEFAULT 0,
    last_projected_event_at TIMESTAMPTZ NULL,
    updated_at             TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_event_session_metrics PRIMARY KEY (event_session_id),
    CONSTRAINT chk_session_metrics_capacity CHECK (capacity_snapshot IS NULL OR capacity_snapshot >= 0),
    CONSTRAINT chk_session_metrics_created CHECK (reservations_created >= 0),
    CONSTRAINT chk_session_metrics_confirmed CHECK (reservations_confirmed >= 0),
    CONSTRAINT chk_session_metrics_expired CHECK (reservations_expired >= 0),
    CONSTRAINT chk_session_metrics_pay_ok CHECK (payments_succeeded >= 0),
    CONSTRAINT chk_session_metrics_pay_fail CHECK (payments_with_failure >= 0),
    CONSTRAINT chk_session_metrics_refunds CHECK (refunds_completed >= 0),
    CONSTRAINT chk_session_metrics_tix_issued CHECK (tickets_issued >= 0),
    CONSTRAINT chk_session_metrics_tix_revoked CHECK (tickets_revoked >= 0),
    CONSTRAINT chk_session_metrics_tix_scanned CHECK (tickets_scanned >= 0)
);

CREATE INDEX idx_session_metrics_event_id ON event_session_metrics(event_id);

-- ---------------------------------------------------------------------------
-- event_session_revenue_metrics: currency-specific lifetime financial aggregate.
-- Never materialize a single mixed-currency row.
-- ---------------------------------------------------------------------------
CREATE TABLE event_session_revenue_metrics (
    event_session_id       UUID NOT NULL,
    event_id               UUID NOT NULL,
    currency               VARCHAR(3) NOT NULL,
    payments_succeeded     BIGINT NOT NULL DEFAULT 0,
    refunds_completed      BIGINT NOT NULL DEFAULT 0,
    gross_revenue_minor    BIGINT NOT NULL DEFAULT 0,
    refunded_revenue_minor BIGINT NOT NULL DEFAULT 0,
    last_projected_event_at TIMESTAMPTZ NULL,
    updated_at             TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_event_session_revenue_metrics PRIMARY KEY (event_session_id, currency),
    CONSTRAINT chk_session_rev_pay_ok CHECK (payments_succeeded >= 0),
    CONSTRAINT chk_session_rev_refunds CHECK (refunds_completed >= 0),
    CONSTRAINT chk_session_rev_gross CHECK (gross_revenue_minor >= 0),
    CONSTRAINT chk_session_rev_refunded CHECK (refunded_revenue_minor >= 0)
);

CREATE INDEX idx_session_rev_event_id ON event_session_revenue_metrics(event_id);
