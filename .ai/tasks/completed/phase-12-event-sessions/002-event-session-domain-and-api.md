# TASK-P12-002: Implement Event Session Domain and Organizer/Customer APIs

## 1. Task Metadata

- **Task ID:** `TASK-P12-002`
- **Git Branch:** `feat/p12-002-event-session-domain-api`
- **Target Module:** `backend/services/event-service`
- **Phase:** `Phase 12 - Multiple Event Sessions / Showings`
- **Related Specs:** `000-phase-overview.md`, `.ai/architecture/09-post-mvp-evolution.md`
- **Related ADRs:** `ADR-011-event-sessions-booking-boundary.md`
- **Status:** `READY FOR IMPLEMENTATION`

### Orchestration Metadata

- **Complexity:** `5`
- **Failure Risk:** `High`
- **Verification Strength:** `Strong`
- **Required Review Depth:** `Substantive`
- **Preferred Workflow:** `critical`
- **Affected Critical Invariants:** `Server-side authorization; explicit eventSessionId boundary; PostgreSQL source of truth`

---

## 2. Objective

Make `EventSession` a first-class event-service domain/API resource. Organizers can create/list/update/delete eligible sessions for events they own; customers can list only booking-visible sessions. Event detail/discovery remains event-centric. Every API that accepts both event and session identity must validate their relationship server-side.

---

## 3. Critical Invariants & Failure Modes

- [ ] A session always belongs to exactly one event and inherits that event's venue/hall/layout; Phase 12 must not add per-session halls.
- [ ] Organizer authorization is ownership-based on the parent event, not merely possession of a session UUID.
- [ ] Customer endpoints never expose sessions belonging to unpublished/ineligible events.
- [ ] `eventId + sessionId` mismatch is rejected; never fetch session by ID and ignore the path event ID.
- [ ] No endpoint may silently choose “first”, “next”, or “only” session for booking semantics.
- [ ] Mutations cannot alter a session after it has become booking-eligible in a way that would invalidate holds/tickets. Use the effective sales-open boundary as the local lock: once sales are open (or a published event has no future `saleStartsAt` and is immediately eligible), schedule/delete operations are rejected. This conservative rule avoids needing reservation-service state in event-service.
- [ ] `startsAt < endsAt`; sale window ordering enforced both at request validation/domain layer and DB.
- [ ] Event publication must require at least one future valid session.

Failure modes to test: IDOR ownership bypass, mismatched pair, invalid times, deletion after sales opened, mutation of past/completed session, publish with zero future sessions, accidental duplicate event rows in discovery, customer seeing draft sessions.

---

## 4. Dependencies / Prerequisites

- `TASK-P12-001` completed.
- Existing `Event`, `EventService`, `AdminEventController`, `EventController`, `SecurityConfig`, `EventCompletionScheduler` behavior understood.

---

## 5. Exact File Inventory

- `[NEW]` `.../service/EventSessionService.java`
- `[NEW]` `.../service/impl/EventSessionServiceImpl.java`
- `[NEW]` `.../mapper/EventSessionMapper.java`
- `[NEW]` `.../web/dto/request/CreateEventSessionRequest.java`
- `[NEW]` `.../web/dto/request/UpdateEventSessionRequest.java`
- `[NEW]` `.../web/dto/response/EventSessionResponse.java`
- `[NEW]` `.../web/controller/EventSessionController.java`
- `[MODIFY]` `.../service/impl/EventServiceImpl.java`
- `[MODIFY]` `.../web/controller/AdminEventController.java` only if publication endpoint lives there and requires the new session precondition
- `[MODIFY]` `.../web/controller/EventController.java` only for customer session listing/detail linkage
- `[MODIFY]` `.../web/dto/response/EventDetailResponse.java` as specified below
- `[MODIFY]` `.../scheduler/EventCompletionScheduler.java` so completion derives from all relevant sessions rather than legacy event `endsAt`
- `[NEW/MODIFY]` corresponding mapper/service/controller/repository/scheduler/integration tests.

Do not remove legacy `startsAt`/`endsAt` from `Event` or create/update DTOs until P12-007.

---

## 6. Technical Specifications & Contracts

### 6.1 Organizer API

Canonical organizer routes:

- `POST /api/events/{eventId}/sessions`
- `GET /api/events/{eventId}/sessions` (organizer view includes all statuses for owned event)
- `PUT /api/events/{eventId}/sessions/{sessionId}`
- `DELETE /api/events/{eventId}/sessions/{sessionId}`

