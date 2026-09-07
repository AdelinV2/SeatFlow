# TASK-P13-008: Rebind Analytics Fixtures to Real Scan/Refund/Revocation Producers

## 1. Task Metadata

- **Task ID:** `TASK-P13-008`
- **Git Branch:** `feat/p13-008-analytics-producer-rebind` (from `develop`)
- **Target Module:** `backend/services/ticket-service`, `backend/services/payment-service`, `backend/services/reservation-service`, `backend/services/analytics-service` (test sources only for the rebind)
- **Phase:** `Phase 13 - Refunds & Ticket Cancellation` (exit dependency for `TASK-P14-007` REV-001)
- **Related Specs:** `.ai/tasks/phase-13-refunds-ticket-cancellation/000-phase-overview.md`, `.ai/architecture/05-messaging-and-outbox.md`, `.ai/decisions/ADR-012-refund-cutoff-and-ticket-revocation.md`, `.ai/decisions/ADR-013-analytics-event-driven-read-model.md`
- **Related Tasks:** `TASK-P14-007` (blocked P1 REV-001 scope gap), Phase 13 `001`-`007` (producers land first)
- **Status:** `READY FOR IMPLEMENTATION`

### Orchestration Metadata

- **Complexity:** `4`
- **Failure Risk:** `High`
- **Verification Strength:** `Maximum`
- **Required Review Depth:** `Substantive + independent QA`
- **Preferred Workflow:** `critical`
- **Affected Critical Invariants:** `financial correctness; refund idempotency; scan attendance uniqueness; revocation correctness; transactional outbox; no double booking side effects`

---

## 2. Objective

Close the `TASK-P14-007` REV-001 prerequisite gap without inventing producer behavior inside Phase 14.

When Phase 13 lands the real scan/refund/revocation outbox producers, replace the five synthetic analytics builders with real producer serialization and prove producer-to-broker-to-projection contracts end to end:

- `reservationRefunded` / `ReservationRefunded`
- `paymentRefunded` / `PaymentRefunded`
- `revokedForTicket` / `revokedForReservation` / `TicketRevoked`
- `scanned` / `scannedAs` / `TicketScanned` / `TicketValidated`

Until then, `TASK-P14-007` scan/refund/revocation coverage stays explicitly reducer-level by supervisor scope decision (see P14-007 §4 scope note); this task is the concrete rebind exit dependency.

---

## 3. Prerequisites

- Phase 13 `001`-`007` implemented: refund eligibility/24h policy, Stripe Test Mode refund idempotency, refund Kafka choreography/recovery, ticket revocation + scanner states, refund UI/notifications.
- Real outbox emission exists for: accepted ticket scan/validation, completed payment refund, reservation refund evidence, ticket revocation.
- `TASK-P14-007` merged (reducer-level coverage + producer-bound proof for the 7 existing families + broker replay/order/catch-up/duplicate proofs).

---

## 4. Work Items

1. For each of the 5 synthetic families, replace `AnalyticsEnvelopeFactory` builders with real producer record/outbox payload builders (no hand-shaped JSON that can drift from producers).
2. Extend `AnalyticsProducerEnvelopeContractTest` to the newly landed families: parse + project + strictness (dropped required field fails validation, never projects).
3. Feed the exact producer-published envelopes through the real analytics listener (`AnalyticsEventConsumer` + P14-002 error handler) on the real broker, not only through `ProjectionEventProcessor` directly.
4. Re-run broker replay (`AnalyticsBrokerReplayIntegrationTest`) and outage catch-up (`AnalyticsOutageCatchUpIntegrationTest`) unchanged against the rebound retained set; full normalized snapshot equality (including every `SES|` source watermark) must hold.
5. Update `AnalyticsCanonicalFixtures`/`AnalyticsEnvelopeFactory` javadocs: remove the reducer-only labels for families that are now producer-bound.
6. Keep all existing P14-007 proofs green (duplicate/concurrent/rollback/redelivery/DLQ/grain/API/CSV/filter/isolation/frontend).

---

## 5. Acceptance Criteria

- [ ] Changing/removing a scan/refund/revocation producer event type or required field fails a contract test.
- [ ] Real broker replay + catch-up pass on the rebound retained set with full snapshot equality.
- [ ] No synthetic builder remains for any family that production emits.
- [ ] Independent review + final QA pass with no unresolved blocker.

---

## 6. Verification Commands

```bash
cd backend
mvn -pl services/analytics-service -am test
mvn -pl services/event-service,services/reservation-service,services/payment-service,services/ticket-service,services/analytics-service,services/api-gateway -am test
cd ../frontend
npm test -- --watch=false
npm run build
bash ../infra/scripts/verify-analytics-isolation.sh
```
