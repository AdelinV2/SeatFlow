# 03 — Database Schemas & Caching Specification

SeatFlow enforces a strict **Database-per-Service** architecture using PostgreSQL 16+ as the transactional source of truth, managed via Flyway migrations. Redis is used strictly for transient caching, rate limiting, and real-time state coordination.

---

## 1. Database Architecture & Production Standards

### 1.1 Global Invariants & Rules
- **Database Isolation:** Every microservice connects exclusively to its own dedicated database catalog/schema. No cross-database joins, foreign keys, or transactions are permitted.
- **Primary Keys:** Primary keys must use `UUID` generated via PostgreSQL `gen_random_uuid()` (non-sequential, globally unique, avoids ID enumeration attacks).
- **Timezone Awareness:** Timestamps must use `TIMESTAMPTZ` and default to `now()`.
- **Optimistic Locking:** All mutable business aggregates (`events`, `reservations`, `payments`, `tickets`, `venues`) must include `version BIGINT NOT NULL DEFAULT 0` mapped to `@Version private Long version;` in JPA.
- **Financial Precision:** Prices and monetary amounts must use `NUMERIC(10, 2)` (never `FLOAT` or `DOUBLE`) with an accompanying ISO-4217 currency code `VARCHAR(3) NOT NULL DEFAULT 'USD'`.
- **String Enums:** Enums are stored as `VARCHAR(30)` with database-level `CHECK` constraints to guarantee valid state machine transitions.

### 1.2 DDL Object Naming Conventions (ADR-002)
To maintain consistency and simplify database operations, all database objects follow strict naming prefixes:

| Object Type | Naming Format | Example |
|---|---|---|
| **Primary Key** | `pk_<table>` | `pk_users`, `pk_seat_holds` |
| **Foreign Key** | `fk_<table>_<referenced_table>` | `fk_venue_sections_venues`, `fk_seat_holds_reservations` |
| **Unique Constraint** | `uq_<table>_<column(s)>` | `uq_users_email`, `uq_seats_section_row_seat` |
| **Standard B-Tree Index** | `idx_<table>_<column(s)>` | `idx_events_status_date`, `idx_res_customer_email` |
| **Partial Index** | `idx_<table>_<purpose>` (with `WHERE`) | `idx_res_pending_expires_at`, `idx_outbox_events_unpub` |
| **Check Constraint** | `chk_<table>_<field_or_rule>` | `chk_res_seat_count`, `chk_pricing_price` |

### 1.3 JPA Entity & Hibernate Production Standards Checklist
Every Spring Data JPA entity must adhere to the following production rules:
1. **Explicit `@Table` Metadata:** Always specify `name`, `uniqueConstraints`, and `indexes` matching the Flyway DDL specifications.
2. **Dynamic Updates (`@DynamicUpdate`):** Enable on high-write mutable entities (`Reservation`, `Payment`, `Event`) so Hibernate executes minimal SQL `UPDATE` statements containing only dirty columns.
3. **Flyway DDL Constraint Ownership:** All database check constraints (`chk_...`), foreign keys, and indexes are defined exclusively in Flyway SQL migrations (`db/migration/V...__...sql`). Never use deprecated vendor annotations like `@org.hibernate.annotations.Check` (deprecated since Hibernate 7). Enforce application-level validation via Jakarta Bean Validation in DTOs.
4. **Hibernate-Safe `equals()` & `hashCode()` (NEVER `@EqualsAndHashCode` or `@Data`):** Implement explicit `equals()` using `Hibernate.getClass(this) != Hibernate.getClass(o)` with null-safe `getId()` comparison, and `hashCode()` returning `getClass().hashCode()` to avoid breaking Hibernate proxy equality or Set bucket hash stability.
5. **ToString & Logging Safety (`@ToString`):** Use `@ToString(onlyExplicitlyIncluded = true)` or exclude relationship fields (`@ManyToOne`, `@OneToMany`) to prevent triggering lazy-loading and N+1 query loops.
6. **Native JSONB Mapping:** Map PostgreSQL JSONB columns using `@JdbcTypeCode(SqlTypes.JSON)` (e.g. `payload` in `OutboxEvent`).
7. **Column Immutability (`updatable = false`):** Mark immutable attributes (`id`, `createdAt`, `idempotencyKey`, `eventId`, `userId`) with `@Column(..., updatable = false)`.
8. **Monetary Precision:** Use `BigDecimal` with `@Column(precision = 10, scale = 2, nullable = false)`.
9. **Enum Mapping:** Always `@Enumerated(EnumType.STRING)`, never `ORDINAL`.

