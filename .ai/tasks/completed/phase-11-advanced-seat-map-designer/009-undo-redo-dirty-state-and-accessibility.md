# TASK-P11-009: Add Undo/Redo, Dirty-State Guard, and Keyboard Editing

## 1. Task Metadata

- **Task ID:** `TASK-P11-009`
- **Git Branch:** `feat/p11-009-editor-history-dirty-a11y`
- **Target Module:** `frontend/src/app/features/admin/venues`, `frontend/src/app/core/guards`, `frontend/src/app/services`
- **Phase:** `Phase 11 - Advanced Venue & Seat Map Designer`
- **Related Specs:** `.ai/architecture/07-frontend-specification.md` §1 and §3; `.ai/architecture/09-post-mvp-evolution.md` §3.4
- **Related ADRs:** `.ai/decisions/ADR-010-advanced-seat-layout-model.md`
- **Status:** `READY FOR IMPLEMENTATION`
- **Complexity:** 4/5
- **Failure Risk:** Medium
- **Verification Strength:** Partial
- **Affected Invariants:** unsaved state isolation; stable IDs through history; accessible alternatives; route guard behavior; no accidental destructive keyboard action
- **Primary Failure Modes:** history snapshots sharing references; undo crossing last server baseline; route exit losing changes; Delete firing while typing; pointer-only features; unbounded history memory
- **Required Review Depth:** Substantive

## 2. Objective & Critical Invariants

Add a bounded local history, route-leave protection, keyboard commands, and non-pointer alternatives for every editor mutation.

- [ ] Undo/redo changes only the unsaved draft and never calls the API.
- [ ] History preserves all persistent IDs and `layoutVersion` from its base snapshot.
- [ ] A successful server save clears history and establishes a new baseline.
- [ ] Delete/Backspace never mutates layout while focus is in input, textarea, select, or contenteditable.
- [ ] Escape cancels the current gesture/selection without reverting unrelated draft changes.
- [ ] Every drag/resize/rotate/delete action has a focusable numeric/button alternative.

## 3. Dependencies

- TASK-P11-005 editor state and TASK-P11-006 through TASK-P11-008 editing commands.
- Reuse existing `pendingChangesGuard` and `PendingChangesAware`; do not create a second route-guard abstraction.
- Accepted ADR-010.

## 4. Exact File Inventory

- `[NEW]` `frontend/src/app/services/layout-history.service.ts`
- `[NEW]` `frontend/src/app/services/layout-history.service.spec.ts`
- `[MODIFY]` `frontend/src/app/features/admin/venues/venue-grid-designer/venue-grid-designer.component.ts`
- `[MODIFY]` `frontend/src/app/features/admin/venues/venue-grid-designer/venue-grid-designer.component.html`
- `[MODIFY]` `frontend/src/app/features/admin/venues/venue-grid-designer/venue-grid-designer.component.spec.ts`
- `[MODIFY]` `frontend/src/app/core/guards/pending-changes.guard.ts`
- `[MODIFY]` `frontend/src/app/core/guards/guards.spec.ts`
- `[MODIFY]` `frontend/src/app/app.routes.ts`

## 5. State, Keyboard, and Route Contracts

### 5.1 History

- Store at most 100 immutable complete layout snapshots; transient selection/pan/zoom is excluded.
- Coalesce pointer-move events into one history entry committed on pointer end.
- `undo()`/`redo()` are no-ops at stack boundaries.
- Any new command after undo clears redo.
- Load/reset/save success clears both stacks.
- History snapshots preserve IDs and layoutVersion exactly; new null IDs remain null.

### 5.2 Keyboard

- `Ctrl/Cmd+Z`: undo; `Ctrl+Y` and `Ctrl/Cmd+Shift+Z`: redo.
- Arrow keys move selected items 1 unit; Shift+Arrow moves 10; when snap is active, the step is snap size and Shift uses 10× snap size.
- Delete/Backspace deactivates existing selected sections/seats, removes null-ID draft records, and removes selected elements from draft.
- Escape cancels active pointer operation, then clears selection.
- Ignore layout shortcuts when event target is an editable control or when modifier composition/IME is active.

