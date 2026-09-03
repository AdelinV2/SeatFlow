-- ====================================================================
-- V5: Add advanced seat layout geometry and backfill
-- Spec: .ai/tasks/phase-11-advanced-seat-map-designer/001-seat-layout-schema-and-backfill.md §5.1
-- ADR: ADR-010 (venue-canvas vs seat-local coords, 44-unit pitch, 80-unit gap)
-- Invariants: seats.id/section_id/venue_sections.id never updated; grid_x/grid_y retained NOT NULL
-- ====================================================================

ALTER TABLE venues
    ADD COLUMN layout_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_venues_layout_version CHECK (layout_version >= 0);

ALTER TABLE venue_sections
    ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN position_x NUMERIC(12,3),
    ADD COLUMN position_y NUMERIC(12,3),
    ADD COLUMN width NUMERIC(12,3),
    ADD COLUMN height NUMERIC(12,3),
    ADD COLUMN rotation_deg NUMERIC(7,3) NOT NULL DEFAULT 0,
    ADD COLUMN z_index INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN shape_metadata JSONB;

WITH legacy_section_geometry AS (
    SELECT id,
           0::NUMERIC(12,3) AS position_x,
           COALESCE(
               SUM((row_count * 44) + 80) OVER (
                   PARTITION BY venue_id
                   ORDER BY name, id
                   ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
               ),
               0
           )::NUMERIC(12,3) AS position_y,
           (GREATEST(col_count, 1) * 44)::NUMERIC(12,3) AS width,
           (GREATEST(row_count, 1) * 44)::NUMERIC(12,3) AS height
    FROM venue_sections
)
UPDATE venue_sections target
SET position_x = source.position_x,
    position_y = source.position_y,
    width = source.width,
    height = source.height
FROM legacy_section_geometry source
WHERE target.id = source.id;

ALTER TABLE venue_sections
    ALTER COLUMN position_x SET NOT NULL,
    ALTER COLUMN position_y SET NOT NULL,
    ALTER COLUMN width SET NOT NULL,
    ALTER COLUMN height SET NOT NULL,
    ADD CONSTRAINT chk_venue_sections_position_x CHECK (position_x BETWEEN 0 AND 100000),
    ADD CONSTRAINT chk_venue_sections_position_y CHECK (position_y BETWEEN 0 AND 100000),
    ADD CONSTRAINT chk_venue_sections_width CHECK (width > 0 AND width <= 100000),
    ADD CONSTRAINT chk_venue_sections_height CHECK (height > 0 AND height <= 100000),
    ADD CONSTRAINT chk_venue_sections_rotation CHECK (rotation_deg BETWEEN -180 AND 180),
    ADD CONSTRAINT chk_venue_sections_z_index CHECK (z_index BETWEEN -1000 AND 1000),
    ADD CONSTRAINT chk_venue_sections_shape_metadata CHECK (
        shape_metadata IS NULL OR jsonb_typeof(shape_metadata) = 'object'
    );

ALTER TABLE seats
    ADD COLUMN position_x NUMERIC(12,3),
    ADD COLUMN position_y NUMERIC(12,3),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

UPDATE seats
SET position_x = (grid_x * 44)::NUMERIC(12,3),
    position_y = (grid_y * 44)::NUMERIC(12,3);

ALTER TABLE seats
    ALTER COLUMN position_x SET NOT NULL,
    ALTER COLUMN position_y SET NOT NULL,
    ADD CONSTRAINT chk_seats_position_x CHECK (position_x BETWEEN 0 AND 100000),
    ADD CONSTRAINT chk_seats_position_y CHECK (position_y BETWEEN 0 AND 100000);

CREATE UNIQUE INDEX uq_seats_section_position_active
    ON seats(section_id, position_x, position_y)
    WHERE is_active = TRUE;

CREATE TABLE venue_layout_elements (
    id          UUID           NOT NULL DEFAULT gen_random_uuid(),
    venue_id    UUID           NOT NULL,
    type        VARCHAR(30)    NOT NULL,
    label       VARCHAR(255),
    geometry    JSONB          NOT NULL,
    z_index     INTEGER        NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT pk_venue_layout_elements PRIMARY KEY (id),
    CONSTRAINT fk_venue_layout_elements_venues
        FOREIGN KEY (venue_id) REFERENCES venues(id) ON DELETE CASCADE,
    CONSTRAINT chk_venue_layout_elements_type
        CHECK (type IN ('STAGE', 'AISLE', 'LABEL', 'BARRIER', 'DECORATION')),
    CONSTRAINT chk_venue_layout_elements_geometry
        CHECK (jsonb_typeof(geometry) = 'object'),
    CONSTRAINT chk_venue_layout_elements_z_index
        CHECK (z_index BETWEEN -1000 AND 1000)
);

CREATE INDEX idx_venue_layout_elements_venue_id
    ON venue_layout_elements(venue_id);
CREATE INDEX idx_venue_layout_elements_venue_z
    ON venue_layout_elements(venue_id, z_index, id);
CREATE INDEX idx_venue_sections_venue_active
    ON venue_sections(venue_id, is_active);