---

## 2. Database Schemas by Service

### 2.1 User Service (`seatflow_user`)

```sql
-- V1__create_users_table.sql
CREATE TABLE users (
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    external_id VARCHAR(255) NOT NULL, -- Subject ID (sub claim) from Microsoft Entra / OIDC
    email       VARCHAR(255) NOT NULL,
    phone       VARCHAR(50),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_external_id UNIQUE (external_id),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT chk_users_email_format CHECK (email ~* '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$')
);

-- Partial index for customer phone lookups (ignoring nulls)
CREATE INDEX idx_users_phone ON users(phone) WHERE phone IS NOT NULL;
-- Index for admin pagination and audit ordering
CREATE INDEX idx_users_created_at ON users(created_at DESC);

-- V2__create_outbox_events_table.sql
CREATE TABLE outbox_events (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    aggregate_id  UUID         NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    payload       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    retry_count   INT          NOT NULL DEFAULT 0,

    CONSTRAINT pk_user_outbox PRIMARY KEY (id),
    CONSTRAINT chk_user_outbox_retry CHECK (retry_count >= 0 AND retry_count <= 5)
);

-- Partial index for high-throughput outbox publisher polling
CREATE INDEX idx_user_outbox_unpub ON outbox_events(created_at ASC) WHERE published_at IS NULL;
CREATE INDEX idx_user_outbox_aggregate ON outbox_events(aggregate_id, created_at DESC);
```

---

### 2.2 Seat Map Service (`seatflow_seatmap`)

```sql
-- V1__create_venues_and_sections_tables.sql
CREATE TABLE venues (
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    address     VARCHAR(500) NOT NULL,
    city        VARCHAR(100) NOT NULL,
    country     VARCHAR(100) NOT NULL DEFAULT 'USA',
    capacity    INT          NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_venues PRIMARY KEY (id),
    CONSTRAINT uq_venues_name_city UNIQUE (name, city),
    CONSTRAINT chk_venues_capacity CHECK (capacity > 0)
);

CREATE INDEX idx_venues_city ON venues(city);
CREATE INDEX idx_venues_name ON venues(name);

CREATE TABLE venue_sections (
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    venue_id    UUID         NOT NULL,
    name        VARCHAR(100) NOT NULL, -- e.g. "Main Floor", "Balcony Left", "VIP Lounge"
    row_count   INT          NOT NULL,
    col_count   INT          NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_venue_sections PRIMARY KEY (id),
    CONSTRAINT fk_venue_sections_venues FOREIGN KEY (venue_id) REFERENCES venues(id) ON DELETE CASCADE,
    CONSTRAINT uq_venue_sections_venue_name UNIQUE (venue_id, name),
    CONSTRAINT chk_venue_sections_row_count CHECK (row_count > 0),
    CONSTRAINT chk_venue_sections_col_count CHECK (col_count > 0)
);

CREATE INDEX idx_venue_sections_venue_id ON venue_sections(venue_id);

-- V2__create_seats_table.sql
CREATE TABLE seats (
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    section_id  UUID         NOT NULL,
    row_label   VARCHAR(10)  NOT NULL, -- e.g. "A", "B", "AA"
    seat_number INT          NOT NULL, -- e.g. 1, 2, 3
    grid_x      INT          NOT NULL, -- Visual layout column index (0-based)
    grid_y      INT          NOT NULL, -- Visual layout row index (0-based)
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_seats PRIMARY KEY (id),
    CONSTRAINT fk_seats_venue_sections FOREIGN KEY (section_id) REFERENCES venue_sections(id) ON DELETE CASCADE,
    CONSTRAINT uq_seats_section_row_seat UNIQUE (section_id, row_label, seat_number),
    CONSTRAINT uq_seats_section_grid UNIQUE (section_id, grid_x, grid_y),
    CONSTRAINT chk_seats_seat_number CHECK (seat_number > 0),
    CONSTRAINT chk_seats_grid_x CHECK (grid_x >= 0),
    CONSTRAINT chk_seats_grid_y CHECK (grid_y >= 0)
);

CREATE INDEX idx_seats_section_id ON seats(section_id);
-- Partial index for active seat layout queries
CREATE INDEX idx_seats_section_active ON seats(section_id) WHERE is_active = TRUE;

-- V3__create_outbox_events_table.sql
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

CREATE INDEX idx_seatmap_outbox_unpub ON outbox_events(created_at ASC) WHERE published_at IS NULL;
CREATE INDEX idx_seatmap_outbox_aggregate ON outbox_events(aggregate_id, created_at DESC);
```

