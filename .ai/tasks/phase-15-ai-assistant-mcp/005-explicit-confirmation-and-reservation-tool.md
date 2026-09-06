# TASK-P15-005: Implement Reservation Lookup, Explicit Confirmation, and Reservation Tool Boundary

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
- **Affected Critical Invariants:** `explicit human confirmation; zero autonomous purchase; reservation ownership; idempotency; session isolation; live revalidation; 10-seat limit; 15-minute hold; no double booking`

---

## 2. Objective

Complete the Reservation Service integration for Phase 15 by adding:

1. the authorization-safe read-only `getReservation` tool; and
2. the only state-changing AI capability: creation of a normal SeatFlow reservation hold after the authenticated user explicitly confirms one exact server-side proposal.

This task must make it technically impossible for ordinary model-generated tool calls or free-form chat text to bypass the confirmation boundary.

Payment remains completely outside the AI tool set.

---

## 3. `getReservation` Read-Only Tool

Add `getReservation` to the ordinary chat read-only allow-list only after this task implements an ownership-safe Reservation Service client.

Request:

```text
GetReservationRequest
- reservationId: UUID
```

Result must be compact/customer-safe:

```text
ReservationToolResult
- reservationId
- eventId
- eventSessionId
- status
- expiresAt
- totalAmount / currency using canonical representation
- seats[] display summary
- session start metadata when already present in the public reservation response
```

Rules:

- downstream call uses the authenticated user's JWT;
- Reservation Service remains authoritative for ownership/access;
- AI Service must not switch to an internal privileged identity after `403`;
- do not expose customer email/name unless the normal authenticated customer response requires it for the user-facing result; prefer omission;
- do not expose guest proof headers, internal provider IDs, payment tokens, or audit internals;
- `404/403` returns safe tool error, never a guessed reservation.

After implementation, P15 ordinary chat allow-list is exactly:

```text
searchEvents
getEvent
getEventSessions
getAvailableSeats
findBestSeats
getReservation
```

`createReservation` remains absent from ordinary chat.

---

## 4. Authoritative Confirmation Flow

Use a dedicated application endpoint/action, never model prose, as authorization.

```text
/chat -> findBestSeats -> secure server proposal
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
      owner/status/TTL validation
                 |
      live availability + price revalidation
                 |
        exact proposal still valid?
        /                       \
      no                         yes
      |                           |
 STALE_PROPOSAL            Reservation Service
                                  |
                           normal 15-minute hold
                                  |
                         RESERVATION_CREATED
                                  |
                          normal checkout
```

A chat message such as `yes`, `confirm`, `book it`, or an LLM tool-call attempt must **not** directly create a reservation. It may cause the UI to re-present the explicit confirmation card.

---

## 5. Secure Proposal Store

No persistent AI database is introduced.

Use an application-owned bounded in-memory proposal store for the current single-instance portfolio deployment.

Required defaults:

```text
AI_PROPOSAL_TTL=5m
AI_MAX_ACTIVE_PROPOSALS=500
```

Both are configurable with validation/safe upper bounds.

Proposal record:

```text
ReservationProposal
- proposalId: UUID/opaque random ID
- conversationId
- ownerSubject
- eventId
- eventSessionId
- seatIds[]
- seat display snapshot[]
- pricingTierIds[]?           // only if current booking contract requires them
- maxTotalPriceMinor?         // original hard constraint if supplied
- selected category/strategy? // only constraints needed for revalidation
- totalPriceMinor
- currency
- createdAt
- expiresAt
- status: ACTIVE | CONSUMED | SUPERSEDED | EXPIRED
- serverIdempotencyKey
```

Rules:

- one active proposal per conversation; a new one supersedes the old one;
- cleanup expired entries lazily and/or with bounded scheduled cleanup;
- when max entries is reached, apply deterministic safe eviction/rejection; never evict another user's active proposal in a way that could transfer IDs/ownership;
- raw JWT, Groq key, card/payment token, chain-of-thought and complete prompt history are forbidden fields;
- process restart invalidates proposals safely.

