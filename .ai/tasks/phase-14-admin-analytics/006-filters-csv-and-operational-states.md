# TASK-P14-006: Add Analytics Filters, Safe CSV Export, and Operational UX States

## 1. Task Metadata

- **Task ID:** `TASK-P14-006`
- **Git Branch:** `feat/p14-006-analytics-filters-export`
- **Target Module:** `backend/services/analytics-service`, `frontend/src/app/features/admin/analytics`, `frontend/src/app/services`
- **Phase:** `Phase 14 - Admin Analytics & Operations Dashboard`
- **Related Specs:** `.ai/tasks/phase-14-admin-analytics/000-phase-overview.md`, P14-004 and P14-005
- **Related ADRs:** `.ai/decisions/ADR-013-analytics-event-driven-read-model.md`
- **Status:** `READY FOR IMPLEMENTATION`

### Orchestration Metadata

- **Complexity:** `4`
- **Failure Risk:** `High`
- **Verification Strength:** `Strong`
- **Required Review Depth:** `Critical for export/security; standard for UI`
- **Preferred Workflow:** `full`
- **Affected Critical Invariants:** `bounded analytics queries; ADMIN-only export; CSV injection safety; aggregate-grain correctness; no PII; filter consistency; request-race safety; eventual-consistency disclosure`

---

## 2. Objective

Make the Phase 14 dashboard practical for admin use by adding:

- explicit UTC date-range filtering;
- projected event and event-session filters sourced from analytics itself;
- session table pagination controls;
- selectable trend metric;
- bounded server-side CSV export that preserves the split between currency-neutral operations and currency-specific finance;
- robust filter-specific loading/empty/error/unavailable states;
- a documented offline projection rebuild/replay procedure instead of a destructive HTTP endpoint.

Dashboard filtering/export must never cause synchronous reads from operational service databases/APIs.

---

## 3. Critical Invariants & Failure Modes

### 3.1 Invariants

- [ ] Filter-option and export endpoints are ADMIN-only server-side.
- [ ] Date filtering uses P14-004 UTC, inclusive, maximum-366-day semantics.
- [ ] Frontend sends date-only `YYYY-MM-DD`; browser timezone conversion must not shift boundaries.
- [ ] Changing event filter clears an incompatible selected session before filtered data requests.
- [ ] Applying filters resets server pagination to page 0.
- [ ] Older/slower HTTP responses cannot overwrite a newer filter selection.
- [ ] Filter options come from analytics facts, not Event Service fanout.
- [ ] Missing labels fall back to IDs.
- [ ] Filter-option responses are explicitly bounded and report truncation; no hidden truncation.
- [ ] CSV is generated server-side from bounded analytics queries and contains no PII.
- [ ] CSV text snapshots are protected against spreadsheet formula injection.
- [ ] CSV uses deterministic UTF-8/RFC-4180-style quoting and stable columns.
- [ ] CSV keeps operational rows currency-neutral and financial rows currency-specific; it never repeats operational counts once per currency.
- [ ] Export never silently truncates results; oversized export fails with a stable error.
- [ ] Dashboard has no destructive reset/replay endpoint. Rebuild is an operator procedure with consumers stopped.
- [ ] "Last projected event" is not mislabeled as exact Kafka lag.
- [ ] Network/unavailable state is distinct from valid zero/empty data.

### 3.2 Failure Modes to Prevent

- late Event A request overwrites Event B result;
- session B remains selected after switching to Event A;
- date off by one due local timezone;
- filter endpoint silently returns first N values without telling UI;
- CSV repeats reservation/ticket counts on RON and EUR revenue rows;
- CSV title beginning `=HYPERLINK(...)` executes in spreadsheet;
- CSV loses currency identity;
- unbounded export;
- successful empty result rendered as service unavailable;
- failed refresh leaves old figures looking like they match new filters;
- frontend exports only current page but labels it full export;
- rebuild endpoint/automatic reset can truncate analytics accidentally;
- consumer offsets reset while consumers active.

---

## 4. Dependencies / Prerequisites

- P14-004 REST contracts complete.
- P14-005 default dashboard complete.
- P14-003 facts include projected event/session metadata where available.
- Existing common-domain error/security conventions remain authoritative.

---

## 5. Exact File Inventory

### Backend

- `[MODIFY]` analytics admin controller/query service/repositories
- `[NEW]` `.../web/dto/response/AnalyticsEventFilterOptionResponse.java`
- `[NEW]` `.../web/dto/response/AnalyticsSessionFilterOptionResponse.java`
- `[NEW]` `.../web/dto/response/AnalyticsFilterOptionsResponse.java`
- `[NEW]` `.../service/AnalyticsCsvExportService.java`
- `[NEW]` service implementation if current convention uses one
- `[NEW]` `.../web/csv/AnalyticsCsvWriter.java` or equivalent focused utility
- `[NEW/MODIFY]` controller/service/security/export tests
- `[NEW]` `backend/services/analytics-service/README.md` if absent; include rebuild/replay runbook

