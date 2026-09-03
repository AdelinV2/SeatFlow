# TASK-P11-008: Add Bounded Stage, Aisle, Label, Barrier, and Decoration Elements

## 1. Task Metadata

- **Task ID:** `TASK-P11-008`
- **Git Branch:** `feat/p11-008-layout-elements`
- **Target Module:** `frontend/src/app/shared/components/seat-layout`
- **Phase:** `Phase 11 - Advanced Venue & Seat Map Designer`
- **Related Specs:** `.ai/architecture/09-post-mvp-evolution.md` §3.2 and §3.4; `.ai/architecture/07-frontend-specification.md` §4.5
- **Related ADRs:** `.ai/decisions/ADR-010-advanced-seat-layout-model.md`
- **Status:** `READY FOR IMPLEMENTATION`
- **Complexity:** 3/5
- **Failure Risk:** Medium
- **Verification Strength:** Strong
- **Affected Invariants:** non-bookable element isolation; typed rectangular geometry; bounded transforms; stable element IDs; rendering order
- **Primary Failure Modes:** element treated as a seat; arbitrary SVG/path data; lost element IDs; invalid label; geometry beyond bounds; element controls unavailable without pointer
- **Required Review Depth:** Substantive

## 2. Objective & Critical Invariants

Add typed non-bookable layout elements to the editor using the same bounded transform engine.

- [ ] Elements never appear in seat selection, capacity, pricing, reservation, or ticket logic.
- [ ] Existing element IDs remain unchanged; copied/new elements use null IDs.
- [ ] Geometry is limited to x/y/width/height/rotation and z-index.
- [ ] `LABEL` requires visible non-blank text; no raw HTML is rendered.
- [ ] No path commands, Bézier control points, CAD import, or 3D fields are accepted.

## 3. Dependencies

- TASK-P11-005 element types, TASK-P11-006 transforms, and TASK-P11-007 designer shell.
- Backend element types and bounds from TASK-P11-003.
- Accepted ADR-010 rectangular element geometry contract.

## 4. Exact File Inventory

- `[NEW]` `frontend/src/app/shared/components/seat-layout/layout-element-palette/layout-element-palette.component.ts`
- `[NEW]` `frontend/src/app/shared/components/seat-layout/layout-element-palette/layout-element-palette.component.html`
- `[NEW]` `frontend/src/app/shared/components/seat-layout/layout-element-palette/layout-element-palette.component.scss`
- `[NEW]` `frontend/src/app/shared/components/seat-layout/layout-element-palette/layout-element-palette.component.spec.ts`
- `[NEW]` `frontend/src/app/shared/components/seat-layout/layout-element-node/layout-element-node.component.ts`
- `[NEW]` `frontend/src/app/shared/components/seat-layout/layout-element-node/layout-element-node.component.html`
- `[NEW]` `frontend/src/app/shared/components/seat-layout/layout-element-node/layout-element-node.component.scss`
- `[NEW]` `frontend/src/app/shared/components/seat-layout/layout-element-node/layout-element-node.component.spec.ts`
- `[MODIFY]` `frontend/src/app/shared/components/seat-layout/layout-canvas/layout-canvas.component.ts`
- `[MODIFY]` `frontend/src/app/shared/components/seat-layout/layout-canvas/layout-canvas.component.html`

## 5. Element Contracts

Palette creates exact defaults, all with `elementId:null` and the next available z-index:

| Type | Default label | Default geometry |
|---|---|---|
| `STAGE` | `Stage` | `x=100,y=40,width=400,height=80,rotationDeg=0` |
| `AISLE` | null | `x=100,y=160,width=300,height=40,rotationDeg=0` |
| `LABEL` | `Label` | `x=100,y=240,width=200,height=44,rotationDeg=0` |
| `BARRIER` | null | `x=100,y=320,width=300,height=20,rotationDeg=0` |
| `DECORATION` | null | `x=100,y=380,width=100,height=100,rotationDeg=0` |

Creation validates/clamps against x/y `0..100000`, positive width/height `<=100000`, rotation `-180..180`, and z-index `-1000..1000`. Duplicate copies geometry with a 20-unit clamped offset and a null ID. Removing an element deletes it only from the unsaved draft; backend replacement on save performs persistent deletion.

Rendering uses fixed sanitized SVG primitives: rounded rectangle for stage/aisle/label/decoration and thin rectangle for barrier. Text binds through Angular interpolation only. Type controls are a closed union; users cannot provide markup or SVG `d` strings.

## 6. Implementation Sequence

1. Build palette buttons with fixed defaults and typed create output.
2. Build element-node read/edit rendering and reuse pure transform helpers.
3. Extend canvas stable z-order merge for sections and elements.
4. Add selection, move, resize, rotate, duplicate, label edit, z-order, and draft removal.
5. Add keyboard-focusable controls and numeric form alternatives.
6. Add tests for every type, ID rule, bound, rendering primitive, and capacity isolation.

## 7. Negative and Edge Cases

- Blank/whitespace `LABEL` fails before state mutation.
- Text containing `<script>` renders as text, never markup.
- Unknown runtime type produces no editable node and a validation message; it is not coerced.
- Duplicate at maximum canvas coordinate remains within bounds.
- Element count does not change active-seat count.
- Existing element move/resize retains its ID; new/duplicate remains null-ID until save.
- Equal z-index uses stable ID-or-array-index tie-break.

## 8. Security and Accessibility

- Do not use `[innerHTML]`, dynamic SVG paths, data URLs, or style strings sourced from labels.
- Palette and element controls expose names, focus rings, keyboard activation, and 44px targets.
- Server-side ADMIN enforcement remains in TASK-P11-004.

## 9. Tests Mapped to Risks

| Risk | Required proof |
|---|---|
| Booking contamination | Element operations leave seat count/selection arrays unchanged. |
| Injection/path scope | Hostile label is escaped and DOM contains no injected node/path data. |
| ID loss | Existing move retains ID; duplicate has null ID. |
| Bounds | Move/resize/rotate/z operations clamp or reject exactly. |
| Unsupported type | Closed-union guard rejects it without draft mutation. |

## 10. Exact Verification Commands

```bash
cd frontend
npm exec prettier -- --check src/app/shared/components/seat-layout/layout-element-palette src/app/shared/components/seat-layout/layout-element-node src/app/shared/components/seat-layout/layout-canvas
npm exec tsc -- -p tsconfig.app.json --noEmit
npm test -- --watch=false --browsers=ChromeHeadless --include=src/app/shared/components/seat-layout/layout-element-palette/layout-element-palette.component.spec.ts --include=src/app/shared/components/seat-layout/layout-element-node/layout-element-node.component.spec.ts --include=src/app/shared/components/seat-layout/layout-canvas/layout-canvas.component.spec.ts
npm run build
```

Expected result: all commands exit 0; injection and capacity-isolation tests pass.

## 11. Review Focus

- Closed element type/geometry model and absence of arbitrary SVG/HTML.
- Stable/null element ID semantics.
- Seat/capacity isolation.
- Transform bounds, z-order, and accessible alternatives.

## 12. Acceptance Criteria

- [ ] All five accepted element types can be created and edited.
- [ ] Labels are escaped and `LABEL` cannot be blank.
- [ ] Elements never affect seat identity, capacity, price, or selection.
- [ ] Existing IDs survive edits; new/copies remain null-ID before save.
- [ ] No CAD/3D/Bézier/SVG-path feature exists.
- [ ] Formatting, static check, targeted tests, and production build exit 0.