---

### 2.3 Event Service (`seatflow_event`)

```sql
-- V1__create_events_and_pricing_tables.sql
CREATE TABLE events (
    id          UUID          NOT NULL DEFAULT gen_random_uuid(),
    venue_id    UUID          NOT NULL, -- Logical reference to seat-map-service
    title       VARCHAR(255)  NOT NULL,
    description TEXT          NOT NULL,
    category    VARCHAR(100)  NOT NULL, -- e.g. "CONCERT", "THEATRE", "SPORTS", "CONFERENCE"
    banner_url  VARCHAR(1000),
    event_date  TIMESTAMPTZ   NOT NULL,
    status      VARCHAR(30)   NOT NULL DEFAULT 'DRAFT', -- DRAFT, PUBLISHED, CANCELLED, COMPLETED
    version     BIGINT        NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_events PRIMARY KEY (id),
    CONSTRAINT chk_events_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'CANCELLED', 'COMPLETED'))
);

-- Compound index for public catalog queries: upcoming published events
CREATE INDEX idx_events_status_date ON events(status, event_date ASC);
-- Partial compound index for category browsing
CREATE INDEX idx_events_category_date ON events(category, event_date ASC) WHERE status = 'PUBLISHED';
CREATE INDEX idx_events_venue_id ON events(venue_id);
CREATE INDEX idx_events_created_at ON events(created_at DESC);

CREATE TABLE event_pricing_tiers (
    id            UUID           NOT NULL DEFAULT gen_random_uuid(),
    event_id      UUID           NOT NULL,
    section_id    UUID           NOT NULL, -- Logical reference to venue_sections(id)
    category_name VARCHAR(100)   NOT NULL, -- e.g. "VIP", "Standard", "Student", "Early Bird"
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

-- V2__create_outbox_events_table.sql
CREATE TABLE outbox_events (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    aggregate_id  UUID         NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    payload       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    retry_count   INT          NOT NULL DEFAULT 0,

    CONSTRAINT pk_event_outbox PRIMARY KEY (id),
    CONSTRAINT chk_event_outbox_retry CHECK (retry_count >= 0 AND retry_count <= 5)
);

CREATE INDEX idx_event_outbox_unpub ON outbox_events(created_at ASC) WHERE published_at IS NULL;
```

---

### 2.4 Reservation Service (`seatflow_reservation`)

```sql
-- V1__create_reservations_and_seat_holds_tables.sql
CREATE TABLE reservations (
    id              UUID          NOT NULL DEFAULT gen_random_uuid(),
    user_id         UUID,         -- NULL for guest checkouts (ADR-001)
    customer_email  VARCHAR(255)  NOT NULL,
    customer_name   VARCHAR(255),
    event_id        UUID          NOT NULL,
    status          VARCHAR(30)   NOT NULL, -- PENDING, CONFIRMED, CANCELLED, EXPIRED
    expires_at      TIMESTAMPTZ   NOT NULL, -- 15-minute hold expiration timestamp
    idempotency_key VARCHAR(255)  NOT NULL,
    total_amount    NUMERIC(10,2) NOT NULL,
    seat_count      INT           NOT NULL DEFAULT 1,
    version         BIGINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_reservations PRIMARY KEY (id),
    CONSTRAINT uq_reservations_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT chk_res_status CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT chk_res_seat_count CHECK (seat_count >= 1 AND seat_count <= 10), -- Enforces Invariant #1 (Max 10 seats)
    CONSTRAINT chk_res_total_amount CHECK (total_amount >= 0.00),
    CONSTRAINT chk_res_email_format CHECK (customer_email ~* '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$')
);

-- CRITICAL: Partial index for 15-minute hold sweeper scheduler (ADR-002)
CREATE INDEX idx_res_pending_expires_at ON reservations(expires_at ASC) WHERE status = 'PENDING';
CREATE INDEX idx_res_event_status ON reservations(event_id, status);
CREATE INDEX idx_res_user_status ON reservations(user_id, status) WHERE user_id IS NOT NULL;
CREATE INDEX idx_res_customer_email ON reservations(customer_email);
CREATE INDEX idx_res_created_at ON reservations(created_at DESC);

CREATE TABLE seat_holds (
    id             UUID          NOT NULL DEFAULT gen_random_uuid(),
    reservation_id UUID          NOT NULL,
    event_id       UUID          NOT NULL,
    seat_id        UUID          NOT NULL,
    status         VARCHAR(30)   NOT NULL, -- HELD, SOLD, RELEASED
    price          NUMERIC(10,2) NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_seat_holds PRIMARY KEY (id),
    CONSTRAINT fk_seat_holds_reservations FOREIGN KEY (reservation_id) REFERENCES reservations(id) ON DELETE CASCADE,
    CONSTRAINT chk_seat_holds_status CHECK (status IN ('HELD', 'SOLD', 'RELEASED')),
    CONSTRAINT chk_seat_holds_price CHECK (price >= 0.00)
);

-- CRITICAL: Partial unique index guaranteeing Invariant #3 (Zero Double-Booking) (ADR-002)
CREATE UNIQUE INDEX uq_active_seat_hold ON seat_holds(event_id, seat_id)
    WHERE status IN ('HELD', 'SOLD');

CREATE INDEX idx_holds_reservation_id ON seat_holds(reservation_id);
CREATE INDEX idx_holds_event_seat ON seat_holds(event_id, seat_id);
CREATE INDEX idx_holds_event_status ON seat_holds(event_id, status);

-- V2__create_outbox_events_table.sql
CREATE TABLE outbox_events (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    aggregate_id  UUID         NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    payload       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    retry_count   INT          NOT NULL DEFAULT 0,

    CONSTRAINT pk_res_outbox PRIMARY KEY (id),
    CONSTRAINT chk_res_outbox_retry CHECK (retry_count >= 0 AND retry_count <= 5)
);

CREATE INDEX idx_res_outbox_unpub ON outbox_events(created_at ASC) WHERE published_at IS NULL;
```

