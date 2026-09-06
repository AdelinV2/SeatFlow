# TASK-P15-003: Implement Seat Availability and Deterministic Best-Seat Ranking

## 1. Task Metadata

- **Task ID:** `TASK-P15-003`
- **Git Branch:** `feat/p15-003-seat-availability-ranking`
- **Target Module:** `backend/services/ai-service`
- **Phase:** `Phase 15 - AI Assistant & Controlled Tool Calling`
- **Depends On:** `TASK-P15-002`
- **Related Specs:** `.ai/tasks/phase-15-ai-assistant-mcp/000-phase-overview.md`, `.ai/architecture/09-post-mvp-evolution.md`
- **Related ADRs:** `.ai/decisions/ADR-014-ai-assistant-tool-orchestration.md`
- **Status:** `READY FOR IMPLEMENTATION`

### Orchestration Metadata

- **Complexity:** `5`
- **Failure Risk:** `Critical`
- **Verification Strength:** `Strong`
- **Required Review Depth:** `Critical`
- **Preferred Workflow:** `critical`
- **Affected Critical Invariants:** `authoritative availability; session isolation; max-10; deterministic ranking; money/currency correctness; adjacency honesty; no hallucinated seat IDs/prices`

---

## 2. Objective

Implement the two seat-focused read-only AI tools:

- `getAvailableSeats`
- `findBestSeats`

`getAvailableSeats` must compose existing live SeatFlow APIs into a compact authoritative seat snapshot. `findBestSeats` must rank candidate seat sets in deterministic Java code. The LLM may choose constraints and explain returned results, but it must never calculate availability, price, adjacency, or ranking itself.

---

## 3. Authoritative Data Sources

Use current Phase 11/12 contracts as they exist when implementation starts.

Required responsibilities:

- **Reservation Service** is authoritative for session-scoped seat availability at `/api/event-sessions/{sessionId}/seats/availability` or the then-current canonical equivalent.
- **Event/Seat Map read API** provides seat identity/layout metadata (`seatId`, `sectionId`, `rowLabel`, integer `seatNumber`, active state, positions, section metadata).
- **Event Service pricing contract** provides customer-visible pricing tiers/currency.

The AI service must join these read-only responses by stable identifiers. It must not duplicate booking state or introduce an AI-owned inventory cache that can become authoritative.

If upstream DTO names change before implementation, adapt to the canonical contracts; do not revive deprecated event-level inventory semantics.

---

## 4. Tool Contracts

### 4.1 `getAvailableSeats`

Recommended request:

```text
GetAvailableSeatsRequest
- eventSessionId: UUID
- sectionId: UUID?
- category: String?
- maxTotalPriceMinor: Long?
- currency: String?
- limit: Integer?            // bounded; default small enough for model context
```

Recommended result:

```text
AvailableSeatsResult
- eventSessionId
- snapshotAt
- currency?                  // only when one unambiguous requested/result currency applies
- seats[]
  - seatId
  - sectionId
  - sectionName
  - rowLabel
  - seatNumber
  - positionX/positionY when useful
  - categoryName
  - pricingTierId
  - priceMinor
  - currency
  - status = AVAILABLE
```

Rules:

- only `AVAILABLE` + active seats are returned;
- do not return held/sold/reserved seats as candidates;
- never convert money using floating-point arithmetic;
- if upstream represents money as decimal major units, convert to minor units using the project's canonical money utility/scale and test exact rounding; do not invent FX conversion;
- filter by requested currency instead of combining currencies;
- cap result size before returning to the model.

### 4.2 `findBestSeats`

Required request:

```text
FindBestSeatsRequest
- eventSessionId: UUID
- quantity: int                   // 1..10
- maxTotalPriceMinor: Long?
- currency: String?
- preferredSectionId: UUID?
- preferredSectionName: String?
- preferredCategory: String?
- strategy: SeatRankingStrategy
```

Canonical strategies for Phase 15:

```text
CLOSEST_TO_STAGE
MOST_CENTRAL
BEST_VALUE
```

Do not add subjective strategies without deterministic definitions.

