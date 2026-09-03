# TASK-P11-012: Render Shared Geometry in Customer Preview and Close Phase Compatibility

## 1. Task Metadata

- **Task ID:** `TASK-P11-012`
- **Git Branch:** `feat/p11-012-customer-renderer-preview-tests`
- **Target Module:** `frontend/src/app/features/booking`, `frontend/src/app/features/admin/venues`; compatibility verification across seat-map-service and event-service
- **Phase:** `Phase 11 - Advanced Venue & Seat Map Designer`
- **Related Specs:** `.ai/architecture/07-frontend-specification.md` §4.5-4.6; `.ai/architecture/09-post-mvp-evolution.md` §3.4-3.5
- **Related ADRs:** `.ai/decisions/ADR-010-advanced-seat-layout-model.md`
- **Status:** `READY FOR IMPLEMENTATION`
- **Complexity:** 5/5
- **Failure Risk:** Critical
- **Verification Strength:** Strong
- **Affected Invariants:** customer seat IDs; max-10 client guard; price/section association; live availability reconciliation; shared geometry interpretation; legacy rendering; preview parity; inaccessible/inactive seat exclusion
- **Primary Failure Modes:** visible seat at wrong coordinate; preview differing from customer; advanced fields dropped during flattening; legacy grid regression; element overlay intercepting seat clicks; selection/availability keyed by position instead of ID; mobile/keyboard regression
- **Required Review Depth:** Critical

## 2. Objective & Critical Invariants

Update the customer seat-selection renderer and admin preview to use the same shared layout canvas primitives, then run the Phase 11 compatibility gate across migration, APIs, event mapping, serialization, and legacy rendering.

- [ ] Selection, conflicts, WebSocket updates, holds, pricing, reservations, and tickets continue to key only by stable `seatId`/`sectionId`.
- [ ] The customer renderer uses continuous seat/section geometry when present and the exact 44-unit grid fallback when absent.
- [ ] Admin preview is read-only and uses the same section/seat/element rendering path as customers.
- [ ] Layout elements are `aria-hidden` when decorative and never intercept seat pointer/keyboard events.
- [ ] Maximum 10-seat frontend behavior and server-side reservation invariant remain unchanged.
- [ ] No CAD/3D/Bézier/SVG-path behavior is introduced.

## 3. Dependencies

- TASK-P11-006 shared canvas, TASK-P11-010 saved editor state, and TASK-P11-011 event response.
- Existing `SeatStateService`, WebSocket reconciliation, `KeyboardSeatNavDirective`, pricing-tier UI, and reservation request contract.
- Accepted ADR-010 and all prior Phase 11 tasks passing their targeted verification.

## 4. Exact File Inventory

- `[MODIFY]` `frontend/src/app/models/seat.model.ts`
- `[MODIFY]` `frontend/src/app/features/booking/seat-selection/seat-selection.component.ts`
- `[MODIFY]` `frontend/src/app/features/booking/seat-selection/seat-selection.component.spec.ts`
- `[MODIFY]` `frontend/src/app/features/booking/seat-map/seat-map.component.ts`
- `[MODIFY]` `frontend/src/app/features/booking/seat-map/seat-map.component.html`
- `[MODIFY]` `frontend/src/app/features/booking/seat-map/seat-map.component.scss`
- `[MODIFY]` `frontend/src/app/features/booking/seat-map/seat-map.component.spec.ts`
- `[MODIFY]` `frontend/src/app/features/admin/venues/venue-grid-designer/venue-grid-designer.component.ts`
- `[MODIFY]` `frontend/src/app/features/admin/venues/venue-grid-designer/venue-grid-designer.component.html`
- `[MODIFY]` `frontend/src/app/features/admin/venues/venue-grid-designer/venue-grid-designer.component.spec.ts`

## 5. Rendering and Compatibility Contracts

### 5.1 Model/Flattening

Extend `Seat`, `SeatMapSectionResponse`, and `EventSeatMapResponse` with TASK-P11-011 fields. `SeatSelectionComponent.flattenSeats` must copy continuous position and section geometry without changing IDs, pricing, currency, status, or `isActive`. Legacy missing positions derive `gridX*44/gridY*44`; missing section geometry derives the same defaults as event-service.

### 5.2 Customer Renderer

Refactor `SeatMapComponent` to delegate spatial section/seat/element rendering to `LayoutCanvasComponent` in read-only mode while retaining its existing section tabs, price tiers, zoom controls, ARIA grid navigation, tooltips, selected/conflict animation, and seat-toggle output. Section rotation applies to visual seats, but keyboard navigation continues using `gridX/gridY`. Element layers use stable z-order and `pointer-events:none` in customer/preview mode.

Inactive sections are absent; inactive/unpriced seats are unavailable and not focusable. Seat click output still returns the original `Seat` object keyed by ID. Live status updates change status only, never geometry.

### 5.3 Admin Preview

