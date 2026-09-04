# TASK-P11-007: Add Section Generation and Stable-ID Bulk Seat Editing

## 1. Task Metadata

- **Task ID:** `TASK-P11-007`
- **Git Branch:** `feat/p11-007-section-seat-bulk-editing`
- **Target Module:** `frontend/src/app/features/admin/venues`, `frontend/src/app/services`
- **Phase:** `Phase 11 - Advanced Venue & Seat Map Designer`
- **Related Specs:** `.ai/architecture/07-frontend-specification.md` §3; `.ai/architecture/09-post-mvp-evolution.md` §3.4
- **Related ADRs:** `.ai/decisions/ADR-010-advanced-seat-layout-model.md`
- **Status:** `READY FOR IMPLEMENTATION`
- **Complexity:** 4/5
- **Failure Risk:** High
- **Verification Strength:** Strong
- **Affected Invariants:** stable seat/section IDs; row/seat/grid uniqueness; active capacity; bounded local positions; soft deactivation
- **Primary Failure Modes:** regenerating loaded UUIDs; duplicate labels/numbers/positions; capacity overflow; per-seat network fan-out; destructive section deletion; incorrect spreadsheet-style row labels
- **Required Review Depth:** Substantive

## 2. Objective & Critical Invariants

Replace the current immediate-mutation grid workflow with local section creation/duplication, deterministic bulk generation, multi-selection, rename/renumber/move, and activation/deactivation inside the versioned draft.

- [ ] Editing any loaded seat preserves its `seatId`; duplicating/generating creates null-ID records only.
- [ ] Deleting an existing section means `isActive=false` and deactivates its seats.
- [ ] No bulk action calls the legacy per-seat PATCH endpoint.
- [ ] Generated grid and continuous coordinates are both populated.
- [ ] The active-seat count may equal but never exceed venue capacity.
- [ ] Duplicates and bounds are detected before save.

## 3. Dependencies

- TASK-P11-005 editor state and TASK-P11-006 shared canvas.
- Backend validation remains authoritative; frontend validation mirrors it for immediate feedback.
- Existing `getRowLabel` behavior (`A..Z, AA..`) must be retained through a shared generator service.

## 4. Exact File Inventory

- `[MODIFY]` `frontend/src/app/features/admin/venues/venue-grid-designer/venue-grid-designer.component.ts`
- `[MODIFY]` `frontend/src/app/features/admin/venues/venue-grid-designer/venue-grid-designer.component.html`
- `[MODIFY]` `frontend/src/app/features/admin/venues/venue-grid-designer/venue-grid-designer.component.scss`
- `[MODIFY]` `frontend/src/app/features/admin/venues/venue-grid-designer/venue-grid-designer.component.spec.ts`
- `[NEW]` `frontend/src/app/features/admin/venues/section-properties-panel/section-properties-panel.component.ts`
- `[NEW]` `frontend/src/app/features/admin/venues/section-properties-panel/section-properties-panel.component.html`
- `[NEW]` `frontend/src/app/features/admin/venues/section-properties-panel/section-properties-panel.component.scss`
- `[NEW]` `frontend/src/app/features/admin/venues/section-properties-panel/section-properties-panel.component.spec.ts`
- `[NEW]` `frontend/src/app/services/seat-layout-generator.service.ts`
- `[NEW]` `frontend/src/app/services/seat-layout-generator.service.spec.ts`

## 5. Editing Contracts

### 5.1 Generator Input/Output

Input: rows `1..50`, columns `1..50`, row-label start as non-negative alphabetic index, seat-number start `>=1`, horizontal/vertical pitch `1..1000`, origin X/Y within section bounds, and target activity. Output seats use `seatId:null`, unique row labels/numbers/grid coordinates, and `positionX=originX+gridX*pitchX`, `positionY=originY+gridY*pitchY`.

Reject before draft mutation when: dimensions fall outside range; any generated point exceeds section width/height; row label exceeds 10 characters; seat number exceeds Java/backend integer range; duplicates collide with retained seats; or projected active count exceeds venue capacity.

### 5.2 Section Operations