### Frontend

- `[MODIFY]` `frontend/src/app/models/admin-analytics.model.ts`
- `[MODIFY]` `frontend/src/app/services/admin-analytics-api.service.ts`
- `[MODIFY]` API service spec
- `[MODIFY]` P14-005 analytics dashboard TS/HTML/SCSS/spec
- `[NEW]` a focused filter/export child only if it materially improves maintainability; do not split for ceremony

---

## 6. Technical Specifications & Contracts

### 6.1 Bounded Filter-Options Envelope

Both filter endpoints return:

```json
{
  "items": [],
  "totalProjected": 0,
  "truncated": false
}
```

Hard maximum returned items:

```text
500
```

Behavior when more than 500 match:

- query deterministic first 501 to detect overflow;
- return first 500 sorted items;
- `totalProjected` is exact only if query computes it efficiently; otherwise use a separate bounded count query because 500 is small. Do not fake the value;
- `truncated=true`;
- frontend displays `Showing first 500 options — narrow the date range to see more.`

No silent truncation and no unbounded dropdown.

### 6.2 Event Filter Options

```http
GET /api/admin/analytics/filter-options/events?from=YYYY-MM-DD&to=YYYY-MM-DD
```

Item:

```json
{
  "eventId": "...",
  "label": "Concert title",
  "firstProjectedSessionStart": "2026-09-10T18:00:00Z"
}
```

Sort:

```text
COALESCE(label, '') ASC, eventId ASC
```

Rules:

- only projected events relevant to selected range;
- label nullable;
- no Event Service query.

### 6.3 Session Filter Options

```http
GET /api/admin/analytics/filter-options/sessions?from=YYYY-MM-DD&to=YYYY-MM-DD&eventId=<optional UUID>
```

Item:

```json
{
  "eventSessionId": "...",
  "eventId": "...",
  "label": "Evening show",
  "startsAt": "2026-09-10T18:00:00Z"
}
```

Sort:

```text
startsAt ASC NULLS LAST, eventSessionId ASC
```

Unknown projected event -> `200` with empty envelope.

### 6.4 Frontend Applied-Filter Model

Fields:

```text
fromDate
toDate
eventId | null
eventSessionId | null
trendMetric
sessionPage
sessionPageSize
```

UX:

- initialize visible date controls to same 30-day UTC default;
- use a testable date/clock utility;
- `Apply filters` performs dashboard refresh; do not refresh on each date keystroke;
- changing event refreshes session options and clears incompatible selected session;
- selecting a session may set its event only if that behavior is explicit and tested; default preference is keep event/session consistency from selected option;
- `Reset` -> 30-day default, no event/session, page 0, default metric;
- keep draft form state separate from currently applied filters so CSV and displayed results always correspond to applied filters;
- no new partial URL-query persistence unless existing project convention already provides it.

### 6.5 Request Race / Cancellation

Use `switchMap`, Angular resource/signal cancellation, or equivalent supported pattern.

Required:

```text
A starts -> B starts -> B completes -> A completes late -> UI remains B
```

Each widget response must be associated with active applied-filter state. No arbitrary timeout solution.

### 6.6 Session Pagination

- server pagination only;
- default 25;
- UI page-size options `10,25,50,100`;
- filter change -> page 0;
- page change should reload session table only when current component architecture separates calls;
- never fetch all pages client-side.

### 6.7 Trend Metric Selector

Expose only P14-004 implemented metrics:

```text
NET_REVENUE
GROSS_REVENUE
TICKETS_ISSUED
TICKETS_SCANNED
RESERVATIONS_CREATED
PAYMENTS_SUCCEEDED
```

For money metrics, preserve separate currency series or require a clear currency selection. Never stack/add currencies into a total.

### 6.8 CSV Export Endpoint

```http
GET /api/admin/analytics/export/daily.csv?from=...&to=...&eventId=...&eventSessionId=...
```

The export is a **long-form union** of two aggregate grains. It uses `row_type` so operational counts are never duplicated by currency.

Exact stable columns:

```text
row_type
metric_date
event_id
event_session_id
event_title
session_label
currency
reservations_created
reservations_confirmed
reservations_expired
operational_payments_succeeded
payments_with_failure
operational_refunds_completed
tickets_issued
tickets_revoked
tickets_scanned
financial_payments_succeeded
financial_refunds_completed
gross_revenue_minor
refunded_revenue_minor
net_revenue_minor
stripe_test_mode
last_projected_event_at
```

