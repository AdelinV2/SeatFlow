# ADR-003: Hybrid Event Lifecycle Auto-Completion and Public Access Guarding

- **Date:** 2026-08-25
- **Author(s):** SeatFlow Architecture Team
- **Driven by Task:** TASK-P03-006 / Event Lifecycle Architectural Hardening
- **Supersedes:** N/A

## 1. Status
`ACCEPTED`

## 2. Context
In SeatFlow, events progress through an explicit lifecycle: `DRAFT` → `PUBLISHED` → `COMPLETED` (or `CANCELLED`).

Under the baseline specification:
1. Public event search (`GET /api/events`) filters out past events dynamically using `WHERE status = 'PUBLISHED' AND event_date > now()`.
2. However, single-event public retrieval (`GET /api/events/{eventId}`) and priced seat-map inspection (`GET /api/events/{eventId}/seat-map`) only verified `status == PUBLISHED`. Consequently, direct URL access to a past event whose start timestamp had already passed would still return active ticket pricing and venue layout.
3. In PostgreSQL, past events remained in `status = 'PUBLISHED'` indefinitely unless an administrator manually executed a `PUT /api/admin/events/{eventId}` transition to `COMPLETED`. This causes data drift in historical administrative queries, reporting, and downstream services.
4. Downstream checkout operations in `reservation-service` require guaranteed protection against holding seats for past events.

## 3. Decision
We adopt a **Hybrid Architectural Approach (Option 3)** combining immediate fail-fast access guards with an asynchronous scheduled outbox reconciliation job:

1. **Fail-Fast Access Guards at Service & Boundary Layer:**
   - Public methods `getPublishedEvent(eventId)` and `getEventSeatMap(eventId)` in `EventServiceImpl` must verify both:
     - `event.getStatus() == EventStatus.PUBLISHED`
     - `event.getEventDate().isAfter(Instant.now())`
   - If `eventDate <= Instant.now()`, the service throws `ResourceNotFoundException("Event", eventId)` to prevent exposing past event details or seat maps to public callers.
   - `AdminEventController` and administrative service methods (`getEventForAdministration`) remain unobstructed, allowing administrators full access to inspect past and completed events.
   - `reservation-service` (Phase 04) enforces `eventDate > Instant.now()` when authoritatively creating seat holds.

2. **Automated Event Completion Scheduler (`EventCompletionScheduler`):**
   - `event-service` introduces a scheduled background sweeper (`@Scheduled(cron = "${event.completion.cron:0 */15 * * * *}")`) running every 15 minutes by default.
   - The scheduler queries published events whose `event_date <= :now` using `SELECT ... FOR UPDATE SKIP LOCKED` (or atomic batch pagination) to ensure safe, idempotent execution across multiple concurrent `event-service` replicas.
   - It transitions matching events to `EventStatus.COMPLETED`, updates `updated_at = Instant.now()`, and persists an `EventCompletedEvent` domain envelope into `outbox_events` in the same database transaction.

3. **EventCompleted Domain Event (`seatflow.event.events`):**
   - Outbox event type: `EVENT_COMPLETED`
   - Payload: `EventCompletedEvent(UUID eventId, UUID venueId, String title, Instant eventDate, Instant completedAt)`
   - Allows downstream microservices (e.g. `notification-service` for post-event surveys or `ticket-service` for lifecycle closure) to react asynchronously.

4. **Database Index Utilization:**
   - The existing compound B-Tree index `idx_events_status_date ON events(status, event_date ASC)` in `seatflow_event` directly satisfies the sweeper query (`WHERE status = 'PUBLISHED' AND event_date <= :now`) with optimal $O(\log N)$ performance.

## 4. Alternatives Considered
1. **Manual Administrative Transition Only (Baseline):**
   - *Pros:* Zero background job overhead.
   - *Cons:* Data inconsistency in database; requires manual human operations; direct link lookup could leak past events.
   - *Reason for rejection:* Unacceptable for an automated enterprise ticketing platform.

2. **Dynamic Query Filtering Only (No Database Status Change):**
   - *Pros:* Simple to implement; no `@Scheduled` worker.
   - *Cons:* Database state remains permanently `PUBLISHED` for events years in the past; no domain event is emitted when an event finishes.
   - *Reason for rejection:* Causes reporting skew and deprives downstream services of completion triggers.

3. **Hybrid Dynamic Guard + Scheduled Outbox Reconciliation (Chosen):**
   - *Pros:* Immediate sub-second protection for customer endpoints and reservation checkout + clean, eventual consistency in database state with Kafka domain event emission.
   - *Cons:* Requires a periodic scheduled job and transaction management.
   - *Reason for choice:* Delivers optimal consistency, zero customer-facing race conditions, and complete architectural parity with `reservation-service`'s hold sweeper.

## 5. Consequences
### Positive:
- Customers can never view seat maps, pricing, or initiate seat reservations for expired events.
- Database state reflects true business lifecycle without human intervention.
- Emits `EventCompletedEvent` to Kafka for downstream event-driven workflows.
- Safe for multi-instance horizontal scaling via `SKIP LOCKED` / transactional boundaries.

### Negative / Trade-offs:
- Small background compute overhead for the 15-minute polling job.
  - *Mitigation:* Indexed lookup on `(status, event_date)` makes the query execution negligible (sub-millisecond when no events need updating).

## 6. Implementation Notes
- **Impacted Microservices:** `event-service` (Task `TASK-P03-006`), `reservation-service` (Phase 04 hold validation).
- **Impacted Architecture Documents:** `02-microservices-spec.md`, `03-database-models.md`, `05-messaging-and-outbox.md`, `06-api-contracts.md`, `SeatFlow-Architecture-and-Implementation-Spec.md`.
