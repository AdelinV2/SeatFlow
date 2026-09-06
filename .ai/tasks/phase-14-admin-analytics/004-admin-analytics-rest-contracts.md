# TASK-P14-004: Expose Admin-Only Analytics REST Contracts

## 1. Task Metadata

- **Task ID:** `TASK-P14-004`
- **Git Branch:** `feat/p14-004-admin-analytics-api`
- **Target Module:** `backend/services/analytics-service`, API Gateway route/security verification
- **Phase:** `Phase 14 - Admin Analytics & Operations Dashboard`
- **Related Specs:** `.ai/tasks/phase-14-admin-analytics/000-phase-overview.md`, P14-001 through P14-003
- **Related ADRs:** `.ai/decisions/ADR-013-analytics-event-driven-read-model.md`
- **Status:** `READY FOR IMPLEMENTATION`

### Orchestration Metadata

- **Complexity:** `4`
- **Failure Risk:** `High`
- **Verification Strength:** `Strong`
- **Required Review Depth:** `Critical`
- **Preferred Workflow:** `critical`
- **Affected Critical Invariants:** `ADMIN authorization; money correctness; multi-currency isolation; bounded queries; eventual-consistency disclosure; no PII; no source-service fanout`

---

## 2. Objective

Expose stable, server-authorized, bounded REST endpoints under `/api/admin/analytics/**` for the Angular admin dashboard.

The API must make analytics semantics explicit rather than returning generic maps or frontend-computed business definitions. It must expose:

- KPI summary and cohort rates;
- daily time series;
- paginated event-session operational metrics;
- top event/session ranking;
- projection freshness metadata.

All results come solely from `seatflow_analytics`. No endpoint may synchronously fan out to Event, Reservation, Payment, Ticket, Seat Map, Grafana, or another service to complete a response.

CSV/filter helper endpoints belong to P14-006.

---

## 3. Critical Invariants & Failure Modes

### 3.1 Invariants

- [ ] Every `/api/admin/analytics/**` endpoint requires `ROLE_ADMIN` in analytics-service, independent of frontend guards.
- [ ] Anonymous, CUSTOMER/USER, and STAFF/non-admin callers cannot read analytics data.
- [ ] API Gateway routing does not replace service-side authorization.
- [ ] All date ranges are validated and bounded before repository execution.
- [ ] Date semantics are UTC calendar dates and both `from`/`to` are inclusive.
- [ ] Default range is deterministic: last 30 UTC calendar days including today when no dates are supplied.
- [ ] Maximum requested range is 366 calendar days for interactive endpoints.
- [ ] Money is returned in integer minor units grouped by currency; never return a mixed-currency scalar total.
- [ ] Every financial DTO clearly communicates `testMode=true` / `Stripe Test Mode` semantics.
- [ ] Rates use P14-003 cohort definitions, not arbitrary ratios recalculated in the controller.
- [ ] Zero denominator yields `null`/unavailable rate plus the underlying numerator/denominator, not NaN, Infinity, or misleading 0%.
- [ ] Pagination size is bounded; sorting is allowlisted.
- [ ] UUID filters are parsed/validated normally; no string-concatenated SQL.
- [ ] Responses contain no customer PII, Stripe secrets, raw webhook payload, JWT data, or internal stack traces.
- [ ] Analytics freshness is visible so eventual consistency is not mistaken for transactional source-of-truth state.
- [ ] Empty valid data returns `200` with empty collections/zero counts as appropriate, not `404`.

### 3.2 Failure Modes to Prevent

- client-side role guard being the only authorization;
- `SUM(amount)` across currencies;
- unbounded 10-year time-series query;
- arbitrary `sort` field injected into SQL/JPA;
- current-day local timezone mismatch between API and stored UTC buckets;
- percentage division by zero;
- frontend reverse-engineering rate definitions from raw counts;
- per-row REST calls to Event Service for labels;
- endpoint returns stale data with no freshness indication;
- one giant untyped response object that makes contract changes risky.

---

## 4. Dependencies / Prerequisites

- P14-001 gateway/service/security scaffold complete.
- P14-002/P14-003 facts, aggregates, and cohort query support complete.
- Shared SeatFlow exception/error response and pagination conventions must be reused where compatible.
- Read current admin controller patterns before implementation; preserve OpenAPI/controller conventions already enforced by `backend/AGENTS.md`.