Use existing API prefix conventions if gateway/controller already supplies `/api`; do not accidentally double-prefix.

Create request fields:

```text
startsAt: offset-aware instant, required
endsAt: offset-aware instant, required
saleStartsAt: optional offset-aware instant
saleEndsAt: optional offset-aware instant
timezone: optional valid IANA ZoneId string
```

Client must not set `eventId`, `id`, audit fields, `legacyBackfill`, or an arbitrary status. Server derives parent/event and initial status.

Update request may change the same mutable schedule fields only while the session is locally unlocked. Status transitions must be owned by explicit domain rules/schedulers, not free-form strings.

Success/error semantics:

- create: `201` + created response;
- list/read: `200`;
- update: `200`;
- delete: `204`;
- malformed/temporal validation: established shared `400` domain error;
- not found or parent/session mismatch: established not-found contract without leaking another organizer's object;
- forbidden ownership: established `403` where repository conventions expose existence safely;
- locked session mutation: `409` domain conflict.

### 6.2 Customer API

- `GET /api/events/{eventId}/sessions`

Customer response returns only sessions that belong to a customer-visible event and are eligible to be shown. Past/completed sessions may be omitted from the booking list unless product detail explicitly needs history; booking selection MUST contain only future/non-cancelled sessions whose sale window permits the appropriate UI state.

`EventDetailResponse` may include a sessions summary or link-ready session list, but event discovery/search must continue to return one item per event, not one row per session.

### 6.3 Internal Validation Contract

Add an event-service operation used by reservation-service in P12-003:

```text
resolveSession(sessionId) -> session + parent event booking facts
```

Expose it through the existing authenticated inter-service convention, preferably a clearly internal endpoint such as `GET /internal/event-sessions/{sessionId}/booking-context` if repository architecture uses internal REST. Response must include at minimum:

- `eventSessionId`
- `eventId`
- `eventStatus`
- `sessionStatus`
- `startsAt`, `endsAt`, sale boundaries
- `venueId`, `hallId`
- data already required for pricing/seat-map validation

The server, not reservation-service client input, derives `eventId` from the session.

### 6.4 Event Lifecycle

- Event publish requires >=1 future valid session.
- Event completion occurs only when every non-cancelled session is ended/completed; the scheduler must not complete an event after its first showing.
- Event cancellation semantics remain event-level as currently implemented; introducing session-specific cancellation/refunds is out of scope.

---

## 7. Step-by-Step Implementation Sequence

1. Add DTOs and mapper.
2. Add service contract and implementation with ownership and lock checks centralized in service layer.
3. Add controller routes and security rules.
4. Add booking-context/internal validation endpoint using existing service-to-service auth pattern.
5. Update event publish validation.
6. Update completion scheduler/repository queries to session-aware semantics.
7. Update event detail/customer session exposure without duplicating search results.
8. Add unit, MVC/security, repository, and Testcontainers integration tests.

---

## 8. Test Requirements

- [ ] Organizer creates two sessions on one event and receives distinct IDs.
- [ ] Non-owner cannot mutate/list organizer-only session state.
- [ ] `{eventA}/sessions/{sessionB}` is rejected when B belongs to event B.
- [ ] invalid time/sale windows are rejected.
- [ ] update/delete after sales-open lock returns conflict.
- [ ] customer cannot list sessions for draft/private event.
- [ ] event publish with zero future sessions fails; succeeds with one valid future session.
- [ ] completion scheduler does not complete event while a later session remains.
- [ ] internal booking context derives parent event server-side and returns exact selected session times.
- [ ] legacy backfilled event remains visible and maps to its one session.

---

## 9. Verification Commands

```bash
cd backend
./mvnw -pl services/event-service -am test
```

Expected: all event-service tests pass including security/integration/lifecycle cases.

---

## 10. Independent Review Focus

Ownership/IDOR, event-session pair validation, lifecycle locking, customer visibility, event completion with multiple sessions, and absence of implicit “default session” booking behavior.

---

## 11. Acceptance Criteria

- [ ] Organizer and customer contracts are implemented exactly.
- [ ] Event remains catalog boundary; session is schedule boundary.
- [ ] At least two sessions can coexist on one event.
- [ ] Publication/completion semantics are session-aware.
- [ ] Security and mismatch tests pass.
- [ ] No legacy event schedule field is removed yet.
- [ ] Substantive independent review and final QA pass.

---

## 12. Execution Entry Point

```text
Implement TASK-P12-002 using the SeatFlow autonomous orchestration workflow.
```