- Create: null `sectionId`, unique default/name, active, bounded default geometry, empty or generated seats.
- Duplicate: null section/seat IDs, copied visual values offset by 40 units and clamped, name suffixed `Copy` with numeric disambiguation.
- Deactivate: existing IDs retained; section and all seats inactive.
- Remove: permitted only for a never-saved null-ID section; otherwise use deactivate.
- Reactivate: allowed only when capacity and duplicate rules remain valid.

### 5.3 Seat Operations

Multi-select by click modifier and accessible list checkboxes. Bulk actions apply one immutable draft update: activate/deactivate, translate by numeric delta, set row label, or renumber from a positive start in deterministic `(gridY,gridX,seatId-or-index)` order. Validation failure leaves the draft byte-for-byte unchanged and reports the exact rule.

## 6. Implementation Sequence

1. Extract and test row-label/generation logic in `SeatLayoutGeneratorService`.
2. Refactor designer load to use `VenueLayoutEditorStateService` and render `LayoutCanvasComponent`.
3. Add section create/duplicate/deactivate/remove/reactivate operations.
4. Add seat selection and bulk actions through one state update per command.
5. Add properties panel numeric/text controls and inline validation messages.
6. Remove immediate row/column loops that call `toggleSeat`; keep the legacy API method only for unrelated callers until phase cleanup.
7. Add risk-mapped component/service tests.

## 7. Negative and Edge Cases

- 50x50 generation succeeds when within capacity/bounds; 51 rows or columns fails.
- `Z -> AA`, `ZZ -> AAA` remains correct.
- Duplicate case-insensitive row/seat identity and normalized continuous positions fail.
- Capacity exact fit succeeds; one active seat over fails without partial generation.
- Bulk translation that moves one selected seat out of bounds rejects the entire bulk command.
- Duplicate section near canvas maximum clamps offset without altering source.
- Deactivate/reactivate keeps loaded IDs unchanged.
- Rapid repeated command clicks cannot issue HTTP requests before explicit save.

## 8. Security and Accessibility

- Route remains protected by `adminGuard`, but this does not replace TASK-P11-004 server authorization.
- Every pointer action has a properties-panel/button/checkbox alternative with a programmatic label and 44px target.
- Validation messages are associated with controls and announced with `aria-live`.

## 9. Tests Mapped to Risks

| Risk | Required proof |
|---|---|
| UUID regeneration | Edit/deactivate loaded seats and assert IDs unchanged. |
| Invalid generation | Boundary, duplicate, out-of-bounds, and capacity tests leave draft unchanged. |
| Network fan-out | Bulk action spec asserts no API mutation until save. |
| Algorithm drift | Row-label tests include Z/AA/ZZ/AAA. |
| Destructive removal | Existing section becomes inactive; only null-ID section is removed. |

## 10. Exact Verification Commands

```bash
cd frontend
npm exec prettier -- --check src/app/features/admin/venues/venue-grid-designer src/app/features/admin/venues/section-properties-panel src/app/services/seat-layout-generator.service.ts src/app/services/seat-layout-generator.service.spec.ts
npm exec tsc -- -p tsconfig.app.json --noEmit
npm test -- --watch=false --browsers=ChromeHeadless --include=src/app/features/admin/venues/venue-grid-designer/venue-grid-designer.component.spec.ts --include=src/app/features/admin/venues/section-properties-panel/section-properties-panel.component.spec.ts --include=src/app/services/seat-layout-generator.service.spec.ts
npm run build
```

Expected result: all commands exit 0; stable-ID and all-or-nothing generation assertions pass.

## 11. Review Focus

- Null-versus-existing ID semantics and deactivation path.
- Duplicate/capacity/bounds checks before state replacement.
- Absence of per-seat HTTP loops.
- Accessible alternatives and Signal/OnPush compliance.

## 12. Acceptance Criteria

- [ ] Admin can create, duplicate, deactivate/reactivate, and remove eligible draft sections.
- [ ] Bulk generation/editing is deterministic and atomic in local state.
- [ ] Existing IDs never change; generated IDs remain null before save.
- [ ] Invalid operations leave the draft unchanged with a specific message.
- [ ] No unsupported CAD/3D/path functionality appears.
- [ ] Formatting, static check, targeted tests, and production build exit 0.
