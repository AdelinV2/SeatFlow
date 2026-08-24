# 03 — Database Schemas & Caching Specification

SeatFlow enforces a strict **Database-per-Service** architecture using PostgreSQL 16+ as the transactional source of truth, managed via Flyway migrations. Redis is used strictly for transient caching, rate limiting, and real-time state coordination.

---

## 1. Database Architecture & Rules

- Every service connects exclusively to its designated database schema/catalog.
- Primary keys must use `UUID` generated via PostgreSQL `gen_random_uuid()`.
- Timestamps must use `TIMESTAMPTZ` and default to `now()`.
- Optimistic locking fields use `version BIGINT NOT NULL DEFAULT 0`.
- All foreign keys and frequently filtered columns (`user_id`, `event_id`, `status`, `expires_at`) must have explicit B-tree indexes.

---

## 2. Database Schemas by Service

### 2.1 User Service (`seatflow_user`)
```sql
CREATE TABLE users (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    external_id VARCHAR(255) UNIQUE NOT NULL, -- Subject ID from Entra/OIDC
    email       VARCHAR(255) UNIQUE NOT NULL,
    phone       VARCHAR(50),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

### 2.2 Seat Map Service (`seatflow_seatmap`)
```sql
CREATE TABLE venues (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    address     VARCHAR(500) NOT NULL,
    capacity    INT          NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE venue_sections (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    venue_id    UUID         NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL, -- e.g. "Main Floor", "Balcony Left"
    row_count   INT          NOT NULL,
    col_count   INT          NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_sections_venue ON venue_sections(venue_id);

CREATE TABLE seats (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    section_id  UUID         NOT NULL REFERENCES venue_sections(id) ON DELETE CASCADE,
    row_number  VARCHAR(10)  NOT NULL, -- e.g. "A", "B", "1"
    seat_number INT          NOT NULL, -- e.g. 1, 2, 3
    grid_x      INT          NOT NULL,
    grid_y      INT          NOT NULL,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_seat_in_section UNIQUE (section_id, row_number, seat_number)
);
CREATE INDEX idx_seats_section ON seats(section_id);
```

### 2.3 Event Service (`seatflow_event`)
```sql
CREATE TABLE events (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    venue_id    UUID           NOT NULL, -- Logical reference to seat-map-service
    title       VARCHAR(255)   NOT NULL,
    description TEXT           NOT NULL,
    category    VARCHAR(100)   NOT NULL, -- e.g. "CONCERT", "THEATRE"
    banner_url  VARCHAR(1000),
    event_date  TIMESTAMPTZ    NOT NULL,
    status      VARCHAR(30)    NOT NULL DEFAULT 'DRAFT', -- DRAFT, PUBLISHED, CANCELLED, COMPLETED
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_events_date ON events(event_date);
CREATE INDEX idx_events_status ON events(status);
CREATE INDEX idx_events_category ON events(category);
CREATE INDEX idx_events_venue ON events(venue_id);

CREATE TABLE event_pricing_tiers (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id    UUID           NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    section_id  UUID           NOT NULL, -- References venue_section
    category_name VARCHAR(100) NOT NULL, -- e.g. "VIP", "Standard", "Student"
    price       NUMERIC(10, 2) NOT NULL,
    currency    VARCHAR(3)     NOT NULL DEFAULT 'USD',
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_event_section_tier UNIQUE (event_id, section_id, category_name)
);
CREATE INDEX idx_pricing_event ON event_pricing_tiers(event_id);
```

### 2.4 Reservation Service (`seatflow_reservation`)
```sql
CREATE TABLE reservations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID,        -- NULL for guest checkouts
    customer_email  VARCHAR(255) NOT NULL,
    customer_name   VARCHAR(255),
    event_id        UUID         NOT NULL,
    status          VARCHAR(30)  NOT NULL, -- PENDING, CONFIRMED, CANCELLED, EXPIRED
    expires_at      TIMESTAMPTZ  NOT NULL,
    idempotency_key VARCHAR(255) UNIQUE NOT NULL,
    total_amount    NUMERIC(10,2) NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_res_user ON reservations(user_id);
CREATE INDEX idx_res_customer_email ON reservations(customer_email);
CREATE INDEX idx_res_event ON reservations(event_id);
CREATE INDEX idx_res_status ON reservations(status);
CREATE INDEX idx_res_expires_at ON reservations(expires_at) WHERE status = 'PENDING';

CREATE TABLE seat_holds (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reservation_id UUID        NOT NULL REFERENCES reservations(id) ON DELETE CASCADE,
    event_id       UUID        NOT NULL,
    seat_id        UUID        NOT NULL,
    status         VARCHAR(30) NOT NULL, -- HELD, SOLD, RELEASED
    price          NUMERIC(10,2) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_active_seat_hold ON seat_holds(event_id, seat_id)
    WHERE status IN ('HELD', 'SOLD');
CREATE INDEX idx_holds_reservation ON seat_holds(reservation_id);
CREATE INDEX idx_holds_event_seat ON seat_holds(event_id, seat_id);

CREATE TABLE outbox_events (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id  UUID         NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    payload       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    retry_count   INT          NOT NULL DEFAULT 0,
    CONSTRAINT max_retries CHECK (retry_count <= 5)
);
CREATE INDEX idx_res_outbox_unpub ON outbox_events(created_at) WHERE published_at IS NULL;
```

### 2.5 Payment Service (`seatflow_payment`)
```sql
CREATE TABLE payments (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reservation_id     UUID         NOT NULL,
    user_id            UUID,        -- NULL for guest checkouts
    customer_email     VARCHAR(255) NOT NULL,
    event_id           UUID         NOT NULL,
    stripe_payment_id  VARCHAR(255) UNIQUE,
    idempotency_key    VARCHAR(255) UNIQUE NOT NULL,
    amount             NUMERIC(10,2) NOT NULL,
    currency           VARCHAR(3)   NOT NULL DEFAULT 'USD',
    status             VARCHAR(30)  NOT NULL, -- INITIATED, SUCCESS, FAILED, REFUNDED
    failure_reason     TEXT,
    version            BIGINT       NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_payments_reservation ON payments(reservation_id);
CREATE INDEX idx_payments_user ON payments(user_id);
CREATE INDEX idx_payments_customer_email ON payments(customer_email);
CREATE INDEX idx_payments_status ON payments(status);

CREATE TABLE outbox_events (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id  UUID         NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    payload       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    retry_count   INT          NOT NULL DEFAULT 0,
    CONSTRAINT max_retries CHECK (retry_count <= 5)
);
CREATE INDEX idx_pay_outbox_unpub ON outbox_events(created_at) WHERE published_at IS NULL;
```

### 2.6 Ticket Service (`seatflow_ticket`)
```sql
CREATE TABLE tickets (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reservation_id UUID         NOT NULL,
    payment_id     UUID         NOT NULL,
    user_id        UUID,        -- NULL for guest checkouts
    customer_email VARCHAR(255) NOT NULL,
    attendee_name  VARCHAR(255),
    event_id       UUID         NOT NULL,
    seat_id        UUID         NOT NULL,
    ticket_code    VARCHAR(64)  UNIQUE NOT NULL, -- Cryptographically secure token
    qr_code_data   TEXT         NOT NULL,
    status         VARCHAR(30)  NOT NULL DEFAULT 'VALID', -- VALID, USED, CANCELLED
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_tickets_user ON tickets(user_id);
CREATE INDEX idx_tickets_customer_email ON tickets(customer_email);
CREATE INDEX idx_tickets_event ON tickets(event_id);
CREATE INDEX idx_tickets_reservation ON tickets(reservation_id);
CREATE INDEX idx_tickets_code ON tickets(ticket_code);
CREATE INDEX idx_tickets_payment ON tickets(payment_id);

CREATE TABLE outbox_events (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id  UUID         NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    payload       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    retry_count   INT          NOT NULL DEFAULT 0,
    CONSTRAINT max_retries CHECK (retry_count <= 5)
);
CREATE INDEX idx_ticket_outbox_unpub ON outbox_events(created_at) WHERE published_at IS NULL;
```

### 2.7 Notification Service (`seatflow_notification`)
```sql
CREATE TABLE notification_logs (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_email VARCHAR(255) NOT NULL,
    template_type   VARCHAR(100) NOT NULL, -- e.g. "BOOKING_CONFIRMATION", "HOLD_EXPIRY"
    subject         VARCHAR(500) NOT NULL,
    status          VARCHAR(30)  NOT NULL, -- SENT, FAILED
    error_message   TEXT,
    sent_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    retry_count     INT          NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_recipient ON notification_logs(recipient_email);
```

---

## 3. Redis Data Structures & Key Patterns

| Key Pattern | Data Type | TTL | Purpose |
|---|---|---|---|
| `rate:ip:{clientIp}` | Integer (Counter) | 1 min | API Gateway rate limiting |
| `cache:event:{eventId}` | String (JSON) | 5 min | Cached public event summary |
| `cache:seatmap:{venueId}` | String (JSON) | 30 min | Cached static venue seat layout |
| `realtime:event:{eventId}:seats` | Hash (seatId -> status) | None | In-memory mirror for fast STOMP WebSocket broadcasts |
