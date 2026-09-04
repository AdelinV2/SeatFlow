# TASK-P11-006: Build Shared Pan-Zoom Canvas and Section Transform Engine

## 1. Task Metadata

- **Task ID:** `TASK-P11-006`
- **Git Branch:** `feat/p11-006-designer-canvas-transforms`
- **Target Module:** `frontend/src/app/shared/components/seat-layout`, `frontend/src/app/shared/utils`
- **Phase:** `Phase 11 - Advanced Venue & Seat Map Designer`
- **Related Specs:** `.ai/architecture/07-frontend-specification.md` §1 and §4.5; `.ai/architecture/09-post-mvp-evolution.md` §3.4
- **Related ADRs:** `.ai/decisions/ADR-010-advanced-seat-layout-model.md`, `.ai/decisions/ADR-015-unified-seat-map-and-tier-color-rendering.md`
- **Status:** `READY FOR IMPLEMENTATION`
- **Complexity:** 4/5
- **Failure Risk:** Medium
- **Verification Strength:** Strong
- **Affected Invariants:** shared geometry interpretation; bounded transforms; accessible rendering foundation; no CAD/3D scope
- **Primary Failure Modes:** pointer-coordinate drift under zoom; stored coordinates polluted by viewport pan/zoom; resize below bounds; rotation/snap inconsistency; unstable rendering order; inaccessible transform controls
- **Required Review Depth:** Substantive

## 2. Objective & Critical Invariants

Create reusable SVG canvas and section-node primitives that render the shared geometry model and emit bounded transform changes. Viewport state remains local and never enters persistence.

- [ ] Section/seat world coordinates are independent of viewport pan/zoom.
- [ ] Seat positions are interpreted in section-local coordinates.
- [ ] Pointer deltas are converted through the current SVG transform before editing geometry.
- [ ] Snap-to-grid affects new transform values only and is not a storage requirement.
- [ ] All emitted transforms satisfy TASK-P11-003 bounds.
- [ ] Renderer accepts only rectangle/rotation transforms; no path editor, Bézier, CAD, or 3D behavior.

## 3. Dependencies

- TASK-P11-005 typed frontend geometry.
- Existing customer `SeatMapComponent` behavior is reference material; it is not modified in this task.
- Accepted ADR-010 coordinate contract.

## 4. Exact File Inventory

- `[NEW]` `frontend/src/app/shared/components/seat-layout/layout-canvas/layout-canvas.component.ts`
- `[NEW]` `frontend/src/app/shared/components/seat-layout/layout-canvas/layout-canvas.component.html`
- `[NEW]` `frontend/src/app/shared/components/seat-layout/layout-canvas/layout-canvas.component.scss`
- `[NEW]` `frontend/src/app/shared/components/seat-layout/layout-canvas/layout-canvas.component.spec.ts`
- `[NEW]` `frontend/src/app/shared/components/seat-layout/section-node/section-node.component.ts`
- `[NEW]` `frontend/src/app/shared/components/seat-layout/section-node/section-node.component.html`
- `[NEW]` `frontend/src/app/shared/components/seat-layout/section-node/section-node.component.scss`
- `[NEW]` `frontend/src/app/shared/components/seat-layout/section-node/section-node.component.spec.ts`
- `[NEW]` `frontend/src/app/shared/utils/layout-geometry.ts`
- `[NEW]` `frontend/src/app/shared/utils/layout-geometry.spec.ts`

## 5. Component and Geometry Contracts

### 5.1 Layout Canvas

Inputs: `sections`, `elements`, `selectedSectionIds`, `editable`, and `snapStep` (`0` disables snapping; otherwise positive). Outputs: `sectionTransformChanged`, `selectionChanged`. Signals: zoom `0.25..4`, pan X/Y, dragging, and fit-to-layout. Rendering order is `(zIndex, stable id-or-array-index)`.

Pointer behavior:

