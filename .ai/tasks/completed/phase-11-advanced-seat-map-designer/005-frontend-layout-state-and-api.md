# TASK-P11-005: Add Typed Frontend Layout State and API Serialization

## 1. Task Metadata

- **Task ID:** `TASK-P11-005`
- **Git Branch:** `feat/p11-005-frontend-layout-state-api`
- **Target Module:** `frontend/src/app/models`, `frontend/src/app/services`
- **Phase:** `Phase 11 - Advanced Venue & Seat Map Designer`
- **Related Specs:** `.ai/architecture/07-frontend-specification.md` §1, §3, §4.5; `.ai/architecture/09-post-mvp-evolution.md` §3.4
- **Related ADRs:** `.ai/decisions/ADR-010-advanced-seat-layout-model.md`
- **Status:** `READY FOR IMPLEMENTATION`
- **Complexity:** 3/5
- **Failure Risk:** High
- **Verification Strength:** Strong
- **Affected Invariants:** frontend/backend DTO parity; stable IDs; immutable editor snapshots; layout-version propagation; grid compatibility; typed visual geometry
- **Primary Failure Modes:** dropping UUIDs during serialization; numeric/string drift; mutating loaded snapshots in place; omitting inactive seats; sending stale/incorrect layout version; accepting malformed API shapes
- **Required Review Depth:** Substantive

## 2. Objective & Critical Invariants

Extend the existing venue models and API service with the exact TASK-P11-003/004 contract, and introduce a signal-based editor state service whose serialization round-trip preserves IDs and geometry.

- [ ] `seatId`, `sectionId`, and `elementId` remain unchanged for loaded records.
- [ ] New client-only records use `null` IDs until the server assigns UUIDs; the client never fabricates persistent IDs.
- [ ] `layoutVersion` from the last server response is sent unchanged on the next save.
- [ ] `gridX/gridY` remain present beside `positionX/positionY`.
- [ ] JSONB geometry is represented by typed interfaces, not `Record<string, unknown>`.
- [ ] Component-level state uses Signals and immutable replacement.

## 3. Dependencies

- TASK-P11-003 response/request field names and TASK-P11-004 endpoints.
- Existing `AdminVenueApiService`, `venue.model.ts`, and `ApiErrorResponse`.
- Accepted ADR-010.

## 4. Exact File Inventory

- `[MODIFY]` `frontend/src/app/models/venue.model.ts`
- `[MODIFY]` `frontend/src/app/services/admin-venue-api.service.ts`
- `[MODIFY]` `frontend/src/app/services/admin-venue-api.service.spec.ts`
- `[NEW]` `frontend/src/app/services/venue-layout-editor-state.service.ts`
- `[NEW]` `frontend/src/app/services/venue-layout-editor-state.service.spec.ts`

## 5. Contracts

### 5.1 TypeScript Model

Add exact types mirroring backend JSON:

```typescript
export type LayoutElementType = 'STAGE' | 'AISLE' | 'LABEL' | 'BARRIER' | 'DECORATION';
export interface LayoutGeometry { x: number; y: number; width: number; height: number; rotationDeg: number; }
export interface VenueLayoutElement { elementId: string | null; type: LayoutElementType; label: string | null; geometry: LayoutGeometry; zIndex: number; }
export interface VenueSectionSeat {
  seatId: string | null;
  rowLabel: string;
  seatNumber: number;
  gridX: number;
  gridY: number;
  positionX: number;
  positionY: number;
  isActive: boolean;
}
export interface VenueSectionLayout {
  sectionId: string | null;
  name: string;
  rowCount: number;
  colCount: number;
  isActive: boolean;
  positionX: number;
  positionY: number;
  width: number;
  height: number;
  rotationDeg: number;
  zIndex: number;
  shapeMetadata: object | null;
  seats: VenueSectionSeat[];
}
export interface VenueLayout {
  venueId: string;
  name: string;
  capacity: number;
  totalConfiguredSeats: number;
  layoutVersion: number;
  sections: VenueSectionLayout[];
  elements: VenueLayoutElement[];
}
export interface SaveVenueLayoutRequest {
  layoutVersion: number;
  sections: VenueSectionLayout[];
  elements: VenueLayoutElement[];
}
```