### 5.3 Dirty-State Guard

Add `canDeactivate:[pendingChangesGuard]` to `/admin/venues/:id/designer`. `hasPendingChanges()` returns state-service `isDirty`. Confirmation text names the consequence: unsaved layout edits will be discarded. Browser refresh/close uses `beforeunload` only while dirty and removes the listener on destroy.

### 5.4 Accessible Alternatives

Properties/palette toolbars must support selection, numeric X/Y/width/height/rotation, z-order, activation, duplicate, delete/deactivate, undo, redo, zoom, and fit without drag gestures. Use native buttons/inputs or Angular Material controls with associated labels, focus order, disabled state, and live announcements.

## 6. Implementation Sequence

1. Implement and unit-test bounded immutable history.
2. Route all existing editor mutation commands through one history commit boundary.
3. Add undo/redo toolbar buttons and keyboard shortcuts.
4. Add Delete/Escape/arrow behavior with editable-target guards.
5. Wire existing pending-changes guard and conditional beforeunload.
6. Audit each pointer feature against its form/button alternative and add component tests.

## 7. Negative and Edge Cases

- 101 commands retain only the newest 100 undo points.
- Pointer drag with 50 move events produces one undo entry.
- Undo then edit clears redo.
- Key events in label/name/number inputs do not move or delete items.
- A clean route leaves without prompt; a dirty route asks once; cancel stays.
- Save failure retains history and dirty state; save success clears both.
- Undo/redo never changes the loaded layout version token.

## 8. Security and Accessibility

- Frontend guard is not authorization; backend remains ADMIN-only.
- Do not register global listeners without deterministic teardown.
- Announce command result and selected item count through `aria-live`; restore focus after dialog/toolbar actions.
- Respect reduced-motion settings for selection/undo animations.

## 9. Tests Mapped to Risks

| Risk | Required proof |
|---|---|
| Shared history references | Mutate current draft and assert prior snapshot unchanged. |
| Accidental delete while typing | Input/contenteditable key tests produce no layout change. |
| Unsaved loss | Guard tests cover clean, confirm, cancel, and missing custom confirmation. |
| History/API coupling | Undo/redo asserts zero HTTP calls. |
| Accessibility gap | DOM tests find named keyboard/form controls for each mutation class. |

## 10. Exact Verification Commands

```bash
cd frontend
npm exec prettier -- --check src/app/services/layout-history.service.ts src/app/services/layout-history.service.spec.ts src/app/features/admin/venues/venue-grid-designer src/app/core/guards/pending-changes.guard.ts src/app/core/guards/guards.spec.ts src/app/app.routes.ts
npm exec tsc -- -p tsconfig.app.json --noEmit
npm test -- --watch=false --browsers=ChromeHeadless --include=src/app/services/layout-history.service.spec.ts --include=src/app/features/admin/venues/venue-grid-designer/venue-grid-designer.component.spec.ts --include=src/app/core/guards/guards.spec.ts
npm run build
```

Expected result: all commands exit 0; history boundary, editable-target, and route-confirmation tests pass.

## 11. Review Focus

- Snapshot immutability, coalescing, stack cap, and version preservation.
- Global event listener teardown and shortcut target filtering.
- Exact destructive semantics for existing versus null-ID records.
- Complete pointer-action accessibility parity.

## 12. Acceptance Criteria

- [ ] Up to 100 local commands can be undone/redone with correct redo invalidation.
- [ ] Dirty route/browser exit is guarded; clean exit is not.
- [ ] Keyboard shortcuts never mutate while typing.
- [ ] Every pointer mutation has a keyboard/form alternative.
- [ ] Save success resets history; save failure retains it.
- [ ] Formatting, static check, targeted tests, and production build exit 0.
