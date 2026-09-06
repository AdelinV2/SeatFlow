# TASK-P14-005: Build Admin KPI Dashboard and Accessible Native Charts

## 1. Task Metadata

- **Task ID:** `TASK-P14-005`
- **Git Branch:** `feat/p14-005-admin-analytics-dashboard`
- **Target Module:** `frontend/src/app/features/admin`, `frontend/src/app/services`, `frontend/src/app/models`
- **Phase:** `Phase 14 - Admin Analytics & Operations Dashboard`
- **Related Specs:** `.ai/tasks/phase-14-admin-analytics/000-phase-overview.md`, P14-004 REST contracts
- **Related ADRs:** `.ai/decisions/ADR-013-analytics-event-driven-read-model.md`
- **Status:** `READY FOR IMPLEMENTATION`

### Orchestration Metadata

- **Complexity:** `4`
- **Failure Risk:** `Medium`
- **Verification Strength:** `Strong`
- **Required Review Depth:** `Standard + UI/accessibility review`
- **Preferred Workflow:** `full`
- **Affected Critical Invariants:** `ADMIN-only product surface; Test Mode disclosure; API contract fidelity; no fabricated metrics; responsive/accessibility; no Grafana embedding`

---

## 2. Objective

Extend the existing Angular Admin Portal with a portfolio-grade analytics dashboard using the typed P14-004 API.

The dashboard must present:

- reservation/payment/ticket KPI cards;
- gross/refunded/net revenue grouped by currency;
- reservation conversion/expiration/refund rates;
- a daily trend chart;
- session performance/operations table;
- projection freshness / eventual-consistency disclosure;
- an unmistakable **Stripe Test Mode / Demo Analytics** label around financial data.

Do **not** turn the existing `AdminPortalComponent` into a monolithic analytics implementation. Build a dedicated child feature component and embed it in the portal.

The current frontend has no charting dependency. For Phase 14, use native Angular template + SVG/CSS primitives rather than adding an unapproved chart library merely for convenience.

P14-006 adds interactive filters, CSV export, and advanced operational states. P14-005 delivers a correct default 30-day dashboard first.

---

## 3. Critical Invariants & Failure Modes

### 3.1 Invariants

- [ ] Existing `adminGuard` remains required for the `/admin` route; backend ADMIN authorization remains authoritative.
- [ ] Frontend does not calculate business semantics that the API already defines differently.
- [ ] Money uses API minor units and currency; each currency is rendered separately.
- [ ] No UI card adds RON + EUR + another currency into one number.
- [ ] All financial sections visibly show `Test Mode`/`Demo`; no styling may make test revenue look like production revenue.
- [ ] `ratio=null` is displayed as `Not available`/`—`, not `0%`.
- [ ] Eventual consistency/freshness is disclosed; projected data is not labeled as real-time transactional truth.
- [ ] Missing title/session metadata falls back safely to stable IDs or `Unknown label`, never triggers direct browser calls to service-internal endpoints.
- [ ] No Grafana iframe/embed is used as the product dashboard.
- [ ] No new chart package is added in this task.
- [ ] SVG chart has an accessible text/table equivalent; meaning is not encoded by color alone.
- [ ] Loading, empty, and error states do not show stale zeros that look like real business data.
- [ ] Component uses `ChangeDetectionStrategy.OnPush` and current Angular standalone/signals patterns.
- [ ] API subscriptions are cancellable/cleaned up; no leak from repeated component creation.

### 3.2 Failure Modes to Prevent

- browser computes `netRevenue` from formatted decimal strings and introduces rounding errors;
- multi-currency values collapsed into one KPI;
- empty API response displayed as `0 revenue` while request actually failed;
- test Stripe revenue shown without disclosure;
- huge existing admin portal component gets more nested subscriptions/state and becomes fragile;
- chart crashes when all values are zero;
- SVG division by zero when max series value is zero;
- chart inaccessible to keyboard/screen readers;
- frontend calls Event Service for each analytics row to resolve labels;
- raw backend error body or stack trace shown to user;
- non-admin route behavior regresses.

---

## 4. Dependencies / Prerequisites

