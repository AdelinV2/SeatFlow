CREATE TABLE payments (
    id                       UUID          NOT NULL DEFAULT gen_random_uuid(),
    reservation_id           UUID          NOT NULL,
    user_id                  UUID,         -- NULL for guest checkouts (ADR-001)
    customer_email           VARCHAR(255)  NOT NULL,
    event_id                 UUID          NOT NULL,
    stripe_payment_intent_id VARCHAR(255),
    idempotency_key          VARCHAR(255)  NOT NULL,
    amount                   NUMERIC(10,2) NOT NULL,
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
    CONSTRAINT chk_payments_currency CHECK (length(currency) = 3),
    CONSTRAINT chk_payments_email CHECK (customer_email ~* '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$')
);

-- Partial unique index for Stripe PaymentIntent idempotency (ADR-002)
CREATE UNIQUE INDEX uq_payments_stripe_intent ON payments(stripe_payment_intent_id)
    WHERE stripe_payment_intent_id IS NOT NULL;

CREATE INDEX idx_payments_reservation_id ON payments(reservation_id);
CREATE INDEX idx_payments_user_id ON payments(user_id) WHERE user_id IS NOT NULL;
CREATE INDEX idx_payments_customer_email ON payments(customer_email);
CREATE INDEX idx_payments_status_created ON payments(status, created_at DESC);
CREATE INDEX idx_payments_event_id ON payments(event_id);
