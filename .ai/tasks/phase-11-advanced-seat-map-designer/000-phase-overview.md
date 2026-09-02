# Phase 11 — Advanced Venue & Seat Map Designer

**Status:** `PLANNED`  
**Architecture:** `.ai/architecture/09-post-mvp-evolution.md`  
**Related ADR:** `.ai/decisions/ADR-010-advanced-seat-layout-model.md`  
**Estimated effort:** ~16–20 focused implementation hours  

---

## 1. Outcome

Upgrade the existing admin venue grid designer into a flexible 2D layout editor capable of representing realistic theatre/concert layouts while preserving stable seat IDs and all existing booking behavior.

This phase is intentionally first because it changes how venue geometry is stored/rendered. Later phases must build on the final seat-layout contract rather than forcing another large frontend/data migration.

## 2. Current Baseline to Preserve

- Seat Map Service owns venues, sections and seats.
- Existing seats use row labels, seat numbers and `grid_x/grid_y`.
- Admin already has venue list/editor/designer routes.
- Customer seat selection already consumes seat IDs and live availability.
- Existing venue data must remain usable after migration.

## 3. Required Backend Work

### 3.1 Flyway schema evolution
Add visual transform fields to sections and continuous coordinates to seats. Introduce `venue_layout_elements` for `STAGE`, `AISLE`, `LABEL`, `BARRIER`, optional decorative shapes. Add indexes by venue/section and optimistic layout versioning.

Backfill current integer grid coordinates into continuous coordinates. Do not regenerate seat UUIDs.

### 3.2 Domain/API model
Create explicit request/response records for editable layouts. Avoid one giant unvalidated map payload. Validate:

- section bounds/size;
- duplicate row/seat labels within a section;
- duplicate seat positions where collision rules apply;
- supported layout element types;
- active seat counts and venue capacity consistency;
- stale layout version.

### 3.3 Save semantics
Implement an atomic editor save boundary. A failed validation must not partially update a venue. Optimistic version mismatch returns `409` with a stable error code.

Bulk seat operations should support generation, rename/renumber, move and deactivate. Prefer deactivation over destructive deletion when a seat has historical ticket/reservation references.

## 4. Required Frontend Work

Create/refactor editor primitives around one shared geometry model:

- zoom/pan canvas;
- draggable/resizable/rotatable section blocks;
- section creation/deletion/duplication;
- stage/aisle/label placement;
- bulk row/seat generator;
- multi-selection and bulk activation/deactivation;
- row label and seat-number controls;
- snap-to-grid toggle;
- local undo/redo stack for unsaved changes;
- keyboard delete/escape and accessible alternatives to pointer-only actions;
- dirty-state route guard;
- save conflict UI;
- preview mode using customer seat-rendering components or the same geometry primitives.

Responsive admin use should remain possible, but the full designer may explicitly recommend desktop/tablet landscape for complex editing.

## 5. Compatibility / Migration Requirements

- Existing simple grid venues render identically or near-identically after backfill.
- Event/Reservation/Ticket services do not need seat ID changes.
- Existing event pricing references to section IDs remain valid.
- Customer selection must not know whether a layout originated from the legacy grid generator or advanced editor.

## 6. Suggested Atomic Tasks

Create implementation tasks later using the standard template:

1. `001-seat-layout-schema-and-backfill.md`
2. `002-layout-domain-contracts-and-validation.md`
3. `003-versioned-layout-save-and-bulk-seat-api.md`
4. `004-designer-canvas-transform-engine.md`
5. `005-section-seat-generation-and-bulk-editing.md`
6. `006-layout-elements-stage-aisles-labels.md`
7. `007-undo-redo-preview-and-save-conflicts.md`
8. `008-legacy-layout-compatibility-and-phase-tests.md`

## 7. Testing Required in This Phase

Focused tests are still mandatory even though Phase 17 is the final broad QA phase:

- Flyway migration/backfill test;
- stale layout version conflict;
- invalid/duplicate seat generation;
- stable seat IDs after editing;
- editor serialization round-trip;
- legacy layout rendering smoke test.

## 8. Definition of Done

- [ ] Existing venues migrate without losing seat identity.
- [ ] Admin can construct non-trivial multi-section layouts with stage/aisles.
- [ ] Layout save is atomic and version-safe.
- [ ] Customer seat map renders the same saved geometry.
- [ ] Pricing/reservations/tickets continue referencing the same section/seat IDs.
- [ ] No general-purpose CAD/3D scope has been introduced.
- [ ] Architecture/ADR decisions are reflected in code and tests.