---

## 5. Exact File Inventory

Expected additions in `backend/services/analytics-service`:

- `[NEW]` `.../web/controller/AdminAnalyticsController.java`
- `[NEW]` `.../service/AdminAnalyticsQueryService.java`
- `[NEW]` `.../service/impl/AdminAnalyticsQueryServiceImpl.java` if interface/impl separation matches current project conventions
- `[NEW]` `.../web/dto/request/AnalyticsRangeRequest.java` or a validated query object if current controller style supports it
- `[NEW]` `.../web/dto/response/AnalyticsSummaryResponse.java`
- `[NEW]` `.../web/dto/response/MoneyMetricResponse.java`
- `[NEW]` `.../web/dto/response/RateMetricResponse.java`
- `[NEW]` `.../web/dto/response/AnalyticsTimeSeriesResponse.java`
- `[NEW]` `.../web/dto/response/AnalyticsDailyPointResponse.java`
- `[NEW]` `.../web/dto/response/EventSessionAnalyticsResponse.java`
- `[NEW]` `.../web/dto/response/TopAnalyticsItemResponse.java`
- `[NEW]` `.../web/dto/response/ProjectionFreshnessResponse.java`
- `[NEW]` query repository/projection interfaces needed for bounded read queries
- `[MODIFY]` `.../config/SecurityConfig.java` if P14-001 did not yet enforce the exact matcher
- `[MODIFY]` API Gateway route/security tests if coverage needs endpoint-specific assertions
- `[NEW]` controller/service/repository tests

DTO names may be consolidated only if type safety and OpenAPI clarity remain equal or better; do not replace them with `Map<String,Object>`.

---

## 6. Technical Specifications & Contracts

## 6.1 Common Query Parameters

Interactive endpoints use:

```text
from       optional YYYY-MM-DD, inclusive UTC date
to         optional YYYY-MM-DD, inclusive UTC date
eventId    optional UUID
eventSessionId optional UUID
```

Rules:

- when both dates omitted: `[utcToday - 29 days, utcToday]`;
- when one date is omitted: reject `400` rather than guessing the missing boundary;
- `from <= to`;
- inclusive span <= 366 days;
- `eventSessionId` may be supplied alone; do not require `eventId` merely for validation;
- when both IDs are supplied and no matching projected data exists, return empty/zero data, not a cross-service validation call;
- use injectable `Clock` in service logic so default-range tests are deterministic.

Recommended stable error codes:

```text
INVALID_ANALYTICS_DATE_RANGE
ANALYTICS_DATE_RANGE_TOO_LARGE
INVALID_ANALYTICS_SORT
INVALID_ANALYTICS_LIMIT
```

Reuse existing common error shape.

### 6.2 `GET /api/admin/analytics/summary`

Purpose: KPI cards and rates.

Example semantic shape:

```json
{
  "from": "2026-08-08",
  "to": "2026-09-06",
  "filters": {
    "eventId": null,
    "eventSessionId": null
  },
  "reservations": {
    "created": 120,
    "confirmed": 87,
    "expired": 25,
    "refunded": 4
  },
  "tickets": {
    "issued": 174,
    "revoked": 8,
    "scanned": 103
  },
  "payments": {
    "succeeded": 87,
    "failedAttempts": 11,
    "refundsCompleted": 4,
    "revenueByCurrency": [
      {
        "currency": "RON",
        "grossMinor": 3150000,
        "refundedMinor": 120000,
        "netMinor": 3030000,
        "testMode": true
      }
    ]
  },
  "rates": {
    "reservationToPayment": {
      "numerator": 87,
      "denominator": 120,
      "ratio": 0.725
    },
    "expiration": {
      "numerator": 25,
      "denominator": 120,
      "ratio": 0.208333
    },
    "refund": {
      "numerator": 4,
      "denominator": 87,
      "ratio": 0.045977
    }
  },
  "freshness": {
    "generatedAt": "2026-09-06T12:00:00Z",
    "lastProjectedEventAt": "2026-09-06T11:59:42Z",
    "lastProcessedAt": "2026-09-06T11:59:43Z",
    "eventuallyConsistent": true
  }
}
```

