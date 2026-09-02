# ADR-010: Normalized Advanced Seat Layout with Visual Geometry Extensions

- **Date:** 2026-09-02
- **Status:** `PROPOSED`
- **Driven by:** Phase 11 — Advanced Venue & Seat Map Designer

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
