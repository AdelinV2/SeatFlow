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
- **Affected Critical Invariants:** `ADMIN authorization; money correctness; multi-currency isolation; aggregate-grain correctness; bounded queries; eventual-consistency disclosure; no PII; no source-service fanout`

---

## 2. Objective

Expose stable, server-authorized, bounded REST endpoints under `/api/admin/analytics/**` for the Angular admin dashboard.

The API must expose:

- KPI summary and cohort rates;
- daily time series;
- paginated event-session operational metrics;
- per-session currency-separated revenue;
- top event/session rankings;
- projection freshness metadata.

All results come solely from `seatflow_analytics`. No endpoint may synchronously fan out to Event, Reservation, Payment, Ticket, Seat Map, Grafana, or another service.

P14-001/P14-003 deliberately separate **currency-neutral operational aggregates** from **currency-keyed financial aggregates**. This API must preserve that boundary: operational counts are read once; revenue is returned as grouped currency values. CSV/filter helper endpoints belong to P14-006.

---

## 3. Critical Invariants & Failure Modes

### 3.1 Invariants

- [ ] Every `/api/admin/analytics/**` endpoint requires `ROLE_ADMIN` inside analytics-service.
- [ ] Anonymous and authenticated non-admin callers cannot read analytics.
- [ ] API Gateway routing is not authorization.
- [ ] All date ranges are validated and bounded before repository execution.
- [ ] Date semantics are UTC calendar dates; `from`/`to` are inclusive.
- [ ] Default range is last 30 UTC calendar days including today.
- [ ] Maximum interactive range is 366 inclusive calendar days.
- [ ] Operational counts are not multiplied by the number of currencies returned for a session/date.
- [ ] Money is returned in integer minor units grouped by currency; never a mixed-currency scalar.
- [ ] Financial DTOs clearly expose `testMode=true` / Stripe Test Mode semantics.
- [ ] Rates use P14-003 cohort definitions, not arbitrary controller ratios.
- [ ] Zero denominator yields `ratio=null` with numerator/denominator, never NaN/Infinity.
- [ ] Pagination is bounded and sorting is allowlisted.
- [ ] UUID filters use typed parameter binding; no concatenated SQL.
- [ ] Responses contain no PII, Stripe secrets, raw webhook payload, JWT details, or stack traces.
- [ ] Projection freshness/eventual consistency is explicit.
- [ ] Empty valid queries return `200` with empty/zero result, not `404` except explicit session-detail lookup.

### 3.2 Failure Modes to Prevent

- frontend guard as only authorization;
- joining one `event_session_metrics` row to two revenue rows then summing operational counts twice;
- `SUM(gross)` across RON and EUR;
- unbounded time-series query;
- arbitrary sort field injection;
- local-timezone date drift;
- division by zero;
- frontend reverse-engineering rate definitions;
- per-row Event Service lookups for labels;
- stale data presented as transactional truth;
- generic untyped `Map<String,Object>` contracts.

---

## 4. Dependencies / Prerequisites

- P14-001 gateway/service/security scaffold complete.
- P14-002/P14-003 facts, split aggregates, and cohort query support complete.
- Reuse shared SeatFlow common-domain error/pagination conventions where compatible.
- Read current admin controller/OpenAPI conventions before implementation.

---

## 5. Exact File Inventory

Expected additions in `backend/services/analytics-service`:

- `[NEW]` `.../web/controller/AdminAnalyticsController.java`
- `[NEW]` `.../service/AdminAnalyticsQueryService.java`
- `[NEW]` service implementation if current project convention uses interface/impl separation
- `[NEW]` validated analytics range/filter request object(s)
- `[NEW]` `.../web/dto/response/AnalyticsSummaryResponse.java`
- `[NEW]` `.../web/dto/response/MoneyMetricResponse.java`
- `[NEW]` `.../web/dto/response/RateMetricResponse.java`
- `[NEW]` `.../web/dto/response/AnalyticsTimeSeriesResponse.java`
- `[NEW]` `.../web/dto/response/AnalyticsDailyPointResponse.java`
- `[NEW]` `.../web/dto/response/EventSessionAnalyticsResponse.java`
- `[NEW]` `.../web/dto/response/TopAnalyticsItemResponse.java`
- `[NEW]` `.../web/dto/response/ProjectionFreshnessResponse.java`
- `[NEW]` bounded query repository/projection interfaces
- `[MODIFY]` analytics `SecurityConfig.java` if exact matcher is not yet enforced
- `[MODIFY]` API Gateway route/security tests when endpoint-specific coverage is needed
- `[NEW]` controller/service/repository tests

DTOs may be consolidated only if type safety/OpenAPI clarity remains equal or better. Do not replace them with generic maps.

---

## 6. Technical Specifications & Contracts

### 6.1 Common Query Parameters

