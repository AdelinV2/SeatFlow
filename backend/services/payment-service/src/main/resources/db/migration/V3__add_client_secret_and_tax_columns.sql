-- Payment Service: add Stripe client secret and fiscal breakdown columns (ADR-004).
-- client_secret is required to support idempotent replay of POST /api/payments/intent
-- (the previously stored PaymentIntent is reused, so its client secret must be returned).
-- tax_amount / net_amount persist the Stripe Tax breakdown for fiscal transparency and
-- downstream ticket / receipt rendering.

ALTER TABLE payments ADD COLUMN client_secret VARCHAR(512);

ALTER TABLE payments ADD COLUMN tax_amount NUMERIC(10,2) NOT NULL DEFAULT 0.00;
ALTER TABLE payments ADD COLUMN net_amount NUMERIC(10,2) NOT NULL DEFAULT 0.00;

-- Indexes to support reconciliation and reporting queries.
CREATE INDEX idx_payments_tax_amount ON payments(tax_amount);