- P14-004 API contracts complete and stable.
- Existing admin portal remains on `frontend/src/app/features/admin/admin-portal/` and uses standalone/OnPush/signals.
- Existing API-service convention uses relative gateway URLs such as `/api/admin/events`; analytics must use `/api/admin/analytics` similarly.
- Existing Angular Material/Tailwind/shared skeleton patterns should be reused where appropriate.

Before coding, fetch the actual P14-004 DTO/OpenAPI definitions from the implemented backend. Do not type frontend models from this task's examples if backend field names changed during reviewed implementation.

---

## 5. Exact File Inventory

Expected changes:

- `[NEW]` `frontend/src/app/models/admin-analytics.model.ts`
- `[NEW]` `frontend/src/app/services/admin-analytics-api.service.ts`
- `[NEW]` `frontend/src/app/services/admin-analytics-api.service.spec.ts`
- `[NEW]` `frontend/src/app/features/admin/analytics/admin-analytics-dashboard/admin-analytics-dashboard.component.ts`
- `[NEW]` `frontend/src/app/features/admin/analytics/admin-analytics-dashboard/admin-analytics-dashboard.component.html`
- `[NEW]` `frontend/src/app/features/admin/analytics/admin-analytics-dashboard/admin-analytics-dashboard.component.scss`
- `[NEW]` `frontend/src/app/features/admin/analytics/admin-analytics-dashboard/admin-analytics-dashboard.component.spec.ts`
- `[NEW]` `frontend/src/app/features/admin/analytics/analytics-line-chart/analytics-line-chart.component.ts`
- `[NEW]` `frontend/src/app/features/admin/analytics/analytics-line-chart/analytics-line-chart.component.html`
- `[NEW]` `frontend/src/app/features/admin/analytics/analytics-line-chart/analytics-line-chart.component.scss`
- `[NEW]` `frontend/src/app/features/admin/analytics/analytics-line-chart/analytics-line-chart.component.spec.ts`
- `[MODIFY]` `frontend/src/app/features/admin/admin-portal/admin-portal.component.ts` — import the child dashboard only; do not fold its HTTP state into portal state.
- `[MODIFY]` `frontend/src/app/features/admin/admin-portal/admin-portal.component.html` — embed analytics section in a clear location.

If a generic existing chart/data-table primitive is present by implementation time and fully satisfies accessibility/test requirements, reuse it rather than duplicating it. Do not add a third-party chart dependency without a new explicit decision.

---

## 6. Technical Specifications & Contracts

### 6.1 Typed Frontend Models

Mirror P14-004 backend contracts with explicit TypeScript interfaces/types. At minimum model:

```text
AnalyticsSummary
AnalyticsReservationSummary
AnalyticsTicketSummary
AnalyticsPaymentSummary
MoneyMetric
RateMetric
ProjectionFreshness
AnalyticsMetric enum/union
AnalyticsTimeSeries
AnalyticsCountSeries / AnalyticsMoneySeries
AnalyticsDailyPoint
EventSessionAnalytics
TopAnalyticsItem (if rendered now or prepared for later)
PagedResult<EventSessionAnalytics>
```

Money model must retain integer minor units:

```ts
interface MoneyMetric {
  currency: string;
  grossMinor: number;
  refundedMinor: number;
  netMinor: number;
  testMode: boolean;
}
```

JavaScript `number` is safe for the expected demo values but API/service tests must not convert minor units to floating arithmetic before display. If future values can exceed `Number.MAX_SAFE_INTEGER`, that requires an API contract change (e.g. decimal string), not ad-hoc frontend BigInt serialization in this phase.

### 6.2 API Service

`AdminAnalyticsApiService` baseline methods:

```ts
getSummary(params?: AnalyticsQueryParams): Observable<AnalyticsSummary>
getTimeSeries(metric: AnalyticsMetric, params?: AnalyticsQueryParams): Observable<AnalyticsTimeSeries>
getSessions(params?: AnalyticsSessionQueryParams): Observable<PagedResult<EventSessionAnalytics>>
getSession(sessionId: string): Observable<EventSessionAnalytics>
getTop(...): Observable<...> // implement when dashboard uses it; do not add dead methods only for symmetry
```

Use `HttpParams`; trim/omit optional filters; do not manually concatenate query strings.

Default dashboard may omit `from/to` and rely on backend's fixed 30-day default, but all API service types must support the explicit dates needed by P14-006.

### 6.3 Dashboard Composition

