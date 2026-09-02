# ADR-012: 24-Hour Refund Cutoff and Ticket Revocation Workflow

- **Date:** 2026-09-02
- **Status:** `PROPOSED`
- **Driven by:** Phase 13 — Refunds & Ticket Cancellation

## 1. Context

SeatFlow is a portfolio/demo application using Stripe Test Mode, but a realistic cancellation workflow is valuable for demonstrating distributed state transitions. The desired product rule is that customers may refund a purchase only when at least 24 hours remain before the concrete event showing.

## 2. Decision

Customer-initiated refunds are allowed only if:

`eventSession.startsAt - authoritativeServerNow >= 24 hours`.

The rule is enforced server-side by the refund entry-point service after ownership and reservation state validation. Frontend button visibility is not enforcement.

Initial scope supports full-reservation refunds only. Successful Stripe Test Mode refund leads to reservation `REFUNDED`, ticket revocation, and release of sold seats. Seats are not released while the refund is merely pending.

The cross-service workflow uses persisted states + Transactional Outbox/Kafka and must be idempotent.

## 3. Alternatives Considered

### Refund anytime before event start
Rejected because the intended product policy is explicitly 24 hours.

### Frontend-only cutoff
Rejected because clients cannot enforce business policy or authoritative time.

### Partial per-ticket refunds
Deferred because they add allocation, amount, ticket selection and partial order state complexity without enough portfolio value for the current scope.

## 4. Consequences

- Session support must exist before refunds.
- Reservation, Payment and Ticket state machines gain refund/revocation states.
- Scanner must reject refunded tickets.
- Realtime availability is updated only after refund success.
- Notification Service reports success/failure.

## 5. Boundary Semantics

Exactly 24 hours remaining is eligible. Less than 24 hours is rejected with a stable business error code such as `REFUND_WINDOW_CLOSED`. All comparisons use UTC instants/TIMESTAMPTZ internally and display localized times only at the UI boundary.