---

### 2.5 Payment Service (`seatflow_payment`)

```sql
-- V1__create_payments_table.sql
CREATE TABLE payments (
    id                       UUID          NOT NULL DEFAULT gen_random_uuid(),
    reservation_id           UUID          NOT NULL,
    user_id                  UUID,         -- NULL for guest checkouts (ADR-001)
    customer_email           VARCHAR(255)  NOT NULL,
    event_id                 UUID          NOT NULL,
    stripe_payment_intent_id VARCHAR(255),
    idempotency_key          VARCHAR(255)  NOT NULL,
    amount                   NUMERIC(10,2) NOT NULL, -- Total gross amount charged (tax-inclusive)
    tax_amount               NUMERIC(10,2) NOT NULL DEFAULT 0.00, -- Tax/VAT computed by Stripe Tax (ADR-004)
    net_amount               NUMERIC(10,2) NOT NULL DEFAULT 0.00, -- Net merchant revenue (amount - tax_amount)
    currency                 VARCHAR(3)    NOT NULL DEFAULT 'USD',
    status                   VARCHAR(30)   NOT NULL, -- INITIATED, SUCCESS, FAILED, REFUNDED
    failure_reason           TEXT,
    version                  BIGINT        NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_payments PRIMARY KEY (id),
    CONSTRAINT uq_payments_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT uq_payments_reservation_id UNIQUE (reservation_id), -- Only 1 payment pipeline per reservation
    CONSTRAINT chk_payments_status CHECK (status IN ('INITIATED', 'SUCCESS', 'FAILED', 'REFUNDED')),
    CONSTRAINT chk_payments_amount CHECK (amount > 0.00),
    CONSTRAINT chk_payments_tax_amount CHECK (tax_amount >= 0.00),
    CONSTRAINT chk_payments_net_amount CHECK (net_amount >= 0.00),
    CONSTRAINT chk_payments_currency CHECK (length(currency) = 3),
    CONSTRAINT chk_payments_email CHECK (customer_email ~* '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$')
);

-- Partial unique index for Stripe PaymentIntent idempotency
CREATE UNIQUE INDEX uq_payments_stripe_intent ON payments(stripe_payment_intent_id)
    WHERE stripe_payment_intent_id IS NOT NULL;

CREATE INDEX idx_payments_reservation_id ON payments(reservation_id);
CREATE INDEX idx_payments_user_id ON payments(user_id) WHERE user_id IS NOT NULL;
CREATE INDEX idx_payments_customer_email ON payments(customer_email);
CREATE INDEX idx_payments_status_created ON payments(status, created_at DESC);
CREATE INDEX idx_payments_event_id ON payments(event_id);

-- V2__create_outbox_events_table.sql
CREATE TABLE outbox_events (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    aggregate_id  UUID         NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    payload       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    retry_count   INT          NOT NULL DEFAULT 0,

    CONSTRAINT pk_pay_outbox PRIMARY KEY (id),
    CONSTRAINT chk_pay_outbox_retry CHECK (retry_count >= 0 AND retry_count <= 5)
);

CREATE INDEX idx_pay_outbox_unpub ON outbox_events(created_at ASC) WHERE published_at IS NULL;
```

