# ADR-011: Event Sessions as the Bookable Inventory Boundary

- **Date:** 2026-09-02
- **Status:** `ACCEPTED`
- **Driven by:** Phase 12 — Multiple Event Sessions / Showings

## 1. Context

The current Event model has one `event_date`, and reservations/seat holds partition availability by `event_id`. Supporting multiple showings requires the same event content and venue seats to be sold independently for different times.

## 2. Decision

Introduce `event_sessions` under Event Service. An Event represents reusable catalog/content identity; an Event Session represents one concrete scheduled showing.

After migration, reservation ownership of inventory is scoped by `(session_id, seat_id)`. Reservations, seat holds, tickets, availability APIs and WebSocket topics carry `eventSessionId`.

For Phase 12, venue and pricing remain event-level and shared by all sessions. This avoids introducing session-specific pricing/venue complexity prematurely.

Each existing `event_date` is migrated to one initial session before the old field is deprecated.

## 3. Alternatives Considered

### Duplicate Event per showing
- Pros: minimal schema changes.
- Cons: duplicates content/pricing/admin work; poor user experience; analytics and AI see separate products.
- Rejected.

### Session-specific venue and pricing immediately
- Pros: maximum flexibility.
- Cons: significantly expands Phase 12 and complicates migrations.
- Deferred; may be introduced only by a future ADR if a real requirement appears.

## 4. Consequences

- Reservation uniqueness and concurrency constraints must change from event-scoped to session-scoped.
- Realtime topics and frontend routes become session-aware.
- Refund cutoff can use a precise session start time.
- Event lifecycle becomes an aggregate over session lifecycles.

## 5. Implementation Notes

Migration must preserve existing demo reservations/tickets by linking them to the generated initial session. No service may infer a session from only the event title/date after migration.
