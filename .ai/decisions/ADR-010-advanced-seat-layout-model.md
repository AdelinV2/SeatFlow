# ADR-010: Normalized Advanced Seat Layout with Visual Geometry Extensions

- **Date:** 2026-09-02
- **Status:** `ACCEPTED`
- **Driven by:** Phase 11 — Advanced Venue & Seat Map Designer
- **Affected tasks:** `TASK-P11-001` through `TASK-P11-012`
- **Related architecture:** `.ai/architecture/03-database-models.md` §2.2; `.ai/architecture/06-api-contracts.md` §4.3; `.ai/architecture/07-frontend-specification.md` §8; `.ai/architecture/09-post-mvp-evolution.md` §3

## 1. Context

The current Seat Map model stores sections as row/column matrices and seats using integer `grid_x/grid_y`. This is sufficient for a basic designer but too restrictive for realistic portfolio layouts containing independently positioned sections, stages, aisles, labels and rotated blocks.

Replacing the entire venue with an opaque JSON/SVG document would simplify drawing but would weaken stable seat identity, relational validation and booking correctness.

## 2. Decision

Keep sections and seats normalized relational entities and extend them with visual geometry. Introduce a separate `venue_layout_elements` table for non-bookable visual objects such as stage, aisle, label and barrier.

JSONB may store visual geometry for decorative/layout elements, but business-critical fields (`seatId`, row label, seat number, active state, section ownership) remain explicit relational columns.

Existing `grid_x/grid_y` values are backfilled into initial continuous coordinates and retained during compatibility migration.

## 3. Alternatives Considered

### Entire layout as one JSON/SVG blob
- Pros: simple editor persistence, arbitrary shapes.
- Cons: poor relational validation, harder partial updates, unstable seat identity risk, awkward concurrency/versioning.
- Rejected because booking domain identity must not depend on an opaque drawing document.

### Keep only the existing matrix model
- Pros: simplest implementation.
- Cons: cannot represent realistic section placement/rotation/aisles well enough for the planned editor.
- Rejected because it fails the Phase 11 product goal.

## 4. Consequences

Positive:
- preserves current booking contracts and stable seat IDs;
- supports richer visuals without making JSON authoritative for inventory;
- allows customer and admin renderers to share a geometry model.

Trade-offs:
- migrations and mapper/API changes are required;
- editor save/versioning becomes more complex than the current grid generator.

## 5. Implementation Notes

Use optimistic layout versioning and reject stale admin saves with `409 Conflict`. Do not implement CAD/3D/free-form SVG editing in Phase 11.

The Phase 11 contract is fixed as follows:

- Sections use venue-canvas coordinates and seats use coordinates local to their section, all with `NUMERIC(12,3)` values. Positions and dimensions are bounded to `0..100000`; rotation is bounded to `-180..180` degrees and z-index to `-1000..1000`.
- `venues.layout_version` is a separate non-negative `BIGINT` concurrency token. An editor save locks the venue row, compares the expected version, and atomically persists the complete layout snapshot. A stale version returns the existing stable `SF_409_CONFLICT` error; there is no force-save or automatic overwrite path.
- Existing `grid_x/grid_y` columns and seat UUIDs are retained. The compatibility backfill sets `position_x = grid_x * 44` and `position_y = grid_y * 44`; legacy sections are placed deterministically by `(name, id)` with an 80-unit vertical gap. Existing reservation, ticket, and payment references remain valid.
- Existing sections and seats omitted from a full editor snapshot are soft-deactivated with `is_active = false`; persisted inventory rows are never hard-deleted when referenced. Non-bookable layout elements are rectangular typed primitives (`STAGE`, `AISLE`, `LABEL`, `BARRIER`, `DECORATION`) with JSONB limited to visual geometry/metadata.
- Event-service and customer-renderer contracts remain additive and continue to identify inventory by stable seat and section UUIDs. Bézier curves, CAD/3D geometry, and free-form SVG path storage are explicitly out of scope.
