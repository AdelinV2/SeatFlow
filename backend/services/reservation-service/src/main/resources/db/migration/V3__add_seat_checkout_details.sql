ALTER TABLE seat_holds
    ADD COLUMN row_label VARCHAR(20),
    ADD COLUMN seat_number INTEGER,
    ADD COLUMN pricing_tier_id UUID,
    ADD COLUMN ticket_type VARCHAR(100);

ALTER TABLE seat_holds
    ADD CONSTRAINT chk_seat_holds_seat_number
        CHECK (seat_number IS NULL OR seat_number > 0);
