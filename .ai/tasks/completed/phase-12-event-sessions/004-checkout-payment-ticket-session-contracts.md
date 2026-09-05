# TASK-P12-004: Propagate Exact Session Identity Through Checkout, Payment, Ticketing, and Notifications

## 1. Task Metadata

- **Task ID:** `TASK-P12-004`
- **Git Branch:** `feat/p12-004-session-checkout-ticket-contracts`
- **Target Module:** `backend/services/reservation-service`, `payment-service`, `ticket-service`, `notification-service`, shared event contracts where applicable
- **Phase:** `Phase 12 - Multiple Event Sessions / Showings`
- **Related ADRs:** `ADR-011-event-sessions-booking-boundary.md`
- **Status:** `READY FOR IMPLEMENTATION`

### Orchestration Metadata

- **Complexity:** `5`
- **Failure Risk:** `Critical`
- **Verification Strength:** `Strong`
- **Required Review Depth:** `Critical`
- **Preferred Workflow:** `critical`
- **Affected Critical Invariants:** `Payment enforcement; idempotency; transactional outbox; exact ticket identity; immutable audit snapshot`

---

## 2. Objective

Carry `eventSessionId` and the exact selected showing schedule from confirmed reservation through payment, ticket issuance, and user notifications. A ticket/receipt/email must never display a different event session because the parent event or session was later edited.

---

## 3. Critical Invariants & Failure Modes

- [ ] Payment creation is tied to reservation identity and its stored `eventSessionId`; client cannot substitute session metadata.
- [ ] Payment amount/currency remains server-derived and existing Stripe/webhook idempotency remains unchanged.
- [ ] Ticket issuance occurs only for the exact confirmed/paid reservation according to the current payment state machine.
- [ ] Ticket persists an immutable session snapshot sufficient to render the showing later: `eventSessionId`, `eventId`, `startsAt`, `endsAt`, and optional timezone/display metadata.
- [ ] Notification payloads are derived from persisted reservation/ticket snapshot, not a mutable client body.
- [ ] Kafka events continue to use transactional outbox and `EventEnvelope` conventions.
- [ ] Duplicate/retried payment webhooks cannot create duplicate tickets or notifications.

Failure modes: wrong showing on ticket, missing session after retry, event edit changes an issued ticket, duplicate ticket on duplicate webhook, consumer reads old event-only payload and silently assumes a session, event/session spoofing in checkout.

---

## 4. Dependencies / Prerequisites

- P12-003 session-aware reservation lifecycle events.
- Existing payment webhook/idempotency, ticket issuance, notification consumers and outbox behavior.

---

## 5. Exact File Inventory

Before editing, inventory all producers/consumers of reservation/payment/ticket events. Expected touched areas:

- `reservation-service`: `ReservationConfirmedEvent` and any checkout/payment initiation response/client contract.
- `payment-service`: reservation client DTOs, payment entity/audit fields if event/session identity is persisted, payment-created/completed event payloads, relevant consumers/producers.
- `ticket-service`: ticket entity + Flyway migration for session snapshot fields, reservation/payment consumer DTOs, ticket mapper/API/QR payload if showing metadata is encoded.
- `notification-service`: ticket/reservation/payment event DTOs, template model, email/in-app rendering.
- Corresponding contract/unit/Testcontainers/Kafka integration tests in each service.

If a cross-service payload lives in `backend/common/common-events`, modify that canonical type rather than creating divergent service-local copies.

---

## 6. Technical Specifications & Contracts

### 6.1 Canonical Session Snapshot

At the point reservation becomes confirmation/payment-eligible, capture from trusted backend state:

```text
eventSessionId UUID
eventId UUID
sessionStartsAt offset-aware instant
sessionEndsAt offset-aware instant
sessionTimezone nullable IANA ZoneId metadata
```

Event title/venue/hall/seat labels may continue using existing ticket snapshot fields. The authoritative showing timestamps must not be lazily looked up from mutable event state at ticket render time.

### 6.2 Payment Contract

- Checkout/payment endpoint takes reservation/offer identity under existing idempotency rules; it must not take free-form event/session timestamps.
- Payment service may store `eventSessionId` for audit/correlation, but payment authorization remains based on server-derived reservation amount/currency.
- Stripe metadata may include `reservationId` and `eventSessionId` if within current policy, but webhook processing must correlate primarily through trusted persisted IDs and verify existing invariants; metadata is not authorization.

### 6.3 Event Compatibility Window

During P12-004, producers must include `eventSessionId` while retaining `eventId` only where older consumers still require it. All consumers in scope must be migrated before P12-007. Do not publish two independent events that can diverge; evolve the canonical payload once and update consumers atomically per dependency order.

### 6.4 Ticket Contract

Ticket persistence/API includes exact session identity and snapshot. QR validation/lookup remains ticket/reservation-based; no public QR payload should require a live session lookup to know which showing it represents.

### 6.5 Notification Contract

Subject/body/date rendering uses the ticket/reservation session snapshot. Date/time formatting must use the stored timezone when present; when absent, render the stored offset-aware instant according to the existing product display convention and do not invent a venue timezone.

---

## 7. Step-by-Step Implementation Sequence

1. Build producer/consumer contract matrix for reservation -> payment -> ticket -> notification.
2. Extend canonical payloads additively with session identity/snapshot.
3. Update payment persistence/DTO/consumers without weakening amount/idempotency checks.
4. Add ticket schema migration and persist immutable session snapshot.
5. Update ticket API/QR/rendering.
6. Update notifications/templates.
7. Add retry/duplicate/out-of-order consumer tests.
8. Run all four service suites and Kafka/Testcontainers contract tests.

---

## 8. Test Requirements

- [ ] Reservation for session A produces payment/ticket/notification containing session A ID/times.
- [ ] Editing a still-editable event title or unrelated catalog metadata does not change the stored showing time on an already issued ticket.
- [ ] Session B for same event never appears on session A ticket.
- [ ] Duplicate Stripe/payment-completed event remains idempotent: one payment transition, one ticket logical identity, no duplicate notification side effect beyond current dedupe contract.
- [ ] Consumer rejects/parks malformed message missing required `eventSessionId` after cutover policy; it must not infer from `eventId`.
- [ ] Existing payment amount/currency tamper tests remain green.
- [ ] Outbox atomicity tests remain green.

---

## 9. Verification Commands

```bash
cd backend
./mvnw -pl services/reservation-service,services/payment-service,services/ticket-service,services/notification-service -am test
```

---

## 10. Independent Review Focus

Money correctness, webhook/event idempotency, immutable session snapshot source, consumer compatibility, and absence of mutable-event lookup when rendering issued tickets/notifications.

---

## 11. Acceptance Criteria

- [ ] Session identity is end-to-end through checkout/payment/ticket/notification.
- [ ] Tickets preserve exact showing schedule independently of later mutable catalog state.
- [ ] Payment and outbox/idempotency invariants are unchanged or strengthened.
- [ ] Duplicate/retry paths are proven safe.
- [ ] Critical independent review and final QA pass.

---

## 12. Execution Entry Point

```text
Implement TASK-P12-004 using the SeatFlow autonomous orchestration workflow.
```