```text
from              optional YYYY-MM-DD, inclusive UTC date
to                optional YYYY-MM-DD, inclusive UTC date
eventId           optional UUID
eventSessionId    optional UUID
```

Rules:

- both dates omitted -> `[utcToday - 29 days, utcToday]`;
- exactly one boundary supplied -> `400`;
- `from <= to`;
- inclusive span <= 366 days;
- `eventSessionId` may be supplied without `eventId`;
- when both IDs are supplied and analytics has no matching row, return empty/zero data; do not call another service to validate;
- inject `Clock` for deterministic UTC defaults/tests.

Stable errors:

```text
INVALID_ANALYTICS_DATE_RANGE
ANALYTICS_DATE_RANGE_TOO_LARGE
INVALID_ANALYTICS_SORT
INVALID_ANALYTICS_LIMIT
```

Use the existing common error shape.

### 6.2 `GET /api/admin/analytics/summary`

Purpose: KPI cards + rates.

Semantic response shape:

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
    "withFailure": 11,
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

Example values are illustrative only.

Data source rule:

- reservation/ticket/payment counts come from currency-neutral facts/operational aggregate queries;
- `revenueByCurrency` comes from financial aggregates/facts grouped by currency;
- do not derive count totals by summing rows from currency-keyed revenue tables.

Rates:

- `ratio` decimal `0..1` with predictable serialization (e.g. up to 6 decimal places);
- denominator zero -> `ratio=null`;
- return numerator + denominator so semantics are inspectable;
- no preformatted-only percentage string.

### 6.3 `GET /api/admin/analytics/timeseries`

Parameters: common filters plus:

```text
metric = GROSS_REVENUE | NET_REVENUE | TICKETS_ISSUED | TICKETS_SCANNED | RESERVATIONS_CREATED | PAYMENTS_SUCCEEDED
```

Only `DAY` granularity is supported in Phase 14.

Data source contract:

- money metrics (`GROSS_REVENUE`, `NET_REVENUE`) -> `daily_revenue_metrics`, one series per currency;
- count metrics -> `daily_operational_metrics`, one currency-neutral series.

Response for money:

```text
series = [{ currency, testMode: true, points: [{date, valueMinor}] }]
```

Response for counts:

```text
series = [{ points: [{date, value}] }]
```

Rules:

- fill missing dates with zero so x-axis is stable;
- max 366 points per series;
- never duplicate a count series once per currency;
- one bounded aggregate query per metric request, then fill date gaps in memory; no query-per-day loop.

### 6.4 `GET /api/admin/analytics/sessions`

Parameters:

```text
from, to, eventId
page default 0
size default 25, min 1, max 100
sort default startsAt,desc
allowed sort: startsAt, grossRevenue, ticketsIssued, ticketsScanned, reservationsCreated
```

