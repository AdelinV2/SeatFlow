# TASK-P15-005: Implement Explicit Confirmation and Reservation Tool Boundary

## 1. Task Metadata

- **Task ID:** `TASK-P15-005`
- **Git Branch:** `feat/p15-005-confirmed-reservation-tool`
- **Target Module:** `backend/services/ai-service`
- **Phase:** `Phase 15 - AI Assistant & Controlled Tool Calling`
- **Depends On:** `TASK-P15-004`
- **Related Specs:** `.ai/tasks/phase-15-ai-assistant-mcp/000-phase-overview.md`, `.ai/architecture/09-post-mvp-evolution.md`
- **Related ADRs:** `.ai/decisions/ADR-014-ai-assistant-tool-orchestration.md`
- **Status:** `READY FOR IMPLEMENTATION`

### Orchestration Metadata

- **Complexity:** `5`
- **Failure Risk:** `Critical`
- **Verification Strength:** `Strong`
- **Required Review Depth:** `Critical`
- **Preferred Workflow:** `critical`
- **Affected Critical Invariants:** `explicit human confirmation; zero autonomous purchase; authorization; idempotency; session isolation; live revalidation; 10-seat limit; 15-minute hold; no double booking`

---

## 2. Objective

Add the only Phase 15 state-changing AI capability: creation of a normal SeatFlow reservation hold after the authenticated user explicitly confirms one exact server-side proposal.

This task must make it technically impossible for an ordinary model-generated tool call or free-form chat message to bypass the confirmation boundary.

Payment remains completely outside the AI tool set.

---

## 3. Authoritative Confirmation Flow

Use a dedicated application endpoint/action rather than treating model prose as authorization.

Recommended flow:

```text
/chat -> findBestSeats -> server proposal
                 |
                 v
       CONFIRMATION_REQUIRED
                 |
       user sees exact card
                 |
       clicks Confirm reservation
                 |
                 v
POST /api/ai/proposals/{proposalId}/confirm
                 |
     validate proposal ownership/TTL
                 |
     re-read live availability + price
                 |
     exact proposal still valid?
        /                     \
      no                       yes
      |                         |
 STALE_PROPOSAL          createReservation
                                |
                        Reservation Service
                                |
                        normal 15-minute hold
                                |
                     RESERVATION_CREATED
                                |
                     normal checkout link
```

A chat message such as `yes`, `confirm`, or `book it` **must not directly create a reservation**. It may cause the assistant to re-present the confirmation card, but the state-changing request requires the dedicated confirmation action carrying the server-issued proposal ID.

---

## 4. Secure Proposal Store

Phase 15 does not introduce a persistent AI database. Use a bounded server-side proposal store appropriate for the current single-runtime portfolio deployment.

Recommended implementation:

- application-owned in-memory `ProposalStore`;
- random UUID/opaque proposal ID;
- bound to authenticated subject/user ID and conversation ID;
- explicit creation timestamp and short expiration timestamp;
- bounded max entries + scheduled/lazy cleanup;
- one active proposal per conversation by default;
- consumed/superseded proposals cannot be reused.

Required proposal fields:

```text
ReservationProposal
- proposalId
- conversationId
- ownerSubject
- eventId
- eventSessionId
- seatIds[]                 // exact stable IDs
- seat display snapshot[]
- pricingTierIds[]?         // when current booking API needs them
- totalPriceMinor
- currency
- createdAt
- expiresAt
- status: ACTIVE | CONSUMED | SUPERSEDED | EXPIRED
- serverIdempotencyKey
```

Do not store raw JWT, Groq key, payment token, card data, or model chain-of-thought.

A service restart may invalidate proposals; this is acceptable. The UI must ask the user to request a fresh proposal instead of reconstructing authorization from chat text.

---

## 5. Proposal TTL

Use a short confirmation TTL independent of the 15-minute reservation hold.

Recommended default:

```text
AI_PROPOSAL_TTL=5m
```

The exact value is configurable but must be bounded and shorter than or equal to a reasonable conversational window.

Important distinction:

