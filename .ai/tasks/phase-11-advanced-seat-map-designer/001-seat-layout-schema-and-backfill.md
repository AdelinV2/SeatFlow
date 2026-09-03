# TASK-P11-001: Add Advanced Seat Layout Schema and Identity-Safe Backfill

## 1. Task Metadata

- **Task ID:** `TASK-P11-001`
- **Git Branch:** `feat/p11-001-seat-layout-schema-backfill`
- **Target Module:** `backend/services/seat-map-service`
- **Phase:** `Phase 11 - Advanced Venue & Seat Map Designer`
- **Related Specs:** `.ai/architecture/03-database-models.md` §1 and §2.2; `.ai/architecture/09-post-mvp-evolution.md` §3
- **Related ADRs:** `.ai/decisions/ADR-010-advanced-seat-layout-model.md`
- **Status:** `READY FOR IMPLEMENTATION`
- **Complexity:** 4/5
- **Failure Risk:** Critical
- **Verification Strength:** Strong
- **Affected Invariants:** normalized section/seat ownership; stable seat and section UUIDs; PostgreSQL source of truth; active-seat capacity accounting; event pricing references to section UUIDs; reservation/ticket references to seat UUIDs
- **Primary Failure Modes:** UUID regeneration during backfill; null or invalid continuous coordinates; non-deterministic section placement; active-position duplicates; cascading loss of historical seat references; JSONB becoming authoritative for bookable-seat fields
- **Required Review Depth:** Critical

## 2. Objective & Critical Invariants

Add the relational geometry columns, soft-deactivation flag, venue layout version, and non-bookable layout-element table required by Phase 11. Backfill every current seat from `grid_x/grid_y` without changing a single existing primary key.

### Critical Invariants to Enforce

- [ ] `venue_sections` and `seats` remain normalized relational tables.
- [ ] `seats.id`, `seats.section_id`, and `venue_sections.id` are never updated or regenerated.
- [ ] `grid_x` and `grid_y` remain non-null compatibility fields.
- [ ] JSONB is limited to `venue_sections.shape_metadata` and non-bookable `venue_layout_elements.geometry`.
- [ ] Bookable identity, row label, seat number, active state, and ownership remain scalar columns.
- [ ] Existing venues receive `layout_version = 0`; successful future saves increment it in a later task.
- [ ] Existing active seats remain active and count toward the same venue capacity after migration.
- [ ] Section/seat retirement can use `is_active = FALSE`; no migration deletes rows.
- [ ] No CAD, 3D, Bézier, or arbitrary SVG-path storage is introduced.

## 3. Dependencies

- Flyway migrations `V1` through `V4` must remain unchanged.
- ADR-010 is `ACCEPTED` and explicitly confirms the section-venue/seat-local coordinate system; `NUMERIC(12,3)` geometry precision; 44-unit legacy seat pitch; deterministic vertical placement of legacy sections; `layout_version` as a separate editor concurrency token; `venue_sections.is_active` soft-deactivation; rectangular typed layout-element geometry; and retention of `grid_x/grid_y`.

## 4. Exact File Inventory

- `[NEW]` `backend/services/seat-map-service/src/main/resources/db/migration/V5__add_advanced_seat_layout.sql`
- `[NEW]` `backend/services/seat-map-service/src/test/java/com/seatflow/seatmap/integration/AdvancedSeatLayoutMigrationTest.java`

No existing migration may be edited or deleted.

## 5. Contracts

### 5.1 Exact Flyway DDL and Backfill

`V5__add_advanced_seat_layout.sql` must implement the following schema contract. Equivalent statement ordering is allowed only when the resulting constraints, defaults, indexes, and backfill values are identical.

```sql
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
```

### 5.2 Coordinate and Compatibility Contract

- Section positions are venue-canvas coordinates; seat positions are local to their section.
- Legacy seat backfill is exactly `position_x = grid_x * 44` and `position_y = grid_y * 44`.
- Legacy sections are ordered by `(name, id)` within each venue, placed at `x = 0`, and vertically separated by 80 units.
- The migration must not issue `UPDATE seats SET id`, create replacement seats, truncate a table, or drop any legacy constraint/column.
- `shape_metadata` is nullable because rectangular section geometry is fully represented by scalar transform fields.

## 6. Implementation Sequence

1. Create the migration test with Flyway targeted at version 4, seed two venues, multiple sections, active/inactive seats, and fixed UUIDs.
2. Capture the seeded UUIDs and legacy coordinates before applying V5.
3. Add V5 with the exact expand, backfill, constrain, table, and index order above.
4. Migrate the test database to latest.
5. Assert schema metadata, backfill values, stable IDs, activity flags, layout version, deterministic section offsets, JSONB checks, and active-position uniqueness.
6. Verify a transaction that violates each new check/unique constraint is rejected.
7. Inspect `EXPLAIN` for lookup by `venue_id` on `venue_layout_elements` and active sections; assert the expected indexes exist rather than asserting a planner choice on a tiny test table.

## 7. Negative and Edge Cases

- Empty database and venue with no sections migrate successfully.
- One-row/one-column sections receive width/height `44.000`.
- Multiple sections with identical dimensions have deterministic non-overlapping initial Y offsets.
- Inactive legacy seats retain `is_active = FALSE` and their UUIDs.
- Two active seats at the same new continuous position are rejected; an inactive historical seat may share a position with an active seat.
- Negative, over-100000, zero-size, over-180 rotation, invalid element type, non-object JSON, and out-of-range z-index writes are rejected.

## 8. Security and Observability

- This task exposes no endpoint and changes no authorization rule.
- Migration/test logs must not print connection passwords or seeded production-like personal data.
- PostgreSQL remains the only authoritative layout store.

## 9. Tests Mapped to Risks

| Risk | Required proof |
|---|---|
| Seat identity loss | Compare the exact pre/post sets of seat UUIDs and section UUIDs. |
| Incorrect backfill | Assert exact 44-unit seat coordinates and deterministic section coordinates. |
| Capacity drift | Assert active-seat counts per venue are unchanged. |
| Invalid geometry persistence | Execute violating inserts/updates and assert constraint failures. |
| Missing access indexes | Query `pg_indexes` for all named indexes. |
| Migration not safe on empty/current data | Run both empty-schema and seeded-V4-to-V5 scenarios. |

## 10. Exact Verification Commands

From the repository root:

```bash
cd backend
mvn -pl services/seat-map-service -am -Dtest=AdvancedSeatLayoutMigrationTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl services/seat-map-service -am test
```

Expected result: both commands exit 0; the targeted test proves unchanged UUID sets and exact backfill values.

## 11. Review Focus

- Critical review of every DDL statement that can affect IDs, foreign keys, or activity.
- Confirm the partial position index does not block coexistence of inactive historical seats.
- Confirm no booking field is moved into JSONB.
- Confirm migration ordering works from V4 and from a clean database.

## 12. Acceptance Criteria

- [ ] V5 applies to empty and populated V4 schemas.
- [ ] Every existing seat and section UUID survives unchanged.
- [ ] Continuous coordinates and section transforms are non-null after backfill.
- [ ] `grid_x/grid_y` and their constraints remain present.
- [ ] Layout elements accept only the five bounded element types and object JSON.
- [ ] No row is deleted by the migration.
- [ ] Targeted and module verification commands exit 0.
- [ ] Independent critical review finds no unresolved data-integrity issue.
