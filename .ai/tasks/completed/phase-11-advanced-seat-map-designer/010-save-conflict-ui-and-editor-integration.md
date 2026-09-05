# TASK-P11-010: Integrate Atomic Save and Stale-Version Conflict Recovery

## 1. Task Metadata

- **Task ID:** `TASK-P11-010`
- **Git Branch:** `feat/p11-010-save-conflict-ui`
- **Target Module:** `frontend/src/app/features/admin/venues`, `frontend/src/app/services`
- **Phase:** `Phase 11 - Advanced Venue & Seat Map Designer`
- **Related Specs:** `.ai/architecture/06-api-contracts.md` §1; `.ai/architecture/09-post-mvp-evolution.md` §3.3-3.4
- **Related ADRs:** `.ai/decisions/ADR-010-advanced-seat-layout-model.md`
- **Status:** `READY FOR IMPLEMENTATION`
- **Complexity:** 4/5
- **Failure Risk:** High
- **Verification Strength:** Partial
- **Affected Invariants:** one atomic save request; stale-write prevention; no silent overwrite; baseline/version correctness; server validation authority
- **Primary Failure Modes:** duplicate save submissions; treating all 409s alike outside save context; overwriting a newer layout; incrementing local version optimistically; losing draft on conflict/reload; dismissing server validation details
- **Required Review Depth:** Substantive

## 2. Objective & Critical Invariants

Wire the advanced designer to validation/save endpoints and provide explicit recovery when a stale layout version returns `409 SF_409_CONFLICT`.

- [ ] One Save action sends one full snapshot request; no section/seat fan-out.
- [ ] The browser never increments `layoutVersion`; only the server response changes it.
- [ ] A conflict never auto-overwrites server state and never silently discards the local draft.
- [ ] Save is disabled while a request is in flight and repeat clicks create no second request.
- [ ] Backend 400 validation details are shown against the editor and do not reset history/baseline.

## 3. Dependencies

- TASK-P11-004 API and stable conflict code.
- TASK-P11-005 state/API and TASK-P11-009 history/dirty behavior.
- Existing `ApiErrorResponse` model and global error interceptor.
- Accepted ADR-010.

## 4. Exact File Inventory

- `[MODIFY]` `frontend/src/app/features/admin/venues/venue-grid-designer/venue-grid-designer.component.ts`
- `[MODIFY]` `frontend/src/app/features/admin/venues/venue-grid-designer/venue-grid-designer.component.html`
- `[MODIFY]` `frontend/src/app/features/admin/venues/venue-grid-designer/venue-grid-designer.component.spec.ts`
- `[NEW]` `frontend/src/app/features/admin/venues/layout-conflict-dialog/layout-conflict-dialog.component.ts`
- `[NEW]` `frontend/src/app/features/admin/venues/layout-conflict-dialog/layout-conflict-dialog.component.html`
- `[NEW]` `frontend/src/app/features/admin/venues/layout-conflict-dialog/layout-conflict-dialog.component.scss`
- `[NEW]` `frontend/src/app/features/admin/venues/layout-conflict-dialog/layout-conflict-dialog.component.spec.ts`
- `[MODIFY]` `frontend/src/app/services/admin-venue-api.service.ts`
- `[MODIFY]` `frontend/src/app/services/admin-venue-api.service.spec.ts`

## 5. Save and Conflict Contracts

### 5.1 Save Flow

1. Run local validator; if invalid, focus the first invalid control and make no HTTP call.
2. Call backend validation POST. A 204 proceeds; a 400 displays server violations and stops.
3. Call one PUT with the same immutable request snapshot used for validation.
4. On 200, replace draft/baseline with response, clear history/dirty state, display saved version, and keep selection only for IDs present in response.
5. On non-conflict failure, retain draft/version/history and re-enable Save.

Serialize the snapshot once before validation so edits made while validation is in flight cannot change the subsequent PUT body. Disable layout mutations until this flow completes or fails.

