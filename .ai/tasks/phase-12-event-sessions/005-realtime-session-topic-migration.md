# TASK-P12-005: Isolate Realtime Seat Updates by Event Session

## 1. Task Metadata

- **Task ID:** `TASK-P12-005`
- **Git Branch:** `feat/p12-005-session-realtime-topics`
- **Target Module:** `backend/services/realtime-service`, reservation event contracts, frontend realtime client where contract coupling requires it
- **Phase:** `Phase 12 - Multiple Event Sessions / Showings`
- **Related ADRs:** `ADR-011-event-sessions-booking-boundary.md`
- **Status:** `READY FOR IMPLEMENTATION`

### Orchestration Metadata

- **Complexity:** `4`
- **Failure Risk:** `High`
- **Verification Strength:** `Strong`
- **Required Review Depth:** `Substantive`
- **Preferred Workflow:** `critical`
- **Affected Critical Invariants:** `Session inventory isolation; PostgreSQL remains authority; realtime is fan-out only`

---

## 2. Objective

Migrate realtime seat availability fan-out from event-scoped topics to explicit session-scoped topics so updates from two showings of the same event can never cross-feed.

---

## 3. Critical Invariants & Failure Modes

- [ ] Canonical STOMP destination is unambiguously session-scoped: `/topic/sessions/{eventSessionId}/seats`.
- [ ] Every pushed payload contains `eventSessionId` and `seatId`/current status data required by the client.
- [ ] Destination identity is derived from trusted reservation lifecycle event state, not arbitrary client-provided `eventId`.
- [ ] A subscriber to session A receives no events from session B, even when `eventId`, hall, and seat IDs are identical.
- [ ] WebSocket updates remain hints/fan-out; client reconciliation still uses REST/PostgreSQL-authoritative availability after reconnect/gaps.
- [ ] Old event-scoped destination is compatibility-only during migration and must not remain active after P12-007.

Failure modes: topic collision, stale subscription after user switches sessions, reconnect resubscribes old session, event consumer missing session ID, duplicate Kafka events causing harmful client state.

---

## 4. Dependencies / Prerequisites

- P12-003 lifecycle events carry `eventSessionId`.
- Existing realtime Kafka consumer/STOMP broker conventions understood.

---

## 5. Exact File Inventory

- Realtime-service reservation lifecycle event DTOs/consumers.
- Realtime seat update DTO/model.
- WebSocket messaging publisher/destination builder.
- Security/config only if destination authorization currently parses event IDs.
- Realtime unit/integration tests.
- Frontend realtime subscription service tests only as needed to keep the contract compiling; full UX switching is P12-006.

Do not introduce a second parallel source of seat truth in realtime-service.

---

## 6. Technical Specifications & Contracts

### 6.1 Destination

```text
/topic/sessions/{eventSessionId}/seats
```

No route named `/topic/events/{sessionId}/...` is allowed because it preserves semantic ambiguity.

### 6.2 Payload

At minimum:

```text
eventSessionId: UUID
seatId: UUID
status: existing seat availability enum/state
reservationId/hold expiry: only if already safe/required by current contract
version/event timestamp: retain existing ordering/reconciliation fields if present
```

Do not expose another user's private reservation/customer data in a public seat topic.

### 6.3 Consumer Behavior

- Validate/deserialize required session ID.
- Preserve existing idempotent/retry semantics.
- Duplicate messages may repeat the same public seat state but must not mutate authoritative booking state.
- Malformed legacy event-only messages after consumer cutover are observable failures/DLQ according to current Kafka policy; never route them to an inferred session.

### 6.4 Frontend Reconciliation Contract

When session selection changes, the client must unsubscribe old destination before/while subscribing new one and perform an authoritative REST refresh for the new session. On reconnect it subscribes only the currently selected session and refreshes availability.

---

## 7. Step-by-Step Implementation Sequence

1. Inventory current event-scoped destination and producer payload.
2. Add required `eventSessionId` to consumer model.
3. Replace destination builder with session path.
4. Update realtime tests with two session IDs sharing same event/seat IDs.
5. Update minimal frontend subscription contract to compile; full state UX in P12-006.
6. Add reconnect/isolation integration tests.

---

## 8. Test Requirements

- [ ] Session A message goes only to `/topic/sessions/A/seats`.
- [ ] Session B message goes only to `/topic/sessions/B/seats`.
- [ ] Same seat UUID in A/B does not collide.
- [ ] event-only message is not inferred/routed after cutover.
- [ ] reconnect/current-session refresh contract is covered.
- [ ] no private customer/payment data is added to public WebSocket payload.

---

## 9. Verification Commands

```bash
cd backend
./mvnw -pl services/realtime-service -am test
cd ../frontend
npm test -- --watch=false
```

Use repository-standard Angular test/typecheck commands if different.

---

## 10. Independent Review Focus

Topic construction, consumer trust boundary, cross-session isolation, reconnect/stale-subscription behavior, and ensuring realtime never becomes availability authority.

---

## 11. Acceptance Criteria

- [ ] Realtime destinations and payloads are session-explicit.
- [ ] Cross-session leakage test passes.
- [ ] Old event-scoped topic has a documented removal point in P12-007 and no new consumers depend on it.
- [ ] Substantive review and QA pass.

---

## 12. Execution Entry Point

```text
Implement TASK-P12-005 using the SeatFlow autonomous orchestration workflow.
```
