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
    CONSTRAINT chk_res_seat_count CHECK (seat_count >= 1 AND seat_count <= 10),
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