Add an Edit/Preview toggle. Preview renders the current unsaved draft through `SeatMapComponent` with `previewMode=true`: no selection/hold action, active seats remain visible without event prices, geometry/elements match the customer canvas, and an explicit banner states that availability/pricing is not simulated. Leaving preview returns to the same draft/history/selection.

### 5.4 Responsive and Accessibility Contract

- Pan/zoom and section isolation work on touch and desktop.
- Complex editor shows a desktop/tablet-landscape recommendation below 1024px but remains scrollable and exposes form controls.
- Customer seats retain one roving tabindex per visible section, roles/labels, Enter/Space activation, arrow navigation, and 44px effective touch target.
- Respect reduced motion.

## 6. Implementation Sequence

1. Extend frontend event/seat models and flattening with exact fallback rules.
2. Adapt shared canvas for customer seat status/selection presentation without editor mutations.
3. Refactor customer `SeatMapComponent` around the shared spatial renderer while preserving existing UI/state contracts.
4. Add admin preview mode using the same component/read-only geometry.
5. Add advanced multi-section/rotated/element tests and legacy grid-only smoke tests.
6. Run frontend static/test/build gates.
7. Re-run the migration, atomic-save, event adapter, and full relevant backend modules as the Phase 11 gate.
8. Inspect `git diff --check`, exact task inventories, and `.ai/tmp` for unresolved review ledgers before completion.

## 7. Negative and Edge Cases

- Legacy grid-only event response renders seats at identical 44-unit relative positions.
- Rotated section seats remain clickable/focusable by their original IDs.
- Decorative element overlapping a seat cannot intercept click/focus.
- Inactive section/seat and active unpriced seat cannot be selected.
- Empty layout/elements render a named empty state without exceptions.
- Preview with null IDs renders but emits no selection/save mutation.
- WebSocket update after geometry render updates the matching ID only.
- At 10 selected seats, an eleventh remains blocked exactly as before.

## 8. Security and Observability

- Admin preview is guarded for UX; all persistence remains server ADMIN-only.
- Customer endpoint stays public and exposes only layout data already present in event seat-map contract.
- Do not log full layouts, JWTs, customer emails, or selected price payloads.
- No dynamic HTML/SVG path rendering from server values.

## 9. Tests Mapped to Risks

| Risk | Required proof |
|---|---|
| Legacy render regression | Grid-only fixture produces exact pre-phase seat relative coordinates and selectable IDs. |
| Preview/customer divergence | Same layout fixture produces equal section/seat/element transforms in both modes. |
| ID-based state regression | Selection and WebSocket conflict target only matching seat ID after rotation/move. |
| Overlay interception | Pointer/focus test through overlapping element reaches seat. |
| Booking guard regression | Existing max-10, pricing, reservation body, and reconciliation tests remain green. |
| Serialization/migration regression | Re-run prior targeted backend/frontend suites in final gate. |

## 10. Exact Verification Commands

```bash
cd frontend
npm exec prettier -- --check src/app/models/seat.model.ts src/app/features/booking/seat-selection src/app/features/booking/seat-map src/app/features/admin/venues/venue-grid-designer
npm exec tsc -- -p tsconfig.app.json --noEmit
npm test -- --watch=false --browsers=ChromeHeadless --include=src/app/features/booking/seat-selection/seat-selection.component.spec.ts --include=src/app/features/booking/seat-map/seat-map.component.spec.ts --include=src/app/features/admin/venues/venue-grid-designer/venue-grid-designer.component.spec.ts --include=src/app/services/venue-layout-editor-state.service.spec.ts
npm run build
cd ../backend
mvn -pl services/seat-map-service,services/event-service -am -Dtest=AdvancedSeatLayoutMigrationTest,VenueLayoutServiceImplTest,SeatMapServiceIntegrationTest,SeatMapClientImplTest,EventServiceImplTest,EventControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl services/seat-map-service,services/event-service -am test
cd ..
git diff --check
```

Expected result: every command exits 0; legacy/advanced parity, stable-ID interaction, atomic-save, and migration assertions pass. The repository currently has no Angular lint target, so Prettier plus `tsc --noEmit` is the lint/static gate.

## 11. Review Focus

- Critical end-to-end review from V5 IDs through seat-map API, event adapter, flattening, renderer, selection, and reservation request.
- Exact shared transform parity and legacy fallback.
- Pointer/keyboard behavior under transformed sections and overlays.
- No booking logic coupled to visual position and no unsupported graphics scope.
- No active `.ai/tmp/review-*.md` ledger before task completion.

## 12. Acceptance Criteria

- [ ] Legacy grid venues render identically or near-identically without manual edits.
- [ ] Advanced sections/elements render the same in admin preview and customer selection.
- [ ] Stable seat/section IDs continue through pricing, selection, live updates, and reservation submission.
- [ ] Max-10 and availability reconciliation behavior remain green.
- [ ] Decorative elements cannot become bookable or intercept seats.
- [ ] Frontend formatting/static/test/build and both backend module suites exit 0.
- [ ] Independent critical review and final QA return PASS with no active temporary ledger.