---

### 2.6 Ticket Service (`seatflow_ticket`)

```sql
-- V1__create_tickets_table.sql
CREATE TABLE tickets (
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    reservation_id UUID         NOT NULL,
    payment_id     UUID         NOT NULL,
    user_id        UUID,        -- NULL for guest checkouts (ADR-001)
    customer_email VARCHAR(255) NOT NULL,
    attendee_name  VARCHAR(255),
    event_id       UUID         NOT NULL,
    seat_id        UUID          NOT NULL,
    price          NUMERIC(10,2) NOT NULL, -- Total ticket price (tax-inclusive)
    tax_amount     NUMERIC(10,2) NOT NULL DEFAULT 0.00, -- Tax/VAT portion (ADR-004)
    net_amount     NUMERIC(10,2) NOT NULL DEFAULT 0.00, -- Net ticket base price
    ticket_code    VARCHAR(64)   NOT NULL, -- Cryptographically secure token (URL-safe)
    qr_code_data   TEXT          NOT NULL,
    status         VARCHAR(30)   NOT NULL DEFAULT 'VALID', -- VALID, USED, CANCELLED
    version        BIGINT        NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_tickets PRIMARY KEY (id),
    CONSTRAINT uq_tickets_ticket_code UNIQUE (ticket_code),
    CONSTRAINT uq_tickets_reservation_seat UNIQUE (reservation_id, seat_id),
    CONSTRAINT chk_tickets_status CHECK (status IN ('VALID', 'USED', 'CANCELLED')),
    CONSTRAINT chk_tickets_price CHECK (price >= 0.00),
    CONSTRAINT chk_tickets_tax_amount CHECK (tax_amount >= 0.00),
    CONSTRAINT chk_tickets_net_amount CHECK (net_amount >= 0.00),
    CONSTRAINT chk_tickets_email CHECK (customer_email ~* '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$')
);

-- Partial unique index: A physical seat can only have ONE valid ticket per event
CREATE UNIQUE INDEX uq_tickets_event_seat_valid ON tickets(event_id, seat_id)
    WHERE status = 'VALID';

CREATE INDEX idx_tickets_event_status ON tickets(event_id, status);
CREATE INDEX idx_tickets_user_id ON tickets(user_id) WHERE user_id IS NOT NULL;
CREATE INDEX idx_tickets_customer_email ON tickets(customer_email);
CREATE INDEX idx_tickets_reservation_id ON tickets(reservation_id);
CREATE INDEX idx_tickets_payment_id ON tickets(payment_id);

-- V2__create_ticket_validations_table.sql (Gate Scanner Audit Log)
CREATE TABLE ticket_validations (
    id                UUID         NOT NULL DEFAULT gen_random_uuid(),
    ticket_id         UUID         NOT NULL,
    scanner_device_id VARCHAR(100) NOT NULL,
    scan_result       VARCHAR(30)  NOT NULL, -- SUCCESS, ALREADY_USED, INVALID, CANCELLED
    details           TEXT,
    scanned_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_ticket_validations PRIMARY KEY (id),
    CONSTRAINT fk_validations_tickets FOREIGN KEY (ticket_id) REFERENCES tickets(id) ON DELETE CASCADE,
    CONSTRAINT chk_validations_result CHECK (scan_result IN ('SUCCESS', 'ALREADY_USED', 'INVALID', 'CANCELLED'))
);

CREATE INDEX idx_validations_ticket_id ON ticket_validations(ticket_id, scanned_at DESC);
CREATE INDEX idx_validations_device ON ticket_validations(scanner_device_id, scanned_at DESC);

-- V3__create_outbox_events_table.sql
CREATE TABLE outbox_events (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    aggregate_id  UUID         NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    payload       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    retry_count   INT          NOT NULL DEFAULT 0,

    CONSTRAINT pk_ticket_outbox PRIMARY KEY (id),
    CONSTRAINT chk_ticket_outbox_retry CHECK (retry_count >= 0 AND retry_count <= 5)
);

CREATE INDEX idx_ticket_outbox_unpub ON outbox_events(created_at ASC) WHERE published_at IS NULL;
```

---