### 5.2 Conflict Dialog

Open only when the save PUT returns status 409 and `error.errorCode === 'SF_409_CONFLICT'`. Show local version and explain that another save exists. Actions:

- `Keep editing`: close; retain local draft/history/version.
- `Reload server layout`: GET editable layout; after confirmation, replace local draft/baseline/history with server response.
- `Copy local JSON`: write the typed save snapshot to clipboard for recovery; if clipboard fails, show selectable escaped text in the dialog.

There is no Force Save action and no automatic retry with a new version.

### 5.3 Validation Display

Map `validationErrors[].field/message` from `ApiErrorResponse` into a visible summary and field associations. Unknown fields remain in the summary with the correlation ID. Never render message HTML.

## 6. Implementation Sequence

1. Add save/validation observable wiring and in-flight mutation lock.
2. Implement response-to-baseline success path.
3. Build conflict dialog with the three exact actions.
4. Add safe clipboard fallback and escaped validation summary.
5. Add tests for immutable request snapshot, duplicate-click suppression, every response class, and history/dirty outcomes.
6. Verify global interceptor still rethrows the error; do not weaken global 409 behavior for other features.

## 7. Negative and Edge Cases

- Edit attempted during validation/save is disabled and cannot alter sent snapshot.
- Validation 204 followed by save 409 retains the original local snapshot.
- Reload failure closes no state and retains local draft.
- Conflict with any other error code uses the generic failure path, not stale-layout recovery.
- Save response with server-assigned IDs replaces null IDs and prunes invalid selection.
- Clipboard API unavailable/denied exposes escaped selectable JSON.
- Closing dialog by Escape equals `Keep editing`.

## 8. Security and Accessibility

- Server ADMIN authorization remains authoritative.
- Dialog uses Angular Material focus trap or equivalent accessible dialog semantics, named buttons, Escape behavior, and focus restoration to Save.
- Never use `innerHTML`; include correlation ID but no token/request headers.
- Clipboard content contains layout geometry/IDs only, matching typed request fields.

## 9. Tests Mapped to Risks

| Risk | Required proof |
|---|---|
| Duplicate/changed request | Rapid clicks produce one POST/PUT and PUT body equals validated snapshot. |
| Silent overwrite | Conflict offers no force/auto-retry and issues no second PUT. |
| Draft loss | Keep/edit and reload-failure paths retain draft/history/version. |
| Version forgery | Local version changes only from a 200 response/reload. |
| Unsafe messages | Hostile message is rendered as text. |

## 10. Exact Verification Commands

```bash
cd frontend
npm exec prettier -- --check src/app/features/admin/venues/venue-grid-designer src/app/features/admin/venues/layout-conflict-dialog src/app/services/admin-venue-api.service.ts src/app/services/admin-venue-api.service.spec.ts
npm exec tsc -- -p tsconfig.app.json --noEmit
npm test -- --watch=false --browsers=ChromeHeadless --include=src/app/features/admin/venues/venue-grid-designer/venue-grid-designer.component.spec.ts --include=src/app/features/admin/venues/layout-conflict-dialog/layout-conflict-dialog.component.spec.ts --include=src/app/services/admin-venue-api.service.spec.ts
npm run build
```

Expected result: all commands exit 0; tests prove no auto-overwrite, duplicate request, or local version increment.

## 11. Review Focus

- Immutable validate/save snapshot and in-flight lock.
- Exact status/error-code routing and absence of Force Save.
- Baseline/history/version changes on every outcome.
- Dialog accessibility, escaping, and clipboard fallback.

## 12. Acceptance Criteria

- [ ] Valid save uses one validation POST and one atomic PUT.
- [ ] Stale save shows exact conflict recovery and sends no retry PUT.
- [ ] Failure retains local draft/history/version.
- [ ] Success adopts server IDs/version and clears dirty/history.
- [ ] Server validation errors are visible and escaped.
- [ ] Formatting, static check, targeted tests, and production build exit 0.