Required result:

```text
FindBestSeatsResult
- eventSessionId
- snapshotAt
- candidates[] (recommended max 3)
  - seatIds[]
  - seats[] display summary
  - totalPriceMinor
  - currency
  - contiguous: boolean
  - scoreBreakdown or deterministic reasons[]
  - rankingPosition
```

---

## 5. Hard Constraints Before Scoring

Ranking must operate only on candidates that pass all applicable hard constraints:

1. session ID is valid and session exists;
2. `quantity` is between 1 and 10 inclusive;
3. each seat exists in the current seat-map read model;
4. each seat is active;
5. each seat's current Reservation Service status is exactly `AVAILABLE`;
6. every selected seat belongs to the same requested `eventSessionId` inventory snapshot;
7. each seat has a resolvable price for the requested/default pricing choice;
8. all seats in one proposed set use the same currency;
9. total price is `<= maxTotalPriceMinor` when budget exists;
10. no seat ID appears twice in one candidate set.

A hard-constraint failure removes the candidate. Do not reduce a user's requested quantity silently.

If no valid candidate exists, return a structured `NO_MATCH` result and optional deterministic relaxation hints; do not fabricate a best match.

---

## 6. Deterministic Contiguity Rule

For Phase 15, seats are contiguous only when all are:

- in the same `sectionId`;
- have the same normalized `rowLabel`;
- have unique integer `seatNumber` values;
- sorted seat numbers form an exact consecutive sequence (`n, n+1, ...`).

This matches the existing seat contract where `seatNumber` is numeric.

Do not infer adjacency from labels such as `A1/A2` alone and do not claim cross-row or cross-section adjacency.

Candidate generation order:

1. generate all valid contiguous windows of requested `quantity` within each section+row;
2. if at least one contiguous set survives hard constraints, rank contiguous sets only;
3. if no contiguous set exists, generate bounded non-contiguous alternatives and mark `contiguous=false` explicitly;
4. do not allow the LLM to rewrite that flag.

To prevent combinatorial explosion for non-contiguous alternatives, use a deterministic bounded algorithm rather than enumerating all combinations.

---

## 7. Deterministic Geometry Metrics

Use the Phase 11 continuous layout data when available.

### 7.1 Coordinate normalization

Ranking should compare seats in venue-global coordinates. If seat positions are section-relative, apply the existing section transform/rotation logic or reuse a canonical helper already used by the frontend/read model. Do not compare raw local coordinates from differently transformed sections.

If global geometry cannot be derived reliably, geometry-dependent strategies must fall back to documented non-geometry criteria rather than invent a stage distance.

### 7.2 Stage reference

Prefer the venue layout element of type `STAGE` with valid geometry. Derive its center point deterministically.

If multiple stage elements exist, use a documented deterministic choice (for example lowest `zIndex`, then stable `elementId`) or an existing canonical primary-stage rule. Do not ask the LLM to choose.

If no usable stage exists:

- `CLOSEST_TO_STAGE` falls back to row/section ordering only if the application already has a documented ordering that means proximity;
- otherwise mark stage geometry unavailable and rank by stable neutral criteria.

### 7.3 Centrality

For `MOST_CENTRAL`, derive the active venue-seat bounding box center from global seat coordinates and minimize candidate-set centroid distance to that center.

---

## 8. Scoring and Stable Tie-Breakers

Hard constraints are applied first. Then calculate a score using normalized deterministic components.

Recommended strategy semantics:

### `CLOSEST_TO_STAGE`

Priority order:

1. contiguous set;
2. requested/preferred section/category match;
3. smaller candidate centroid distance to stage center when available;
4. lower total price only as tie breaker unless budget/value preference says otherwise;
5. stable identifiers.

### `MOST_CENTRAL`

1. contiguous set;
2. requested/preferred section/category match;
3. smaller candidate centroid distance to venue center;
4. lower total price;
5. stable identifiers.

### `BEST_VALUE`

1. contiguous set;
2. requested/preferred section/category match;
3. deterministic value score combining normalized geometry quality and lower price;
4. stable identifiers.

