CREATE TABLE ticket_validations (
    id                UUID         NOT NULL DEFAULT gen_random_uuid(),
    ticket_id         UUID,        -- NULL for invalid/unrecognized ticket scans to prevent FK violation
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
