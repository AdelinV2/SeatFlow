# Phase 12 — Multiple Event Sessions / Showings

**Status:** `PLANNED`  
**Architecture:** `.ai/architecture/09-post-mvp-evolution.md`  
**Related ADR:** `.ai/decisions/ADR-011-event-sessions-booking-boundary.md`  
**Estimated effort:** ~10–12 focused implementation hours  

---

## 1. Outcome

Allow one event to contain multiple concrete scheduled showings while keeping seat availability, reservations and tickets independent for each showing.

## 2. Domain Model

Event = catalog/content aggregate: title, description, category, banner, venue, pricing, publication state.

EventSession = bookable time instance: `id`, `eventId`, `startsAt`, optional `endsAt`, status, optional sales window, version.

For this phase all sessions of an event share the event's venue and pricing. Do not add session-specific pricing unless a separate future decision approves it.

## 3. Migration Plan

1. Create `event_sessions` via Flyway.
2. For every existing event, create one initial session from existing `event_date`.
3. Expose session IDs and update dependent services.
4. Backfill reservations/seat holds/tickets to the matching generated session.
5. Make `session_id` required after validation.
6. Deprecate `event_date`; remove it only when no runtime contract depends on it.

Migration scripts must be deterministic and safe against duplicate execution through Flyway semantics.

## 4. Reservation / Concurrency Changes

The authoritative inventory key becomes `(sessionId, seatId)`.

Update:

- reservation entity/DTOs;
- seat hold entity/constraints;
- create reservation request;
- availability query and locking queries;
- idempotency semantics if request fingerprint includes event information;
- hold expiration events;
- realtime seat-status events.

Critical verification: the same physical seat may be sold once in Session A and independently once in Session B, but never twice within the same session.

## 5. Realtime Changes

Move subscriptions to session scope, e.g. `/topic/sessions/{sessionId}/seats`. Reconnect reconciliation fetches session-specific availability.

Do not broadcast one session's seat state into another session.

## 6. Ticket / Notification Changes

Tickets carry `sessionId` and show the concrete local date/time. Email templates and PDF/QR ticket details must use the session schedule.

Scanner verification continues to validate ticket identity/status; display session context where useful.

## 7. Frontend Changes

- Event Detail lists future sessions/showings.
- Session must be selected before seat selection.
- Seat-selection route and services include session ID.
- Calendar derives event occurrences from sessions.
- Admin event editor supports add/edit/cancel session.
- Prevent duplicate/invalid session times and starts-in-past where policy requires.
- My Tickets, order confirmation and guest ticket pages show showing date/time.

## 8. Event Lifecycle

Session statuses: `SCHEDULED`, `CANCELLED`, `COMPLETED`.

Event completion becomes derived/reconciled from sessions. Cancelling one session must not cancel the whole event unless explicitly chosen by admin workflow.

## 9. Suggested Atomic Tasks

1. `001-event-session-schema-domain-and-migration.md`
2. `002-event-session-admin-and-public-apis.md`
3. `003-reservation-session-scoped-inventory.md`
4. `004-realtime-session-topics-and-reconciliation.md`
5. `005-ticket-notification-session-propagation.md`
6. `006-frontend-session-selection-and-routing.md`
7. `007-admin-session-management-and-calendar.md`
8. `008-session-lifecycle-migration-tests.md`

## 10. Definition of Done

- [ ] One event can expose multiple sessions.
- [ ] Existing events automatically receive one migrated session.
- [ ] Seat availability is independent per session.
- [ ] Reservation lock/unique constraints prevent double booking within a session.
- [ ] Tickets and emails show the correct session.
- [ ] Realtime topics cannot leak state between sessions.
- [ ] Existing pricing remains event-level.