- proposal TTL = how long a recommendation may be confirmed;
- reservation TTL = the existing authoritative 15-minute hold created only after successful reservation.

Never display the proposal TTL as if seats are held.

---

## 6. Confirmation Authorization

Before any state-changing call:

1. requester is authenticated;
2. requester owns the proposal;
3. conversation ID/subject association is valid;
4. proposal status is `ACTIVE`;
5. proposal has not expired;
6. proposal has not been superseded/consumed;
7. exact seat IDs/session/price snapshot are server-side values; client cannot replace them in confirmation request.

Confirmation request should contain no editable seat list or price. Prefer:

```text
POST /api/ai/proposals/{proposalId}/confirm
body: empty or { expectedVersion/proposalToken if explicitly needed }
```

Never trust a client body like `{seatIds:[...], price:...}` for this boundary.

---

## 7. Live Revalidation Before Reservation

A proposal is not a lock. Immediately before calling Reservation Service:

1. re-fetch current session state/bookability if required;
2. re-fetch current seat availability;
3. confirm every proposed seat is still `AVAILABLE` and active;
4. re-resolve current pricing using the same pricing-tier/category semantics as P15-003;
5. recompute total in minor units;
6. require same currency;
7. require quantity still `1..10`;
8. verify the current exact proposal still satisfies the user's originally stored hard constraints (budget/category where stored);
9. if price or seat availability changed, return `STALE_PROPOSAL` and require a fresh proposal.

Do not silently substitute different seats during confirmation. Seat substitution requires a new visible proposal and a new explicit confirmation.

---

## 8. Reservation Service Call

Implement the state-changing operation as application-owned code, not an unrestricted LLM tool available to normal chat.

The logical capability may still be named `createReservation`, but it is invoked only from the confirmed proposal handler.

Call the current canonical Reservation Service API through Eureka/LoadBalancer and propagate:

- authenticated user's Bearer JWT;
- `X-Correlation-Id`;
- exact `eventSessionId`;
- exact server-side proposed seat IDs;
- server-derived price inputs if the current Reservation API still requires `seatPrices`;
- server-generated idempotency key.

If pricing-tier selection requires a follow-up current API call, use the existing canonical reservation-pricing endpoint only after the hold is created and preserve error/rollback semantics defined by Reservation Service. Prefer existing atomic contracts when available; do not create a new cross-service transaction in AI Service.

The Reservation Service remains authoritative for:

- user/session ownership;
- 10-seat rule;
- 15-minute expiry;
- concurrency/double-booking prevention;
- idempotency;
- reservation status.

AI Service must not reproduce or weaken those guarantees.

---

## 9. Idempotency

Generate one server-side idempotency key when the proposal is created, not on every confirmation retry.

Rules:

- repeated confirm requests for the same active proposal must resolve to the same reservation result where Reservation Service's idempotency contract allows;
- browser retry/network duplicate must not create two holds;
- proposal should move to `CONSUMED` only after a successful reservation result is known;
- if response is lost after downstream success, retry with the same idempotency key and reconcile from Reservation Service rather than generating a new key;
- definitive validation/stale failures leave no reservation and transition proposal appropriately.

Add tests for the ambiguous timeout-after-submit case.

---

## 10. Failure Contract

Canonical confirmation errors:

```text
PROPOSAL_NOT_FOUND
PROPOSAL_FORBIDDEN
PROPOSAL_EXPIRED
PROPOSAL_SUPERSEDED
PROPOSAL_ALREADY_CONSUMED
STALE_PROPOSAL
SEATS_NO_LONGER_AVAILABLE
PRICE_CHANGED
SESSION_NOT_BOOKABLE
RESERVATION_CONFLICT
RESERVATION_SERVICE_UNAVAILABLE
RESERVATION_RESULT_UNKNOWN_RETRY_SAFE
```

Rules:

- `409` seat conflict -> no alternative seat auto-substitution;
- price changed -> show fresh price only through a new proposal;
- downstream timeout after request may have been accepted -> preserve idempotency key and return retry-safe state;
- never tell the user a reservation exists unless authoritative Reservation Service response/reconciliation confirms it.