This is an illustrative field layout, not permission to hardcode sample values.

Rate rules:

- `ratio` is decimal `0..1`, rounded/serialized predictably (e.g. scale up to 6); frontend owns percent display formatting.
- if denominator `0`, `ratio=null`, numerator/denominator still returned.
- do not expose only a preformatted string like `72.5%`.

### 6.3 `GET /api/admin/analytics/timeseries`

Purpose: daily trend charts.

Parameters: common filters plus:

```text
metric = GROSS_REVENUE | NET_REVENUE | TICKETS_ISSUED | TICKETS_SCANNED | RESERVATIONS_CREATED | PAYMENTS_SUCCEEDED
```

Only `DAY` granularity is in Phase 14. Do not advertise unsupported WEEK/MONTH options.

Response:

```text
from, to, metric, eventuallyConsistent, series[]
```

For money metrics:

```text
series = [{ currency, testMode: true, points: [{date, valueMinor}] }]
```

For count metrics:

```text
series = [{ points: [{date, value}] }]
```

Requirements:

- fill missing dates with zero points so chart x-axis is stable;
- generate at most 366 daily points per series due range bound;
- money remains one series per currency;
- do not perform 366 individual SQL queries; use one bounded aggregate query then fill gaps in memory.

### 6.4 `GET /api/admin/analytics/sessions`

Purpose: paginated operational table.

Parameters:

```text
from, to, eventId
page default 0
size default 25, min 1, max 100
sort default startsAt,desc
allowed sort fields: startsAt, grossRevenue, ticketsIssued, ticketsScanned, reservationsCreated
```

Each row includes only analytics-safe fields such as:

```text
eventId
eventSessionId
eventTitle nullable
sessionLabel nullable
startsAt nullable
status nullable
capacitySnapshot nullable
reservationsCreated
reservationsConfirmed
reservationsExpired
paymentsSucceeded
paymentFailures
refundsCompleted
ticketsIssued
ticketsRevoked
ticketsScanned
revenueByCurrency[]
occupancyRatio nullable
attendanceRatio nullable
lastProjectedEventAt
```

If `eventTitle`/label snapshot is unavailable, return null and let UI fall back to a shortened stable ID. Never live-fetch it.

Pagination uses the repository's standard paged response convention where available.

### 6.5 `GET /api/admin/analytics/sessions/{eventSessionId}`

Return one projected session analytics detail if that session exists in analytics facts.

- `404` is appropriate when the analytics read model has no session fact for that ID.
- Response must still indicate eventual consistency; this does not prove the operational Event Service lacks the session.
- No source-service lookup to distinguish "not projected yet" from "does not exist".

### 6.6 `GET /api/admin/analytics/top`

Parameters:

```text
from, to
metric = NET_REVENUE | TICKETS_ISSUED | TICKETS_SCANNED | RESERVATIONS_CONFIRMED
limit default 5, min 1, max 20
eventId optional
```

Return ranked session items. For `NET_REVENUE`, rankings must be **per currency**; never rank by mixed-currency addition. Response may therefore contain grouped rankings by currency.

Stable tie-breaking: after metric descending, use `eventSessionId` ascending (or another explicit deterministic key). No nondeterministic DB ordering.

### 6.7 Freshness Contract

Provide freshness based on analytics-owned records:

```text
generatedAt           server Clock now
lastProjectedEventAt  max source event occurredAt successfully projected
lastProcessedAt       max processed_events.processed_at
eventuallyConsistent  true
```

Do not label `now - lastProjectedEventAt` as exact Kafka lag; it is only read-model recency and can be misleading during periods with no events. If UI later shows a "last updated" indicator, phrase it accordingly.

### 6.8 Authorization

Analytics-service matcher must enforce:

```text
/api/admin/analytics/** -> hasRole("ADMIN")
```

Tests must cover:

- anonymous -> `401` according to current resource-server behavior;
- authenticated non-admin -> `403`;
- ADMIN -> controller executes.

Do not trust an `X-Role` header or frontend route guard.

### 6.9 Query / Performance Rules

- all queries bounded by date/page/limit;
- no N+1 per session row;
- prefer DB aggregation/projections over loading full fact tables;
- query only columns needed for DTOs;
- confirm indexes from P14-001 support filters; add additive migration if query plan proves a missing index;
- no caching required in Phase 14 unless tests/profiling show a real need;
- never cache one admin's filter result under a key that omits filter/date/currency dimensions.