The 5-minute proposal TTL is **not** a seat hold. Seats remain free until Reservation Service creates the normal 15-minute hold.

---

## 6. Confirmation Authorization

Before any state-changing downstream call:

1. requester is authenticated USER;
2. requester owns the proposal;
3. proposal conversation belongs to same requester;
4. proposal is `ACTIVE`;
5. proposal has not expired;
6. proposal is not superseded/consumed;
7. exact seat/session/price values come from server-side proposal storage;
8. confirmation request contains no client-editable seat IDs or price.

Preferred endpoint:

```text
POST /api/ai/proposals/{proposalId}/confirm
body: empty
```

Do not accept `{ seatIds, sessionId, price }` in confirmation body.

Use repo's anti-enumeration policy for not-found/forbidden proposal IDs consistently.

---

## 7. Live Revalidation Immediately Before Reservation

A proposal is stale-prone by design. Revalidate synchronously:

1. session still exists and is bookable;
2. exact proposed seats still exist/active;
3. all proposed seats are currently `AVAILABLE` in Reservation Service;
4. quantity remains `1..10`;
5. current pricing resolves using the exact same P15-003 rules;
6. all selected prices use same currency;
7. recomputed total exactly equals stored proposal total; if not, return `PRICE_CHANGED`;
8. stored hard constraints (budget/category where applicable) still pass;
9. no seat substitution occurs silently.

If any seat or price changes, require a **new proposal + new explicit confirmation**.

---

## 8. Reservation Service Client

Extend/add one dedicated `ReservationServiceClient` used for both:

- `getReservation` read-only lookup; and
- confirmed reservation creation.

Use Eureka/LoadBalancer, existing RestClient timeout/circuit-breaker conventions, user JWT and `X-Correlation-Id` propagation.

For reservation creation send only server-derived values:

- exact `eventSessionId`;
- exact proposed seat IDs;
- server-derived prices if the current canonical Reservation API still requires `seatPrices`;
- server-generated idempotency key;
- authenticated identity via JWT, not client/model-provided user ID.

If current reservation pricing requires a tier-selection follow-up endpoint, use the existing canonical flow and preserve Reservation Service semantics. Do not invent an AI-owned distributed transaction.

Reservation Service remains authoritative for:

- 10-seat rule;
- 15-minute expiration;
- concurrency/double booking;
- ownership;
- idempotency;
- reservation status.

---

## 9. Idempotency

Generate the idempotency key when the secure proposal is created and reuse it for every retry of that proposal.

Rules:

- double-click/network duplicate -> same idempotency key;
- never generate a new key merely because the first confirmation response timed out;
- proposal becomes `CONSUMED` only after success is authoritatively known;
- timeout-after-submit is `RESERVATION_RESULT_UNKNOWN_RETRY_SAFE`; retry/reconcile with same key;
- definitive stale/validation failure does not call Reservation Service;
- repeated confirmation after known success returns/reconciles the same reservation result rather than creating a new hold.

---

## 10. Failure Contract

Canonical codes:

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

- conflict -> never auto-substitute seats;
- price changed -> never auto-accept new price;
- downstream timeout after submit -> do not claim success/failure until reconciled;
- raw Reservation Service/internal exception body is not returned to Groq/client.

---

## 11. Successful Result

Return structured Reservation Service truth:

```text
ReservationCreatedCard
- reservationId
- eventSessionId
- seat IDs/display labels
- authoritative total amount/currency
- reservationStatus
- expiresAt
- checkoutRoute
```

Use Reservation Service `expiresAt`; never calculate `now + 15m` in AI Service or Angular.

`checkoutRoute` is generated by application routing rules, not LLM text.

No payment tool exists.

---

## 12. Guest Policy

Phase 15 state-changing AI flow is authenticated-user only.