If using weighted scores, weights must be constants documented in code/tests and not prompt-generated. Prefer lexicographic priority tuples over opaque floating scores when practical.

Final tie breaker must be stable, for example:

```text
sectionId -> rowLabel -> minSeatNumber -> sorted seatIds
```

Repeated calls over an identical upstream snapshot must return identical ordering.

---

## 9. Price Selection

SeatFlow may expose multiple pricing tiers per section. The AI service must never guess a tier.

Rules:

- if the user requests a category/tier, select only exact valid category/tier matches according to normalized application semantics;
- otherwise choose the canonical default/base public price already used by the normal booking UI;
- if no canonical default exists and multiple tiers are possible, the tool returns `PRICING_SELECTION_REQUIRED` with the available categories rather than choosing silently;
- return `pricingTierId` with any proposed seat so reservation pricing can be reconciled later if the current reservation API requires it;
- all budget checks use exact integral minor units.

---

## 10. Snapshot and Staleness Semantics

Every result is a snapshot, not a hold.

Include `snapshotAt` and clearly document:

- another user may reserve a seat after ranking;
- Phase 15 confirmation must revalidate availability and price before calling `createReservation`;
- `findBestSeats` never creates a hold;
- do not persist availability as authoritative conversation state.

---

## 11. Expected File Inventory

Create/adapt under `backend/services/ai-service`:

- `[NEW]` Reservation availability client + DTOs;
- `[NEW]` event/seat-map/pricing read client extensions as required;
- `[NEW]` `SeatAvailabilityTools`;
- `[NEW]` `SeatRankingService`;
- `[NEW]` `SeatCandidateAssembler` or equivalent composition layer;
- `[NEW]` `SeatRankingStrategy` enum;
- `[NEW]` tool request/result DTOs;
- `[NEW]` deterministic money/geometry helper only if no reusable canonical helper exists;
- `[NEW]` focused client, algorithm, boundary and property-style tests.

Do not create an AI database/repository/cache for seat state.

---

## 12. Tests

Mandatory tests include:

1. quantity `0` and `11` rejected; `1` and `10` accepted.
2. held/sold/reserved/disabled/inactive seats never enter candidates.
3. two seats are contiguous only for same section + same row + consecutive integer seat numbers.
4. `A1` and `A3` are not contiguous for quantity 2.
5. same seat numbers in different sections are not contiguous.
6. exact budget boundary is accepted; one minor unit above budget is rejected.
7. mixed currencies are never summed.
8. multi-tier ambiguity returns explicit pricing-selection state rather than arbitrary tier selection.
9. identical input snapshots produce byte-equivalent candidate ordering.
10. stage geometry changes deterministically affect `CLOSEST_TO_STAGE` ranking.
11. missing/invalid stage geometry does not crash or hallucinate distance.
12. seat-map/availability mismatch excludes unknown seats safely and records a bounded warning metric.
13. Reservation Service timeout returns tool failure, not stale local data.
14. result count is bounded.
15. no live Groq call is required for ranking tests.

Add regression tests for any ranking edge case found during implementation.

---

## 13. Acceptance Criteria

- [ ] `getAvailableSeats` returns compact live customer-safe availability.
- [ ] `findBestSeats` is pure/deterministic over explicit upstream snapshots.
- [ ] LLM does not compute or invent seat ranking.
- [ ] quantity, session, availability, active-seat, budget, currency and uniqueness constraints are enforced.
- [ ] contiguity has one exact documented definition.
- [ ] money uses exact minor units and never FX-converts.
- [ ] pricing-tier ambiguity is explicit.
- [ ] ranking remains stable under identical data.
- [ ] no AI-owned inventory source of truth exists.
- [ ] failures never return fabricated candidates.
- [ ] tests cover algorithmic and service-composition boundaries.

---

## 14. Verification

```bash
cd backend
mvn -pl services/ai-service -am test
mvn verify
```

A live Groq key is unnecessary for this task's correctness. Optional manual chat testing may be done only after deterministic tool tests pass.