- Background primary click clears selection; section click selects one; Ctrl/Meta-click toggles membership.
- Background drag pans; wheel/pinch zoom anchors at cursor/midpoint.
- Section drag emits position; four corner handles emit width/height while keeping width/height `>0`; rotation handle emits degrees normalized to `[-180,180]`.
- All handlers use pointer capture and clear interaction state on `pointerup`, `pointercancel`, and lost capture.
- `editable=false` emits no mutation event; in read-only mode, `LayoutCanvasComponent` acts as the shared unified venue canvas for both Admin Preview and Customer Seat Selection (`SeatMapComponent`), displaying all active sections, layout elements (`STAGE`, `AISLE`), and seats concurrently without section isolation or tabs (per ADR-015).

### 5.2 Section Node

Apply `translate(positionX positionY) rotate(rotationDeg width/2 height/2)` once, then render seats at local `positionX/positionY`. Render inactive sections/seats with explicit classes but keep them in editor DOM. Expose focusable selection and transform handles with labels; TASK-P11-009 adds keyboard mutations.

### 5.3 Pure Geometry Functions

`clientPointToWorld`, `clampSectionTransform`, `snap`, `normalizeRotation`, `layoutBounds`, and `sortedLayoutItems` must be pure and directly unit-tested. Clamp ranges exactly match TASK-P11-003.

## 6. Implementation Sequence

1. Write pure coordinate/transform tests including non-1 zoom and pan.
2. Implement geometry functions.
3. Build section-node rendering with local seats and transform handles.
4. Build canvas pan/zoom, selection, pointer capture, and fit-to-layout.
5. Add snap toggle input behavior without rounding loaded geometry until a user edit occurs.
6. Add component tests for emitted values, cancellation, rendering order, and read-only mode.

## 7. Negative and Edge Cases

- Zero/NaN zoom input falls back to 1; user zoom clamps at 0.25 and 4.
- Pointer cancel emits no extra transform after cancellation.
- Rotation crossing 180 normalizes continuously to the contract range.
- Snap step 0 preserves decimal values; snap 10 rounds changed coordinates to nearest 10.
- Empty layout fit produces a safe default viewBox.
- Inactive section remains visible in editor mode but cannot be selected in read-only customer mode.
- Duplicate z-index values have deterministic stable ordering.

## 8. Security and Accessibility

- Components make no HTTP requests and contain no authorization logic.
- SVG groups/handles have roles, names, focus indication, and minimum 44px HTML toolbar targets where controls exist.
- Pointer gestures are not the only eventual editing path; TASK-P11-009 must add keyboard/form alternatives before phase completion.

## 9. Tests Mapped to Risks

| Risk | Required proof |
|---|---|
| Zoom coordinate drift | Same world delta computed at zoom 0.5, 1, and 2. |
| Persisting viewport state | Transform output excludes zoom/pan. |
| Out-of-bounds transform | Clamp tests cover every inclusive boundary. |
| Pointer leak | Cancel/lost capture clears all active pointers. |
| Rendering instability | Equal-z items keep stable tie-break order. |

## 10. Exact Verification Commands

```bash
cd frontend
npm exec prettier -- --check src/app/shared/components/seat-layout src/app/shared/utils/layout-geometry.ts src/app/shared/utils/layout-geometry.spec.ts
npm exec tsc -- -p tsconfig.app.json --noEmit
npm test -- --watch=false --browsers=ChromeHeadless --include=src/app/shared/components/seat-layout/layout-canvas/layout-canvas.component.spec.ts --include=src/app/shared/components/seat-layout/section-node/section-node.component.spec.ts --include=src/app/shared/utils/layout-geometry.spec.ts
npm run build
```

Expected result: all commands exit 0; coordinate tests prove zoom-independent world transforms.

## 11. Review Focus

- Matrix math, pointer lifecycle, and separation of viewport versus persisted state.
- Transform bounds and snap semantics.
- Standalone/OnPush/Signal compliance.
- No unsupported vector-editing scope.

## 12. Acceptance Criteria

- [ ] Sections render at continuous transformed positions with local seat coordinates.
- [ ] Pan, wheel/pinch zoom, drag, resize, rotate, fit, select, and snap contracts pass.
- [ ] Read-only mode emits no edits.
- [ ] Pointer cancellation cannot leave a stuck drag.
- [ ] Formatting, static check, targeted tests, and production build exit 0.
