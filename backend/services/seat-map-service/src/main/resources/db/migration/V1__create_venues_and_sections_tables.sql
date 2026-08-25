-- ====================================================================
-- V1: Create venues and venue_sections tables for seat-map-service
-- Database: seatflow_seatmap
-- Spec: .ai/architecture/03-database-models.md (Section 2.2)
-- ADR: ADR-002 (DDL naming conventions, indexing standards)
-- ====================================================================

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
    name        VARCHAR(100) NOT NULL,
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