#### `row_type=OPERATIONS`

One row per matching `daily_operational_metrics` grain.

- `currency` empty;
- operational count columns populated;
- financial payment/refund/money columns empty;
- `stripe_test_mode` empty because the row itself contains no money.

#### `row_type=REVENUE`

One row per matching `daily_revenue_metrics` grain.

- `currency` required;
- operational count columns empty;
- `financial_payments_succeeded`, `financial_refunds_completed`, gross/refunded/net populated;
- `stripe_test_mode=true`.

This prevents a spreadsheet user from accidentally summing the same reservation/ticket counts once for RON and again for EUR.

`event_title`/`session_label` come from analytics session facts and may be empty. No source-service lookup.

### 6.9 CSV Bounds / Ordering / Headers

Maximum total exported rows across both row types:

```text
10_000
```

Implementation:

- count/detect total matching union size before streaming or query `10_001` through a safe combined approach;
- `10_000` accepted;
- `10_001+` -> `ANALYTICS_EXPORT_TOO_LARGE`, no partial file;
- never silently truncate.

Stable ordering:

```text
metric_date ASC,
event_id ASC,
event_session_id ASC,
row_type OPERATIONS before REVENUE,
currency ASC NULLS FIRST
```

HTTP:

```text
Content-Type: text/csv; charset=UTF-8
Content-Disposition: attachment; filename="seatflow-analytics-<from>-<to>.csv"
```

Filename is server-generated from validated dates; client cannot supply path/name.

### 6.10 CSV Escaping / Formula Injection

For text snapshot cells only (`event_title`, `session_label`, any future user-controllable text):

1. null -> empty;
2. inspect first non-whitespace character;
3. if `=`, `+`, `-`, or `@`, prefix the cell value with single quote `'`;
4. escape `"` as `""`;
5. quote fields containing comma, quote, CR, or LF;
6. preserve UTF-8;
7. never emit raw CR/LF unquoted.

Typed UUID/date/numeric values are emitted from typed server values, not passed through arbitrary text.

Required malicious/edge fixtures:

```text
=HYPERLINK("https://example.invalid")
+SUM(1,1)
@cmd
-1+2
Normal, title
Title "quoted"
multiline\nname
```

### 6.11 Frontend Export

`AdminAnalyticsApiService.exportDailyCsv(appliedFilters)` requests `Blob`.

- button `Export CSV`;
- uses currently **applied** filters, not draft edits;
- disabled while export request active;
- follow existing ticket-PDF object URL creation/revocation pattern;
- safe fallback filename if trusted header cannot be parsed;
- export error stays in dashboard;
- `ANALYTICS_EXPORT_TOO_LARGE` tells admin to narrow range/filters;
- do not build CSV from current table page.

### 6.12 Operational UX States

Distinguish:

1. **Initial loading** — no prior analytics content.
2. **Refreshing filters** — prior content may remain only with explicit refreshing indicator; never claim it matches new draft/applied filter before response.
3. **Valid empty** — successful request, no projected data.
4. **Analytics unavailable** — network/gateway 5xx -> concise unavailable + retry.
5. **Unauthorized/forbidden** — use existing auth behavior, not empty state.
6. **Validation error** — client prevents obvious invalid dates but backend remains authoritative.
7. **No projection freshness** — service works, no relevant event processed yet.
8. **Projection recency** — show last projected event; do not call it Kafka lag/staleness based solely on age.
9. **Filter options truncated** — show 500-item warning and encourage narrower date range.
10. **Export too large** — narrow filters/range.

### 6.13 Projection Rebuild / Replay Runbook

No HTTP reset/rebuild endpoint.

Document controlled operator procedure:

```text
1. Stop every analytics-service instance.
2. Confirm analytics-service-v1 consumer group has no active analytics member.
3. Clear/recreate only seatflow_analytics read-model tables, including processed_events, using documented safe ordering/transaction.
4. Reset analytics-service-v1 offsets for reservation/payment/ticket/event topics to earliest retained offsets with Kafka admin tooling.
5. Restart analytics-service.
6. Observe consumer failure/DLQ/processed/lag metrics until replay settles.
7. Verify representative dashboard totals against known event fixtures.
```

Warnings:

- Kafka rebuilds only retained history;
- no undocumented source-DB backfill;
- group ID changes are explicit operator decisions, never normal deploy behavior;
- never reset offsets while consumers active;
- production requires normal operational approval/backup practices;
- rebuild is never automatic at application startup.

---

## 7. Step-by-Step Implementation Sequence