---

## 11. Successful Response

On success return a structured result based on Reservation Service data:

```text
ReservationCreatedCard
- reservationId
- eventSessionId
- seatIds / display labels
- totalAmount (authoritative reservation response)
- currency when available
- reservationStatus
- expiresAt               // authoritative 15-minute hold expiry
- checkoutUrl/route       // normal SeatFlow route, not provider URL invented by model
```

The assistant may add concise prose, but the card is authoritative.

Do not expose a payment tool. The user must continue through the existing checkout UI.

---

## 12. Guest Policy

Phase 15 state-changing reservation confirmation is authenticated-user only unless a later explicit task/ADR adds a secure guest identity/proof contract.

Do not copy the normal guest email-proof flow into the AI agent casually. Guest AI, if enabled in UI, remains read-only.

This avoids asking the LLM to process/store guest identity evidence.

---

## 13. Expected File Inventory

Create/adapt under `backend/services/ai-service`:

- `[NEW]` `proposal/ReservationProposal.java`
- `[NEW]` `proposal/ProposalStatus.java`
- `[NEW]` `proposal/ProposalStore.java`
- `[NEW]` bounded in-memory implementation + cleanup policy;
- `[NEW]` `service/ProposalService.java`
- `[NEW]` `client/ReservationServiceClient.java`
- `[NEW]` `service/ConfirmedReservationService.java`
- `[NEW]` `api/ReservationProposalController.java` or integrate safely with existing assistant controller;
- `[NEW]` confirmation/result DTOs;
- `[MODIFY]` orchestration from P15-004 to create secure stored proposals and return IDs;
- `[NEW]` focused concurrency/idempotency/security tests.

Do not add a database, payment client, or ADMIN tool.

---

## 14. Tests

Mandatory tests:

1. valid owner + active proposal + unchanged live state -> exactly one reservation call.
2. another user confirming proposal -> 403/no downstream call.
3. expired/superseded/consumed proposal -> no downstream call.
4. free-form chat `yes` cannot call Reservation Service.
5. client cannot alter seat IDs/price because confirmation body does not accept them.
6. one seat becomes held after proposal -> stale/conflict, no substitution.
7. price changes by one minor unit -> stale proposal/new confirmation required.
8. session becomes unbookable -> no reservation call.
9. quantity >10 is impossible at proposal boundary and rechecked defensively.
10. duplicate confirm requests reuse same idempotency key.
11. timeout-after-submit retry uses same key and reconciles safely.
12. successful result uses Reservation Service `expiresAt`, not AI-calculated 15 minutes.
13. no payment endpoint/tool can be reached from this flow.
14. proposal store is bounded and cleans expired entries.
15. service restart/lost proposal requires new proposal rather than trusting chat history.
16. JWT/API key never appears in proposal storage or returned DTO.

Use mocked downstream services in CI; add integration tests against Reservation Service contracts where practical.

---

## 15. Acceptance Criteria

- [ ] Only a dedicated explicit confirmation action can trigger reservation creation.
- [ ] Ordinary LLM/chat tool set still contains no state-changing reservation tool.
- [ ] Proposal is server-side, owner-bound, exact, TTL-bounded and non-editable by client.
- [ ] Availability and pricing are revalidated immediately before state change.
- [ ] Changed seats/price require a new proposal and new confirmation.
- [ ] Reservation Service remains authoritative for concurrency, idempotency and 15-minute hold.
- [ ] Duplicate confirmation cannot create duplicate holds.
- [ ] Success returns authoritative reservation ID/status/expiry.
- [ ] Payment remains completely manual/outside AI control.
- [ ] Guest state-changing flow is not introduced implicitly.
- [ ] Critical failure and ambiguous-timeout paths are test-covered.

---

## 16. Verification

```bash
cd backend
mvn -pl services/ai-service -am test
mvn verify
```

Manual end-to-end validation after a Groq key exists:

```text
ask for seats -> proposal card -> click explicit confirm -> reservation created -> normal checkout
```

Verify that typing `yes` without the explicit confirmation action does not create a hold.
