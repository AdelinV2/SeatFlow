# Phase 13 — Refunds & Ticket Cancellation

**Status:** `PLANNED`  
**Architecture:** `.ai/architecture/09-post-mvp-evolution.md`  
**Related ADR:** `.ai/decisions/ADR-012-refund-cutoff-and-ticket-revocation.md`  
**Estimated effort:** ~8–10 focused implementation hours  

---

## 1. Outcome

Implement a realistic, idempotent Stripe Test Mode refund workflow with one hard product rule: **customer refund is available only while at least 24 hours remain before the purchased event session starts**.

Initial scope is full-reservation refunds. No partial/per-ticket refunds.

## 2. Eligibility Rules

Reservation Service must enforce all of the following before accepting the workflow:

- authenticated user owns the reservation, or caller is ADMIN;
- reservation is `CONFIRMED` and paid;
- refund not already completed/pending;
- session exists and is not already completed;
- `session.startsAt - Instant.now() >= 24h`;
- amount/payment relationship is internally consistent.

Exactly 24h is eligible. Anything below is rejected with stable `REFUND_WINDOW_CLOSED`-style error.

Guest refund self-service is optional for this phase; if not implemented securely, guests are directed to demo support rather than creating a weak token flow.

## 3. Workflow State Machine

Recommended choreography:

1. Reservation endpoint accepts request after policy validation.
2. Reservation -> `REFUND_PENDING`; persist Outbox `RefundRequested`.
3. Payment Service consumes event and locates payment by reservation ID.
4. Payment Service calls Stripe Refund API in Test Mode using deterministic idempotency key.
5. On success, persist Stripe refund ID/status and Outbox `PaymentRefunded`.
6. Reservation consumes success -> `REFUNDED`; sold holds become `RELEASED`; publish seat status/reconciliation event.
7. Ticket Service consumes refund success -> all reservation tickets become `REVOKED`.
8. Realtime reflects seats as available again.
9. Notification Service sends refund confirmation.
10. On payment refund failure, publish `RefundFailed` and restore/retain a retryable consistent state without releasing seats or revoking valid tickets.

## 4. Persistence Changes

Reservation DB:
- new statuses `REFUND_PENDING`, `REFUNDED`;
- optional refund requested/completed timestamps.

Payment DB:
- refund status fields or dedicated `refunds` table;
- Stripe refund ID unique constraint;
- deterministic idempotency key;
- failure reason suitable for operations, without leaking secrets.

Ticket DB:
- canonical revoked/cancelled status and timestamp/reason.

## 5. API / UI

My Tickets or purchase detail exposes refund eligibility and reason. UI calculates display countdown for convenience but always handles backend rejection.

Flow:

- `Request refund` button;
- confirmation modal summarizing full reservation amount and 24h policy;
- processing state;
- success/failure result;
- revoked ticket visuals after success.

Scanner shows a clear red invalid/refunded state.

## 6. Kafka / Outbox Contracts

Add versioned domain events such as:

- `RefundRequested`
- `PaymentRefunded`
- `RefundFailed`
- `ReservationRefunded` / seat release event if separation improves consumers.

All consumers are idempotent; duplicate delivery cannot double-refund, double-release or duplicate notification.

## 7. Suggested Atomic Tasks

1. `001-refund-domain-states-error-codes-and-adr-wiring.md`
2. `002-refund-eligibility-api-and-24h-policy.md`
3. `003-payment-stripe-refund-idempotency.md`
4. `004-refund-kafka-choreography-and-recovery.md`
5. `005-ticket-revocation-scanner-and-seat-release.md`
6. `006-refund-ui-and-notifications.md`
7. `007-refund-integration-and-boundary-tests.md`

## 8. Critical Tests

- exactly 24:00:00 remaining => accepted;
- 23:59:59 remaining => rejected;
- duplicate request => one Stripe refund;
- Stripe failure => ticket remains valid and seat remains sold;
- success => ticket revoked and seat available;
- another user's reservation => forbidden;
- scanner rejects revoked ticket.

## 9. Definition of Done

- [ ] Server-side 24h policy is authoritative.
- [ ] Full reservation refund works in Stripe Test Mode.
- [ ] Workflow is durable and idempotent.
- [ ] Seats release only after refund success.
- [ ] All tickets in the refunded reservation are revoked.
- [ ] Scanner and frontend reflect final state.
- [ ] No partial refund complexity was added.
