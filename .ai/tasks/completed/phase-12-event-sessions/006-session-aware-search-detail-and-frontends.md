# TASK-P12-006: Integrate Session Selection into Event Detail, Organizer UI, and Customer Booking

## 1. Task Metadata

- **Task ID:** `TASK-P12-006`
- **Git Branch:** `feat/p12-006-session-aware-frontends`
- **Target Module:** `frontend`, event/reservation API adapters as required
- **Phase:** `Phase 12 - Multiple Event Sessions / Showings`
- **Related ADRs:** `ADR-011-event-sessions-booking-boundary.md`
- **Status:** `READY FOR IMPLEMENTATION`

### Orchestration Metadata

- **Complexity:** `5`
- **Failure Risk:** `High`
- **Verification Strength:** `Strong`
- **Required Review Depth:** `Substantive`
- **Preferred Workflow:** `standard`
- **Affected Critical Invariants:** `Explicit session selection; no mixed-session cart; server authorization remains authoritative; realtime reconciliation`

---

## 2. Objective

Expose multiple sessions coherently in organizer and customer Angular flows. Customers must select a concrete session before seat availability/selection begins; organizer can manage sessions without duplicating event metadata. Search/discovery stays event-centric.

---

## 3. Critical Invariants & Failure Modes

- [ ] Event cards/search results are not duplicated per session.
- [ ] Seat map/availability/reservation requests cannot execute without a validated selected `eventSessionId`.
- [ ] Switching sessions clears all local seat selection, quote/offer/cart state tied to the previous session and unsubscribes its realtime topic.
- [ ] If an active server hold exists when switching, client attempts the established cancel/release operation for that reservation; local state is cleared even if network cancellation fails, and the backend 15-minute expiry remains the safety net. Never transfer held seats to the new session.
- [ ] Deep-link/query-param session preselection is accepted only after API data confirms the session belongs to the current event and is customer-visible.
- [ ] Browser refresh reconstructs session identity explicitly; it does not choose the first session and continue a stale cart.
- [ ] Loading/error/empty/disabled states prevent accidental calls with `undefined`, stale, or previous session IDs.
- [ ] Reconnect subscribes only to the current session topic and refreshes authoritative availability.

---

## 4. Dependencies / Prerequisites

- P12-002 customer/organizer session API.
- P12-003 reservation session API.
- P12-005 session realtime destination.
- Existing Angular architecture and `frontend/AGENTS.md` conventions.

---

## 5. Exact File Inventory

Before edits, identify current event models/API service, event detail page, seat-map/booking state service, cart/checkout state, realtime/STOMP service, organizer event editor, and routes. Expected changes are limited to those existing feature areas plus new session components/models.

Expected new artifacts (adapt names to current feature naming conventions rather than creating duplicate abstractions):

- `EventSession` frontend model/interface.
- event API methods for customer/organizer session list + organizer CRUD.
- customer session selector component on event detail.
- organizer session management panel/dialog/form.
- booking state field `selectedSessionId` and session-aware derived selectors.
- session-aware realtime subscription.
- focused unit/component tests and browser E2E/specs.

Do not create a second global cart/state framework if an existing service/store already owns booking state.

---

## 6. Technical Specifications & Contracts

### 6.1 Customer State Machine

```text
EVENT_LOADING
 -> EVENT_READY_NO_SESSION
 -> SESSION_SELECTED_LOADING_AVAILABILITY
 -> SESSION_READY
 -> SEATS_SELECTED / HOLD_ACTIVE
```

Any event change or session change returns to `EVENT_READY_NO_SESSION` or the explicitly selected validated session state and clears downstream session-bound data.

No implicit “first session” auto-selection. A deep link may explicitly select a valid session after validation.

### 6.2 Session Selector

Show at minimum date/time and sale/availability state. Sort using backend order (`startsAt`, tie-breaker ID). Disable non-bookable sessions rather than letting a later reservation request fail after seat selection. Empty state clearly says no bookable showings rather than showing an empty seat map.

### 6.3 API Calls

All availability/hold/reservation calls use `eventSessionId`. Remove frontend request construction that sends `eventId` as booking key. Event ID remains for navigation/catalog detail.

### 6.4 Session Switch Atomicity in UI

On switch from A -> B:

1. mark booking controls disabled during transition;
2. unsubscribe A realtime;
3. best-effort cancel active A hold using its reservation identity if current UX permits cancellation;
4. clear A seat/price/hold/cart state unconditionally;
5. set validated B session;
6. load B authoritative availability;
7. subscribe B realtime;
8. re-enable controls only after B base state is known.

Late HTTP/WebSocket responses from A must be ignored using request/session identity checking; they cannot overwrite B state.

### 6.5 Organizer UI

- Manage sessions under one event.
- Create/update forms mirror backend temporal validation and display backend errors.
- Locked sessions show disabled mutation controls with reason.
- Deleting one eligible session never deletes the event or another session.

### 6.6 Accessibility / Responsive

Session options must be keyboard accessible, have visible selected/disabled state, semantic labels including full date/time, and remain usable at mobile widths used by current app.

---

## 7. Step-by-Step Implementation Sequence

1. Update TypeScript models and API clients.
2. Extend booking state with explicit selected session and stale-response guard.
3. Implement customer selector and wire detail -> availability -> seat map.
4. Update reservation/cart/checkout requests.
5. Update realtime subscribe/unsubscribe/reconnect.
6. Implement organizer session management.
7. Add unit/component tests.
8. Add browser E2E for two sessions and session switching.

---

## 8. Test Requirements

- [ ] Event list shows one event card for event with two sessions.
- [ ] Seat map cannot load before session selection.
- [ ] Select A -> A availability/topic used.
- [ ] Switch A -> B -> A selection/hold/cart state does not survive; B availability/topic used.
- [ ] Delayed A response arriving after B selection is ignored.
- [ ] Invalid deep-link session belonging to another event is rejected/reset.
- [ ] Browser refresh with valid explicit session restores correctly after validation; stale invalid session does not.
- [ ] Organizer creates/updates/deletes eligible session and sees lock errors.
- [ ] Mobile/keyboard session selection is usable.

---

## 9. Verification Commands

```bash
cd frontend
npm run lint
npm test -- --watch=false
npm run build
```

Run repository-standard browser/E2E command for the two-session flow.

---

## 10. Independent Review Focus

Stale async response races, mixed-session cart/hold state, implicit selection, deep-link validation, realtime unsubscription/reconnect, and UX states that can send null/stale IDs.

---

## 11. Acceptance Criteria

- [ ] Explicit session selection is required before booking.
- [ ] Cross-session UI state cannot mix.
- [ ] Event discovery remains event-centric.
- [ ] Organizer session CRUD UX reflects backend lock/ownership rules.
- [ ] Browser E2E with two sessions passes.
- [ ] Substantive review and final QA pass.

---

## 12. Execution Entry Point

```text
Implement TASK-P12-006 using the SeatFlow autonomous orchestration workflow.
```
