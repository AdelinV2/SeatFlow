-- ====================================================================
-- V2: Create seats table for seat-map-service
-- Database: seatflow_seatmap
-- Spec: .ai/architecture/03-database-models.md (Section 2.2)
-- ADR: ADR-002 (grid coordinate constraints, spatial collision prevention)
-- ====================================================================

CREATE TABLE seats (
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    section_id  UUID         NOT NULL,
    row_label   VARCHAR(10)  NOT NULL,
    seat_number INT          NOT NULL,
    grid_x      INT          NOT NULL,
    grid_y      INT          NOT NULL,
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