`AdminAnalyticsDashboardComponent` owns only analytics state. Existing Admin Portal keeps existing users/venues/system health behavior independently.

Recommended dashboard state:

```text
summaryState: idle/loading/success/error
trendState: idle/loading/success/error
sessionsState: idle/loading/success/error
selectedTrendMetric default NET_REVENUE or GROSS_REVENUE
```

Use signals/computed state or an RxJS stream converted to signals according to current Angular 22 project style. Avoid nested subscriptions.

A failure in one widget should not erase successful data from all unrelated widgets. For example, if session table call fails while summary succeeds, show summary + a table-local error state.

### 6.4 KPI Cards

Display at minimum:

- reservations created;
- confirmed reservations / successful payment conversion rate;
- expired reservations / expiration rate;
- tickets issued;
- tickets scanned;
- refund count / refund rate;
- one financial card group per currency showing gross, refunded, net.

For each rate:

- show formatted percent only when API `ratio != null`;
- optionally show numerator/denominator in secondary text for clarity;
- clamp only display precision, **never** clamp an invalid backend ratio into 0..100 to hide a backend bug.

For money:

- convert minor units using a formatter that knows currency fractional digits via `Intl.NumberFormat` where possible;
- do not assume every currency has exactly 2 fraction digits in formatter logic;
- keep raw integer minor unit value intact in model/state;
- show `Test Mode` badge adjacent to the financial group, not hidden in a tooltip.

### 6.5 Native SVG Trend Chart

Build a small reusable line/bar chart sufficient for Phase 14 rather than a full chart framework.

Requirements:

- input is already date-sorted P14-004 series;
- handles 1 point, all-zero points, positive counts, and positive minor-unit money;
- no divide-by-zero when max == min or max == 0;
- x coordinates deterministic across dates;
- y scaling includes zero baseline for these non-negative metrics;
- for multi-currency money, render separate clearly labeled series or a currency selector; never add them together;
- visual distinction uses labels/line patterns/markers in addition to color where multiple series are shown;
- SVG includes `<title>` and `<desc>` or equivalent accessible labeling;
- provide a compact screen-reader/table representation of date/value points;
- responsive viewBox; no fixed desktop-only width;
- no animation that blocks testing or creates motion-accessibility issues; honor reduced motion if animation is added.

Do not silently smooth/interpolate points in a way that implies values not returned by the API.

### 6.6 Session Operations Table

Render P14-004 session rows with:

```text
Event / session label
Starts at
Reservations confirmed / expired
Tickets issued / scanned / revoked
Payment success/failure/refunds
Revenue by currency
Occupancy when available
Attendance when available
Projection recency
```

Rules:

- fallback label: event title -> session label -> shortened `eventSessionId`;
- `capacitySnapshot=null` -> occupancy `—` with accessible explanation;
- rate null -> `—`, not 0%;
- status is projected/eventually consistent; do not style it as an authoritative live incident state;
- responsive: table can horizontally scroll or switch to stacked cards on narrow widths without truncating essential values;
- no client-side unlimited fetch to render all sessions; use server page returned by API. P14-006 adds paging/filter controls.

### 6.7 Freshness / Eventual Consistency

Show a small dashboard-level disclosure:

```text
Analytics are event-driven and eventually consistent.
Last projected event: <time or unavailable>
```

Do not call this exact Kafka lag. If no events exist, show `No projected events yet` instead of a fake recent timestamp.

### 6.8 Stripe Test Mode Disclosure

Financial area must include persistent visible text such as:

```text
Stripe Test Mode — demo transactions only
```

It should remain visible on desktop/mobile and not depend on hover.

If backend ever returns `testMode=false` unexpectedly during Phase 14, do not silently remove the badge and imply production readiness. Treat it as an unsupported contract discrepancy and render a safe generic payment-mode indicator or error state until product policy changes.

### 6.9 Loading / Error / Empty Baseline

P14-005 baseline states:

- loading: skeletons/placeholders, not numeric zeros;
- API error: concise widget-local message + retry action where practical;
- successful empty summary: valid zero counts and `No analytics data in this period` contextual message;
- successful empty session page: empty-state row/card;
- no projected freshness: explicit unavailable state.

P14-006 adds filter-specific/stale/unavailable operational behavior.

---

