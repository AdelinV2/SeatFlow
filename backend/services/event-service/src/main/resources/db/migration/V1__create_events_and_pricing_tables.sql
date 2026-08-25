CREATE TABLE events (
    id          UUID          NOT NULL DEFAULT gen_random_uuid(),
    venue_id    UUID          NOT NULL,
    title       VARCHAR(255)  NOT NULL,
    description TEXT          NOT NULL,
    category    VARCHAR(100)  NOT NULL,
    banner_url  VARCHAR(1000),
    event_date  TIMESTAMPTZ   NOT NULL,
    status      VARCHAR(30)   NOT NULL DEFAULT 'DRAFT',
    version     BIGINT        NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_events PRIMARY KEY (id),
    CONSTRAINT chk_events_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'CANCELLED', 'COMPLETED'))
);

CREATE INDEX idx_events_status_date ON events(status, event_date ASC);
CREATE INDEX idx_events_category_date ON events(category, event_date ASC) WHERE status = 'PUBLISHED';
CREATE INDEX idx_events_venue_id ON events(venue_id);
CREATE INDEX idx_events_created_at ON events(created_at DESC);

CREATE TABLE event_pricing_tiers (
    id            UUID           NOT NULL DEFAULT gen_random_uuid(),
    event_id      UUID           NOT NULL,
    section_id    UUID           NOT NULL,
    category_name VARCHAR(100)   NOT NULL,
    price         NUMERIC(10, 2) NOT NULL,
    currency      VARCHAR(3)     NOT NULL DEFAULT 'USD',
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT pk_event_pricing_tiers PRIMARY KEY (id),
    CONSTRAINT fk_pricing_events FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE,
    CONSTRAINT uq_event_section_tier UNIQUE (event_id, section_id, category_name),
    CONSTRAINT chk_pricing_price CHECK (price >= 0.00),
    CONSTRAINT chk_pricing_currency CHECK (length(currency) = 3)
);

CREATE INDEX idx_pricing_event_id ON event_pricing_tiers(event_id);
CREATE INDEX idx_pricing_event_section ON event_pricing_tiers(event_id, section_id);
