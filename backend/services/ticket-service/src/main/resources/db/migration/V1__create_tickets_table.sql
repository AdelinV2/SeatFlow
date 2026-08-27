CREATE TABLE tickets (
    id             UUID          NOT NULL DEFAULT gen_random_uuid(),
    reservation_id UUID          NOT NULL,
    payment_id     UUID          NOT NULL,
    user_id        UUID,         -- NULL for guest checkouts (ADR-001)
    customer_email VARCHAR(255)  NOT NULL,
    attendee_name  VARCHAR(255),
    event_id       UUID          NOT NULL,
    seat_id        UUID          NOT NULL,
    price          NUMERIC(10,2) NOT NULL, -- Total gross ticket price (tax-inclusive)
    tax_amount     NUMERIC(10,2) NOT NULL DEFAULT 0.00, -- Tax/VAT portion (ADR-004)
    net_amount     NUMERIC(10,2) NOT NULL DEFAULT 0.00, -- Net base price (ADR-004)
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

-- Partial unique index: A physical seat can only have ONE valid ticket per event (ADR-002)
CREATE UNIQUE INDEX uq_tickets_event_seat_valid ON tickets(event_id, seat_id)
    WHERE status = 'VALID';

CREATE INDEX idx_tickets_event_status ON tickets(event_id, status);
CREATE INDEX idx_tickets_user_id ON tickets(user_id) WHERE user_id IS NOT NULL;
CREATE INDEX idx_tickets_customer_email ON tickets(customer_email);
CREATE INDEX idx_tickets_reservation_id ON tickets(reservation_id);
CREATE INDEX idx_tickets_payment_id ON tickets(payment_id);