## 7. Step-by-Step Implementation Sequence

1. Read implemented P14-004 DTO/OpenAPI contracts and create exact TypeScript models.
2. Implement typed `AdminAnalyticsApiService` and HTTP tests.
3. Create isolated analytics dashboard component; do not modify existing admin data-loading logic yet.
4. Implement summary/KPI state with independent loading/error/success semantics.
5. Implement minor-unit currency formatter and rate formatter with unavailable behavior.
6. Implement native SVG trend chart + accessible data equivalent.
7. Implement session table with safe metadata/rate/capacity fallbacks.
8. Add Test Mode and eventual-consistency disclosures.
9. Embed dashboard child component in Admin Portal.
10. Add component tests for success, loading, empty, error, multi-currency, null rates/capacity, all-zero chart, and Test Mode disclosure.
11. Run full frontend tests/build and manually inspect responsive layout at representative narrow/wide widths.

---

## 8. Test Requirements

### 8.1 API Service

- [ ] calls exact `/api/admin/analytics/...` paths;
- [ ] creates `HttpParams` correctly;
- [ ] omits undefined optional filters;
- [ ] maps typed responses without altering minor-unit values.

### 8.2 Dashboard State

- [ ] initial loading does not show fake zero KPIs;
- [ ] summary success renders exact counts/rates;
- [ ] session failure does not hide successful summary/trend;
- [ ] retry reissues only appropriate failed widget request where implemented;
- [ ] empty success has a distinct empty state from network error.

### 8.3 Money / Test Mode

- [ ] two currencies render as two financial groups/series, never one total;
- [ ] gross/refunded/net raw API values display correctly;
- [ ] `Stripe Test Mode — demo transactions only` remains visible;
- [ ] null rate renders `—`/unavailable.

### 8.4 Chart

- [ ] 30-day count series renders without errors;
- [ ] all-zero series renders valid zero baseline, no NaN SVG attributes;
- [ ] single point renders;
- [ ] multi-currency series are labeled distinctly;
- [ ] SVG has accessible title/description and data equivalent;
- [ ] no chart-library dependency is added to `package.json`.

### 8.5 Session Table

- [ ] missing label falls back to stable ID;
- [ ] capacity null -> occupancy unavailable;
- [ ] attendance null -> unavailable;
- [ ] test-mode money remains labeled in table/context;
- [ ] responsive template retains essential data.

### 8.6 Admin Portal Regression

- [ ] existing venue/user/system health cards still load as before;
- [ ] admin route guard remains intact;
- [ ] adding analytics child does not tie existing portal `isLoading` to analytics requests.

---

## 9. Verification Commands

```bash
cd frontend
npm test -- --watch=false
npm run build
```

If the repo's Karma/Chrome setup requires its existing CI flags, use those exact current flags rather than introducing a second test runner.

Manual verification checklist:

```text
/admin desktop width
/admin mobile/narrow width
multi-currency fixture
empty fixture
analytics API error fixture
all-zero time series
null capacity/rates
```

---

## 10. Independent Review Focus

Review should inspect:

- frontend models exactly match P14-004;
- no business-semantic recomputation/drift;
- currency/minor-unit handling;
- Test Mode visibility;
- loading vs error vs empty distinction;
- component isolation from existing Admin Portal state;
- chart math for zero/single/multi-series;
- accessibility and responsive behavior;
- no hidden cross-service browser fanout;
- no unnecessary dependency addition.

---

## 11. Acceptance Criteria

- [ ] Admin Portal contains a dedicated analytics child feature, not monolithic new logic in `AdminPortalComponent`.
- [ ] Typed API service/models match P14-004.
- [ ] KPI cards, multi-currency revenue, rates, trend chart, and session table render correctly.
- [ ] Stripe Test Mode and eventual consistency are visibly disclosed.
- [ ] Native chart is responsive, accessible, and safe for zero/single-point data.
- [ ] Loading/error/empty states are distinct.
- [ ] Existing admin portal behavior remains green.
- [ ] Frontend tests/build and independent UI review pass.

---

## 12. Execution Entry Point

```text
Implement TASK-P14-005 using the SeatFlow autonomous orchestration workflow.
Consume the implemented P14-004 API exactly. Keep analytics isolated in a child component and do not add a chart dependency or Grafana embed.
```