Each row includes:

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
paymentsWithFailure
refundsCompleted
ticketsIssued
ticketsRevoked
ticketsScanned
revenueByCurrency[]
occupancyRatio nullable
attendanceRatio nullable
lastProjectedEventAt
```

Implementation rule:

- page/sort the currency-neutral session rows first;
- fetch revenue rows for only the session IDs in that page, in one batched query;
- group them into `revenueByCurrency[]` in memory;
- do not SQL-join revenue rows before pagination in a way that duplicates session rows or corrupts `totalElements`.

For sort `grossRevenue` with potentially multiple currencies, ambiguity must be rejected unless a `currency` sort parameter is supplied. Exact contract:

```text
sort=grossRevenue requires currency=<3-letter code>
```

If currency omitted -> `400 INVALID_ANALYTICS_SORT`. For other sort fields, currency parameter is ignored/rejected according to one documented policy; prefer reject unused currency when sort is not monetary only if current API style favors strictness.

Missing title/label returns null; never live-fetch.

### 6.5 `GET /api/admin/analytics/sessions/{eventSessionId}`

Return one projected session detail with the same operational + `revenueByCurrency[]` composition.

- `404` when analytics has no session fact for that ID;
- response remains eventually consistent and does not prove Event Service lacks the session;
- no source-service lookup.

### 6.6 `GET /api/admin/analytics/top`

Parameters:

```text
from, to
metric = NET_REVENUE | TICKETS_ISSUED | TICKETS_SCANNED | RESERVATIONS_CONFIRMED
limit default 5, min 1, max 20
eventId optional
currency required only for NET_REVENUE
```

Rules:

- count rankings use currency-neutral operational aggregates;
- `NET_REVENUE` ranking requires exactly one requested currency and ranks only that currency; never compare mixed-currency money;
- stable tie-break: metric DESC, `eventSessionId ASC`;
- missing `currency` for `NET_REVENUE` -> `400` stable validation error.

### 6.7 Freshness Contract

```text
generatedAt           server Clock now
lastProjectedEventAt  max successfully projected source event occurredAt
lastProcessedAt       max processed_events.processed_at
eventuallyConsistent  true
```

Do not call `now - lastProjectedEventAt` exact Kafka lag. During no-event periods it is only read-model recency.

### 6.8 Authorization

Analytics-service:

```text
/api/admin/analytics/** -> hasRole("ADMIN")
```

Tests:

- anonymous -> current resource-server `401` behavior;
- authenticated non-admin -> `403`;
- ADMIN -> controller executes.

Do not trust role headers or frontend guards.

### 6.9 Query / Performance Rules

- all queries bounded by date/page/limit;
- no N+1 for revenue rows/labels;
- page operational sessions before batched revenue enrichment;
- query only needed columns;
- add indexes via additive migration if real query plan needs one;
- no caching required in Phase 14 unless measured;
- never cache without date/event/session/currency dimensions in key.

---

## 7. Step-by-Step Implementation Sequence

1. Define immutable range/filter objects and validation.
2. Add injected Clock for UTC defaults/freshness.
3. Implement summary query from operational counts + separate grouped revenue query.
4. Implement currency-safe money mapping.
5. Implement count vs financial time-series paths with date gap filling.
6. Implement session page query from operational rows, then batched revenue enrichment.
7. Implement session detail.
8. Implement top rankings, requiring currency for financial ranking.
9. Add freshness metadata.
10. Enforce ADMIN matcher/security tests.
11. Add OpenAPI annotations/examples matching repository conventions.
12. Add PostgreSQL integration tests including same-session RON+EUR fixtures.

---

## 8. Test Requirements

### 8.1 Authorization

- [ ] anonymous denied;
- [ ] regular authenticated user/customer denied;
- [ ] non-admin staff denied unless current role model explicitly maps it to ADMIN;
- [ ] ADMIN succeeds.

### 8.2 Range Validation

- [ ] no dates -> exactly last 30 UTC dates via fixed Clock;
- [ ] one boundary only -> `400`;
- [ ] `from > to` -> `400` stable code;
- [ ] exactly 366 inclusive days accepted;
- [ ] 367 rejected before DB query.

### 8.3 Aggregate Grain / Currency

Fixture: one session with RON + EUR financial rows.

- [ ] session appears once in paged result;
- [ ] reservations/tickets/payment operational counts appear once, not doubled;
- [ ] `revenueByCurrency` contains separate RON + EUR items;
- [ ] summary operational counts are not derived from revenue-row count;
- [ ] no mixed-currency scalar revenue exists.

### 8.4 Rates

- [ ] P14-003 cohort definitions match fixtures;
- [ ] denominator zero -> null ratio;
- [ ] finite serialized numeric values only.

### 8.5 Time Series

- [ ] missing date -> zero point;
- [ ] inclusive boundaries;
- [ ] money -> separate currency series;
- [ ] count -> one currency-neutral series;
- [ ] no per-day query loop.

### 8.6 Sessions / Ranking

- [ ] `size > 100` rejected with stable error;
- [ ] invalid sort rejected;
- [ ] `sort=grossRevenue` without currency rejected;
- [ ] financial top without currency rejected;
- [ ] stable tie ordering;
- [ ] missing title never triggers source-service call;
- [ ] unknown projected session detail -> common-shape `404`;
- [ ] no PII serialized.

---

## 9. Verification Commands

```bash
cd backend
./mvnw -pl services/analytics-service,services/api-gateway -am test
```

Run current OpenAPI validation/generation if the repository has it.

---

## 10. Independent Review Focus

Review:

- server-side ADMIN authorization;
- UTC/default range math;
- exact cohort definitions;
- operational-vs-financial query grain;
- no count multiplication through currency joins;
- multi-currency separation;
- page-before-revenue-enrichment behavior;
- bounded pagination/range/limit/sort;
- eventual-consistency wording;
- no source-service fanout/N+1;
- no PII/internal details;
- deterministic ordering/OpenAPI stability.

---

## 11. Acceptance Criteria

- [ ] Admin-only summary, timeseries, sessions, session detail, and top endpoints exist.
- [ ] Date ranges, pagination, sorting, and limits are bounded/validated.
- [ ] Operational counts are currency-neutral and never duplicated by currency rows.
- [ ] Money is minor-unit, currency-separated, and explicitly Test Mode.
- [ ] Rates use documented cohorts and safe zero-denominator behavior.
- [ ] Responses expose freshness/eventual consistency.
- [ ] No endpoint calls operational services/databases.
- [ ] Security, repository, controller, aggregate-grain, and multi-currency tests pass.
- [ ] Critical independent review passes.

---

## 12. Execution Entry Point

```text
Implement TASK-P14-004 using the SeatFlow autonomous orchestration workflow.
Treat ADMIN authorization, bounded queries, operational-vs-financial aggregate grain, and currency separation as hard contracts. Do not add CSV/filter-option endpoints yet; those belong to TASK-P14-006.
```
