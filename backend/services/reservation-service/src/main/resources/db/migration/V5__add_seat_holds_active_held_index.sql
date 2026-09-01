-- Fast partial index for Prometheus active holds gauge countByStatus(HELD)
CREATE INDEX idx_seat_holds_held_status ON seat_holds(status) WHERE status = 'HELD';