Do not reproduce the normal guest email-proof workflow inside the model context. If a guest assistant is exposed later, it remains read-only until a separate secure contract/ADR explicitly expands scope.

---

## 13. Conversation Reset Interaction

Integrate with P15-004:

- resetting a conversation supersedes/removes its ACTIVE proposal;
- resetting does **not** cancel an already-created Reservation Service reservation;
- after a successful reservation, conversation may retain the reservation-created card/result while the proposal itself is consumed;
- process restart means proposal IDs are invalid; a fresh recommendation is required.

---

## 14. Expected File Inventory

Create/adapt under `backend/services/ai-service`:

- `[NEW/MODIFY]` `client/ReservationServiceClient.java`
- `[NEW]` read-only reservation tool request/result DTOs;
- `[NEW]` `tool/ReservationLookupTools.java` or equivalent;
- `[NEW]` `proposal/ReservationProposal.java`
- `[NEW]` `proposal/ProposalStatus.java`
- `[NEW]` `proposal/ProposalStore.java`
- `[NEW]` bounded in-memory store implementation + cleanup;
- `[NEW]` `service/ProposalService.java`
- `[NEW]` `service/ConfirmedReservationService.java`
- `[NEW]` proposal confirmation controller/DTOs;
- `[MODIFY]` P15-004 allow-list to include `getReservation` only;
- `[MODIFY]` conversation reset to clear active proposal;
- `[NEW]` concurrency/idempotency/security tests.

No DB/payment/admin tool.

---

## 15. Tests

Mandatory tests:

1. `getReservation` propagates USER JWT and returns only authorized reservation data.
2. `getReservation` 403/404 never retries with privileged identity.
3. ordinary chat allow-list now has exactly six read-only tools and still no `createReservation`.
4. valid owner + active proposal + unchanged live state -> exactly one reservation call.
5. another user cannot confirm proposal.
6. expired/superseded/consumed proposal -> no downstream write.
7. typed chat `yes` cannot call Reservation Service.
8. confirm request cannot alter seat IDs/price.
9. one proposed seat becomes unavailable -> stale/conflict, no substitution.
10. current price changes by one minor unit -> `PRICE_CHANGED`, fresh proposal required.
11. session becomes unbookable -> no reservation call.
12. duplicate confirm calls reuse identical idempotency key.
13. timeout-after-submit retry uses same key and reconciles.
14. success uses authoritative Reservation Service `expiresAt`.
15. no payment endpoint/tool can be reached.
16. proposal TTL 5m and max 500 default limits are enforced/configurable.
17. reset removes active proposal but does not cancel real hold.
18. process restart/lost proposal cannot be reconstructed from client/chat fields.
19. secrets/JWT are absent from proposal storage/result/log assertions.

CI uses downstream mocks; live Groq not required.

---

## 16. Acceptance Criteria

- [ ] `getReservation` exists as ownership-safe read-only tool.
- [ ] Ordinary chat exposes exactly six approved read-only tools.
- [ ] `createReservation` is not model-callable from ordinary chat.
- [ ] Only dedicated explicit confirmation can trigger a reservation write.
- [ ] Proposal is server-side, exact, owner-bound, 5-minute TTL, bounded, non-editable by client.
- [ ] Availability/session/pricing are revalidated immediately before write.
- [ ] Changed seats/price always require fresh proposal and fresh confirmation.
- [ ] Idempotency handles double-click/network retry/ambiguous timeout safely.
- [ ] Reservation Service remains authoritative for the actual hold and `expiresAt`.
- [ ] Payment remains manual/outside AI.
- [ ] Guest state-changing AI flow is not introduced.

---

## 17. Verification

```bash
cd backend
mvn -pl services/ai-service -am test
mvn verify
```

Manual live path after Groq key configuration:

```text
ask -> proposal -> click explicit confirm -> one 15-minute hold -> normal checkout
```

Also verify that chat text `yes` without the explicit confirmation action produces no reservation write.