---

## 7. Step-by-Step Implementation Sequence

1. Define immutable request/range/filter objects and shared validation.
2. Add a `Clock` dependency for UTC defaults/freshness timestamps.
3. Implement repository queries for summary and cohort metrics.
4. Implement currency-safe money DTO mapping.
5. Implement daily time-series query + zero-date filling.
6. Implement paginated session metrics query and allowlisted sort mapping.
7. Implement session detail and deterministic top rankings.
8. Add freshness metadata to all top-level responses or a shared envelope without introducing a generic untyped wrapper.
9. Enforce ADMIN matcher and security tests.
10. Add OpenAPI annotations/examples consistent with repo conventions.
11. Add repository/controller integration tests using PostgreSQL and representative multiple-currency fixtures.

---

## 8. Test Requirements

### 8.1 Authorization

- [ ] anonymous cannot access analytics endpoints;
- [ ] regular authenticated user/customer cannot access;
- [ ] non-admin staff role cannot access unless current role model explicitly aliases it to ADMIN (do not assume);
- [ ] ADMIN succeeds.

### 8.2 Range Validation

- [ ] no dates -> exactly last 30 UTC dates via fixed Clock;
- [ ] only one boundary -> `400`;
- [ ] `from > to` -> `400` stable code;
- [ ] exactly 366 inclusive days accepted;
- [ ] 367 days rejected before DB query.

### 8.3 Money / Currency

- [ ] RON + EUR produce separate `revenueByCurrency` items;
- [ ] `net = gross - refunded` exact in minor units;
- [ ] every financial group has `testMode=true`;
- [ ] no scalar mixed-currency `totalRevenue` appears.

### 8.4 Rates

- [ ] cohort definitions match P14-003 fixtures;
- [ ] denominator zero returns null ratio;
- [ ] serialization contains finite numeric values only;
- [ ] frontend need not infer numerator/denominator.

### 8.5 Time Series

- [ ] missing DB date becomes zero point;
- [ ] range boundaries inclusive;
- [ ] money returns separate currency series;
- [ ] one bounded query rather than one query per day (verify repository implementation/tests as practical).

### 8.6 Sessions / Ranking

- [ ] `size=101` rejected/clamped only according to explicit validation policy; prefer rejection with stable error;
- [ ] invalid sort rejected;
- [ ] stable tie ordering;
- [ ] missing event title does not trigger a source-service call;
- [ ] unknown projected session detail returns `404` with common error shape;
- [ ] no PII fields in serialized response.

---

## 9. Verification Commands

```bash
cd backend
./mvnw -pl services/analytics-service,services/api-gateway -am test
```

If OpenAPI contract generation/validation exists in the current repo, run it as part of verification.

---

## 10. Independent Review Focus

Reviewer must inspect:

- server-side ADMIN authorization;
- UTC/default range boundary math;
- exact cohort definitions;
- multi-currency separation in every query/DTO;
- no source-service fanout/N+1;
- bounded pagination/range/limit/sort;
- eventual-consistency wording/freshness semantics;
- no PII/internal details;
- deterministic ordering;
- OpenAPI and frontend-consumable type stability.

---

## 11. Acceptance Criteria

- [ ] Admin-only summary, timeseries, sessions, session detail, and top endpoints exist under `/api/admin/analytics/**`.
- [ ] Date ranges, pagination, sorting, and limits are bounded and validated.
- [ ] Money is minor-unit and currency-separated with explicit Test Mode semantics.
- [ ] Rates use documented cohort definitions and safe zero-denominator behavior.
- [ ] Responses expose read-model freshness/eventual consistency.
- [ ] No endpoint queries or calls operational services/databases.
- [ ] Security, repository, controller, and multi-currency tests pass.
- [ ] Critical independent review passes.

---

## 12. Execution Entry Point

```text
Implement TASK-P14-004 using the SeatFlow autonomous orchestration workflow.
Treat API semantics, ADMIN authorization, bounded queries, and currency separation as hard contracts. Do not add CSV/filter-option endpoints yet; those belong to TASK-P14-006.
```