### 2.7 Notification Service (`seatflow_notification`)

```sql
-- V1__create_notification_logs_table.sql
CREATE TABLE notification_logs (
    id              UUID         NOT NULL DEFAULT gen_random_uuid(),
    recipient_email VARCHAR(255) NOT NULL,
    template_type   VARCHAR(100) NOT NULL, -- e.g. "BOOKING_CONFIRMATION", "HOLD_EXPIRY_WARNING", "PAYMENT_RECEIPT", "TICKET_ISSUED"
    subject         VARCHAR(500) NOT NULL,
    idempotency_key VARCHAR(255), -- Prevents sending duplicate emails on Kafka replay
    status          VARCHAR(30)  NOT NULL, -- PENDING, SENT, FAILED
    error_message   TEXT,
    sent_at         TIMESTAMPTZ,
    retry_count     INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_notification_logs PRIMARY KEY (id),
    CONSTRAINT uq_notifications_idempotency UNIQUE (idempotency_key),
    CONSTRAINT chk_notif_status CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT chk_notif_retries CHECK (retry_count >= 0 AND retry_count <= 5),
    CONSTRAINT chk_notif_email CHECK (recipient_email ~* '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$')
);

CREATE INDEX idx_notif_recipient_created ON notification_logs(recipient_email, created_at DESC);
-- Partial index for dead-letter email retry sweeper
CREATE INDEX idx_notif_pending_retry ON notification_logs(created_at ASC)
    WHERE status = 'FAILED' AND retry_count < 3;
```

---

## 3. Query Optimization & Index Explanation Matrix

| Target Table | Index Name | Type & Definition | Target Query / Workflow Pattern |
|---|---|---|---|
| `seat_holds` | `uq_active_seat_hold` | **Partial Unique:** `(event_id, seat_id) WHERE status IN ('HELD', 'SOLD')` | Guarantees Zero Double-Booking invariant under concurrent seat reservations. |
| `reservations` | `idx_res_pending_expires_at` | **Partial B-Tree:** `(expires_at ASC) WHERE status = 'PENDING'` | Hold sweeper polling query (`WHERE status = 'PENDING' AND expires_at < now()`). |
| `reservations` | `idx_res_customer_email` | **B-Tree:** `(customer_email)` | Guest ticket claiming upon account registration (`UserRegisteredEvent`). |
| `events` | `idx_events_status_date` | **Compound B-Tree:** `(status, event_date ASC)` | Public catalog query (`WHERE status = 'PUBLISHED' AND event_date > now()`) and auto-completion sweeper (`WHERE status = 'PUBLISHED' AND event_date <= now()`) (ADR-003). |
| `events` | `idx_events_category_date` | **Partial Compound:** `(category, event_date ASC) WHERE status = 'PUBLISHED'` | Public category browsing and event filtering. |
| `payments` | `uq_payments_stripe_intent` | **Partial Unique:** `(stripe_payment_intent_id) WHERE stripe_payment_intent_id IS NOT NULL` | Stripe webhook deduplication and idempotency verification. |
| `tickets` | `uq_tickets_event_seat_valid` | **Partial Unique:** `(event_id, seat_id) WHERE status = 'VALID'` | Prevents issuing multiple active valid tickets for the same physical seat. |
| `tickets` | `idx_tickets_user_id` | **Partial B-Tree:** `(user_id) WHERE user_id IS NOT NULL` | Authenticated user "My Tickets" dashboard (`/profile/tickets`). |
| `outbox_events` | `idx_<service>_outbox_unpub` | **Partial B-Tree:** `(created_at ASC) WHERE published_at IS NULL` | Outbox publisher polling query executed every 5s across all services. |

---

## 4. Redis Data Structures & Key Patterns

Redis is strictly leveraged as a high-speed transient coordination layer. PostgreSQL
remains the authoritative store for every reservation and ticket state transition.

| Key Pattern | Data Type | TTL | Purpose |
|---|---|---|---|
| Gateway `RedisRateLimiter` token-bucket keys | Redis script-managed state | Framework-managed | Distributed API Gateway limits keyed by verified JWT subject or normalized client IP |
| `seatflow:realtime:seat-status` | Pub/Sub channel | Not persisted | Best-effort fan-out from Kafka-consuming Realtime instances to every local STOMP broadcaster |

Redis Pub/Sub messages are intentionally not replayable. A disconnected WebSocket client
must reload the current seat state from the REST API when it reconnects. Redis is not used
for reservation locks, hold expiration, durable events, or authoritative seat status.