Keep existing venue summary/create/update types.

### 5.2 API Methods

```typescript
getEditableLayout(venueId: string): Observable<VenueLayout>; // GET /api/admin/venues/{id}/layout
validateLayout(venueId: string, request: SaveVenueLayoutRequest): Observable<void>; // POST .../layout/validation
saveLayout(venueId: string, request: SaveVenueLayoutRequest): Observable<VenueLayout>; // PUT .../layout
```

Keep `getVenueLayout` for the public legacy route until TASK-P11-012 verifies all callers.

### 5.3 Editor State

The root-provided state service exposes read-only signals for `layout`, `baseline`, `isDirty`, `isSaving`, and `loadError`; methods `load`, `replaceDraft`, `applyServerSnapshot`, `buildSaveRequest`, and `resetToBaseline` use deep value copies for arrays/objects. Dirty comparison is deterministic canonical JSON with sections/elements ordered by existing array order and object keys normalized; numeric values are compared as JavaScript numbers. Transient UI state (selection, pan, zoom) is excluded.

## 6. Implementation Sequence

1. Extend model types without deleting legacy fields.
2. Add the three typed API methods and HttpTestingController assertions.
3. Implement editor state load/baseline/draft transitions using Signals.
4. Implement canonical serialization and server-snapshot replacement.
5. Add round-trip tests using multiple sections, inactive seats, null new IDs, rotation, decimals, elements, and version.
6. Add tests proving UI-only state is absent from save JSON.

## 7. Negative and Edge Cases

- Empty section/element arrays round-trip.
- Decimal coordinates, negative rotation, and z-index survive serialization.
- `null` IDs remain null; non-null UUID strings remain byte-for-byte unchanged.
- Failed load leaves no stale baseline.
- Failed save does not replace baseline or increment local version.
- Successful save replaces draft and baseline with the server-assigned IDs/version.
- Mutating an object returned by test fixtures cannot mutate the stored baseline by reference.

## 8. Security

- The frontend ADMIN guard is UX only; backend authorization remains mandatory.
- The API service relies on the existing auth interceptor and must not read/store tokens.
- API errors retain the shared `ApiErrorResponse` shape for TASK-P11-010 conflict routing.

## 9. Tests Mapped to Risks

| Risk | Required proof |
|---|---|
| Lost IDs | Serialization round-trip asserts every non-null ID. |
| Stale version | Save request uses the loaded baseline version exactly. |
| Reference mutation | Fixture mutation after load does not alter baseline/draft. |
| Contract drift | HTTP specs assert URL, method, body, and response fields. |
| Hidden UI data | Serialized JSON has only backend contract keys. |

## 10. Exact Verification Commands

The repository has no configured Angular lint target; use Prettier plus TypeScript `--noEmit` as the deterministic formatting/static-analysis gate.

```bash
cd frontend
npm exec prettier -- --check src/app/models/venue.model.ts src/app/services/admin-venue-api.service.ts src/app/services/admin-venue-api.service.spec.ts src/app/services/venue-layout-editor-state.service.ts src/app/services/venue-layout-editor-state.service.spec.ts
npm exec tsc -- -p tsconfig.app.json --noEmit
npm test -- --watch=false --browsers=ChromeHeadless --include=src/app/services/admin-venue-api.service.spec.ts --include=src/app/services/venue-layout-editor-state.service.spec.ts
npm run build
```

Expected result: all four commands exit 0 and the round-trip test asserts stable IDs/version.

## 11. Review Focus

- Exact backend JSON parity and null-ID semantics.
- Deep immutability and dirty comparison determinism.
- No persistent ID generation in the browser.
- API URL/method/body correctness.

## 12. Acceptance Criteria

- [ ] API and state types match TASK-P11-003/004 exactly.
- [ ] Serialization round-trip preserves all stable IDs, geometry, activity, and version.
- [ ] Save failure leaves baseline/version unchanged.
- [ ] New records remain null-ID until server response.
- [ ] Formatting, static check, targeted tests, and production build exit 0.