1. Add bounded event/session filter-option queries/endpoints/envelope.
2. Add long-form OPERATIONS/REVENUE CSV query contract with row bound.
3. Implement tested CSV escaping/formula protection.
4. Add backend auth/bounds/grain/header/order/escaping/overflow tests.
5. Extend frontend types/API for options + Blob export.
6. Implement draft vs applied filters, UTC dates, event/session consistency, metric selector, reset.
7. Implement cancellable/race-safe refresh.
8. Add session pagination controls.
9. Add export and explicit error/oversize behavior.
10. Add truncated-options warning and distinct operational states.
11. Add offline rebuild README runbook.
12. Run backend/frontend tests and manual rapid-switch/malicious-CSV verification.

---

## 8. Test Requirements

### 8.1 Filter Options

- [ ] ADMIN-only;
- [ ] same date validation as P14-004;
- [ ] eventId scopes sessions;
- [ ] unknown event -> empty envelope;
- [ ] labels nullable;
- [ ] deterministic sorting;
- [ ] <=500 -> `truncated=false`;
- [ ] 501 -> 500 items + `truncated=true` + correct totalProjected.

### 8.2 CSV Grain / Currency

Fixture same session with RON + EUR:

- [ ] exactly one OPERATIONS row for the operational date grain;
- [ ] exactly two REVENUE rows when both currencies contribute on same date;
- [ ] reservation/ticket counts appear only in OPERATIONS row;
- [ ] money appears only in REVENUE rows;
- [ ] no mixed-currency total;
- [ ] Test Mode true only on revenue rows.

### 8.3 CSV Security / Bounds

- [ ] exact header order;
- [ ] deterministic row order;
- [ ] no PII;
- [ ] comma/quote/newline escaped;
- [ ] formula-leading text neutralized;
- [ ] 10,000 total rows accepted;
- [ ] 10,001 rejected with no partial body/file;
- [ ] non-admin denied.

### 8.4 Frontend Filters / Races

- [ ] reset exact default;
- [ ] apply resets page;
- [ ] event change clears incompatible session;
- [ ] date-only strings sent unchanged by timezone;
- [ ] A -> B rapid filter change cannot end displaying A;
- [ ] options truncated warning shown;
- [ ] page changes do not fetch all rows locally.

### 8.5 Frontend Operational / Export States

- [ ] successful empty != error;
- [ ] 5xx unavailable + retry;
- [ ] backend validation actionable;
- [ ] no freshness -> `No projected events yet`;
- [ ] recency not labeled exact Kafka lag;
- [ ] export too large -> narrow filters message;
- [ ] export button prevents duplicate concurrent downloads;
- [ ] export uses applied rather than draft filters.

### 8.6 Rebuild Runbook

- [ ] stops consumers before offset reset;
- [ ] resets only analytics group/topics;
- [ ] warns about retention;
- [ ] forbids cross-database backfill;
- [ ] no automatic/destructive startup behavior.

---

## 9. Verification Commands

```bash
cd backend
./mvnw -pl services/analytics-service -am test

cd ../frontend
npm test -- --watch=false
npm run build
```

Manual verification:

```text
rapidly apply A then B filters; B must remain visible
switch event with selected session; session resets
force >500 filter options; truncation warning appears
export same-session RON+EUR fixture; counts appear only in OPERATIONS row
export malicious comma/quote/newline/formula labels
attempt export as non-admin
attempt >10,000 export
simulate analytics 5xx vs valid empty data
```

---

## 10. Independent Review Focus

Prioritize:

- aggregate grain in CSV;
- CSV injection/escaping/no PII;
- ADMIN security;
- export bounds/no partial truncation;
- filter-option explicit truncation;
- UTC/date consistency;
- response-race handling;
- event/session dependency correctness;
- no source-service fanout;
- honest freshness wording;
- rebuild runbook safety.

---

## 11. Acceptance Criteria

- [ ] Admin can filter by UTC date range, projected event/session.
- [ ] Filter options are bounded with explicit truncation metadata.
- [ ] Session pagination/trend selection use server data without unbounded loads.
- [ ] Filter HTTP races cannot show older selection.
- [ ] CSV is server-side, bounded, deterministic, PII-free, formula-safe, and preserves operational-vs-financial grain.
- [ ] Loading/refresh/empty/unavailable/validation/freshness/truncated/export-too-large states are distinct.
- [ ] No destructive rebuild endpoint exists; offline replay procedure is documented.
- [ ] Backend/frontend tests and independent export/security review pass.

---

## 12. Execution Entry Point

```text
Implement TASK-P14-006 using the SeatFlow autonomous orchestration workflow.
Treat filter race-safety, UTC date semantics, explicit option bounds, CSV aggregate grain, injection protection, export limits, and rebuild safety as acceptance-critical.
```
