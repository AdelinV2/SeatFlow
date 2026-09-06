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
- **Affected Critical Invariants:** `bounded analytics queries; ADMIN-only export; CSV injection safety; no PII; filter consistency; request-race safety; eventual-consistency disclosure`

---

## 2. Objective

Make the Phase 14 dashboard practical for admin use by adding:

- explicit UTC date-range filtering;
- projected event and event-session filters sourced from analytics itself;
- session table pagination controls;
- selectable trend metric;
- bounded server-side CSV export of daily analytics rows;
- robust filter-specific loading/empty/error/unavailable states;
- a documented, offline projection rebuild/replay procedure rather than a dangerous public "rebuild analytics" endpoint.

This task must preserve the core Phase 14 isolation rule: dashboard filtering/export never causes synchronous reads from operational service databases/APIs.

---

## 3. Critical Invariants & Failure Modes

### 3.1 Invariants

- [ ] Filter-option endpoints and export endpoints are ADMIN-only server-side.
- [ ] Date filtering uses the same UTC, inclusive, maximum-366-day semantics as P14-004.
- [ ] Frontend never interprets local-midnight timestamps as API date boundaries; it sends date-only `YYYY-MM-DD` values.
- [ ] Changing event filter clears an incompatible selected session before issuing filtered data requests.
- [ ] Applying any filter resets server pagination to page 0.
- [ ] Older/slower HTTP responses cannot overwrite a newer filter selection.
- [ ] Filter options come from analytics read-model dimensions/facts, not Event Service fanout.
- [ ] Missing projected labels remain usable via IDs.
- [ ] CSV is generated server-side from bounded analytics queries and contains no PII.
- [ ] CSV cells derived from text snapshots are protected against spreadsheet formula injection.
- [ ] CSV uses deterministic UTF-8/RFC-4180-style quoting and stable columns.
- [ ] Export never silently truncates results; an oversized export fails with a stable error.
- [ ] Export preserves currency as a dedicated column and money in integer minor units.
- [ ] Dashboard never calls a destructive/reset/replay endpoint. Projection rebuild is an operator procedure while the consumer is stopped.
- [ ] "Last projected event" is not mislabeled as exact Kafka lag or guaranteed freshness.
- [ ] Network/unavailable state is visually distinct from a valid zero/empty result.

### 3.2 Failure Modes to Prevent

- stale request for Event A overwrites later Event B results;
- session B remains selected after switching to Event A;
- date filter off by one day because browser timezone converts midnight;
- downloading millions of unbounded rows;
- CSV title beginning `=HYPERLINK(...)` executes as spreadsheet formula;
- multi-currency revenue loses currency identity in CSV;
- filter dropdown calls Event Service and creates hidden coupling;
- successful empty filter result rendered as `Analytics service unavailable`;
- failed request leaves previous figures visible without any stale/error indication;
- frontend generates CSV from only current page and presents it as full export;
- admin rebuild endpoint can truncate production analytics by accidental click;
- group-offset reset occurs while consumers are active and produces undefined rebuild state.

---

## 4. Dependencies / Prerequisites

- P14-004 REST contracts complete.
- P14-005 default dashboard complete.
- P14-003 facts include safe projected event/session display metadata where available.
- Existing common error/security conventions remain authoritative.

---

## 5. Exact File Inventory

### 5.1 Backend expected changes

- `[MODIFY]` `.../web/controller/AdminAnalyticsController.java`
- `[MODIFY]` analytics query service/repositories
- `[NEW]` `.../web/dto/response/AnalyticsEventFilterOptionResponse.java`
- `[NEW]` `.../web/dto/response/AnalyticsSessionFilterOptionResponse.java`
- `[NEW]` `.../service/AnalyticsCsvExportService.java`
- `[NEW]` `.../service/impl/AnalyticsCsvExportServiceImpl.java` if interface/impl convention is retained
- `[NEW]` `.../web/csv/AnalyticsCsvWriter.java` or equivalent focused utility
- `[NEW/MODIFY]` controller/service/security/export tests
- `[NEW]` `backend/services/analytics-service/README.md` if the service does not already have operational documentation; include rebuild/replay runbook there

### 5.2 Frontend expected changes

- `[MODIFY]` `frontend/src/app/models/admin-analytics.model.ts`
- `[MODIFY]` `frontend/src/app/services/admin-analytics-api.service.ts`
- `[MODIFY]` `frontend/src/app/services/admin-analytics-api.service.spec.ts`
- `[MODIFY]` analytics dashboard TS/HTML/SCSS/spec from P14-005
- `[NEW]` focused filter/export child component only if the dashboard becomes materially clearer by separation; do not split purely for ceremony

---

## 6. Technical Specifications & Contracts

### 6.1 Event Filter Options

Add:

```http
GET /api/admin/analytics/filter-options/events?from=YYYY-MM-DD&to=YYYY-MM-DD
```

Response sorted deterministically by safe display label then ID:

```json
[
  {
    "eventId": "...",
    "label": "Concert title",
    "firstProjectedSessionStart": "2026-09-10T18:00:00Z"
  }
]
```

Rules:

- only events represented in analytics facts within/relevant to selected range;
- `label` may be null if no event snapshot exists;
- frontend fallback is shortened/stable ID;
- no live Event Service query;
- cap option count at a documented reasonable bound (e.g. 500) and fail/indicate truncation explicitly if exceeded rather than returning an unbounded list. Prefer a backend hard maximum with tests.

### 6.2 Session Filter Options

Add:

```http
GET /api/admin/analytics/filter-options/sessions?from=YYYY-MM-DD&to=YYYY-MM-DD&eventId=<optional UUID>
```

Response:

```json
[
  {
    "eventSessionId": "...",
    "eventId": "...",
    "label": "Evening show",
    "startsAt": "2026-09-10T18:00:00Z"
  }
]
```

Sort by `startsAt ASC NULLS LAST`, then `eventSessionId ASC`.

If `eventId` is selected, only sessions for that projected event are returned. Unknown event filter returns `200 []`.

### 6.3 Filter Application Model

Frontend fields:

```text
fromDate
toDate
eventId | null
eventSessionId | null
trendMetric
sessionPage
sessionPageSize
```

Baseline UX:

- initialize explicit date controls to the same backend default 30-day UTC date range;
- use an injectable date/clock utility in tests rather than hardcoded current dates;
- provide `Apply filters` and `Reset` actions; do not issue a full dashboard refresh on every individual date keystroke;
- selecting Event A refreshes session options and clears a selected session that is not in A;
- selecting a session may imply/set its event only if the option includes a matching eventId and the behavior is explicit/tested;
- `Reset` returns to 30-day range, no event/session filter, page 0, default trend metric;
- filter state remains inside dashboard unless the product already has a consistent query-param state pattern. Do not invent partial URL persistence in this task.

### 6.4 Request-Race / Cancellation Behavior

Create one filter state stream/signal and use cancellable request composition (`switchMap`, Angular signal resource pattern, or equivalent supported pattern).

Required behavior:

```text
apply Filter A -> request A starts
apply Filter B -> request B starts
request B completes
request A completes later
UI must still show B
```

Do not solve this with arbitrary `setTimeout` or by ignoring errors globally.

Independent widgets may still load separately, but each widget must bind response to the active filter version/state.

### 6.5 Session Pagination

Use P14-004 server pagination.

- default 25;
- allowed page sizes: `10, 25, 50, 100` only if backend max remains 100;
- page changes reload only session table, not summary/time series unless shared state implementation makes an equivalent efficient request unavoidable;
- filter change resets page 0;
- never fetch all pages client-side to paginate locally.

### 6.6 Trend Metric Selector

Expose only metrics implemented by P14-004.

Recommended UI options:

```text
Net revenue
Gross revenue
Tickets issued
Tickets scanned
Reservations created
Successful payments
```

For money metrics with multiple currencies, use separate series or a clearly selected currency. Do not stack/add them into one value.

### 6.7 CSV Export Endpoint

Add:

```http
GET /api/admin/analytics/export/daily.csv?from=...&to=...&eventId=...&eventSessionId=...
```

Why daily rows: `daily_sales_metrics` already has a stable `(date,event,session,currency)` grain, so counts and money can be exported without repeating session-level counts across arbitrary currency rows.

CSV columns in exact stable order:

```text
metric_date
event_id
event_session_id
event_title
session_label
currency
reservations_created
reservations_confirmed
reservations_expired
payments_succeeded
payment_failures
refunds_completed
tickets_issued
tickets_revoked
tickets_scanned
gross_revenue_minor
refunded_revenue_minor
net_revenue_minor
stripe_test_mode
last_projected_event_at
```

Rules:

- one row per daily aggregate grain;
- money remains integer minor units;
- `stripe_test_mode` is literal `true` for Phase 14 financial rows;
- no email/name/address/user ID/payment method/raw provider secret;
- deterministic order: `metric_date ASC, event_id ASC, event_session_id ASC, currency ASC`;
- same date/filter validation as P14-004;
- maximum export rows: `10_000` (or a smaller explicit reviewed constant). Query one extra row to detect overflow and return a stable `ANALYTICS_EXPORT_TOO_LARGE` error; never silently truncate;
- content type `text/csv; charset=UTF-8`;
- `Content-Disposition: attachment; filename="seatflow-analytics-<from>-<to>.csv"` with server-generated safe filename;
- do not accept a client-supplied filename/path;
- write/stream rows without building an unbounded giant string.

### 6.8 CSV Escaping and Formula-Injection Protection

Implement one tested CSV writer/escaping policy.

For every text cell (`event_title`, `session_label`, and any future text field):

1. normalize null -> empty cell;
2. protect formula-leading content after leading whitespace according to policy. Cells whose first meaningful character is one of `=`, `+`, `-`, `@` must be prefixed with a single quote `'` before CSV escaping;
3. escape `"` as `""`;
4. quote any field containing comma, quote, CR, or LF;
5. preserve UTF-8 text;
6. never write raw CR/LF in an unquoted field.

IDs/numeric/date fields are generated from typed server values and not user-provided free text.

Add explicit tests with titles such as:

```text
=HYPERLINK("https://example.invalid")
+SUM(1,1)
@cmd
Normal, title
Title "quoted"
multiline\nname
```

### 6.9 Frontend Export

`AdminAnalyticsApiService.exportDailyCsv(filters)` should request a `Blob` and trigger download using the same safe object-URL cleanup pattern already used for ticket PDFs.

UI:

- button text `Export CSV`;
- disabled while exporting;
- export uses **currently applied** filters, not uncommitted form edits;
- filename may use trusted `Content-Disposition` if parsing is safe, otherwise frontend uses a fixed safe fallback;
- export error shows concise message and does not navigate away;
- do not generate CSV from currently visible table page.

### 6.10 Operational UX States

Distinguish at least:

1. **Initial loading** — no prior data yet.
2. **Refreshing filters** — keep prior content only if clearly marked as refreshing; do not present it as matching the new filters before response completes.
3. **Valid empty result** — request succeeded but selected range/filter has no projected data.
4. **Analytics unavailable** — network/gateway `5xx` or connection failure; show `Analytics temporarily unavailable` with retry.
5. **Unauthorized/forbidden** — follow existing global auth behavior; do not mislabel as empty analytics.
6. **Validation error** — show date/filter validation and do not issue request when preventable client-side; still handle backend rejection.
7. **No projection freshness** — service works but has not processed a relevant event yet.
8. **Projection recency** — show `Last projected event ...`; do not automatically label it "Kafka lag" or "stale" based solely on wall-clock age.
9. **Export too large** — explain that user must narrow filters/date range.

### 6.11 Projection Rebuild / Replay Runbook

Do **not** add an HTTP endpoint that truncates analytics or resets Kafka offsets.

Document a controlled operator procedure in analytics-service README. It must require analytics consumers to be stopped before offset reset.

Conceptual procedure:

```text
1. Stop analytics-service instances.
2. Confirm no analytics-service-v1 consumer is active.
3. Truncate only seatflow_analytics read-model tables, including processed_events, in a documented FK-safe/order-safe transaction or recreate the analytics DB.
4. Reset analytics-service-v1 offsets for the subscribed reservation/payment/ticket/event topics to earliest retained offsets using Kafka admin tooling.
5. Restart analytics-service.
6. Observe consumer errors/lag/processed metrics until replay settles.
7. Verify representative dashboard totals against deterministic fixtures/known source events.
```

Warnings:

- Kafka can rebuild only retained history; it is not guaranteed to reconstruct domain state older than retention.
- Never query operational DBs as an undocumented fallback backfill.
- Changing group ID to force replay is an explicit operator decision and must not happen on every deploy.
- Do not reset offsets while consumers are active.
- Production execution requires normal operational approval/backup practices; README is a technical runbook, not an automatic startup action.

---

## 7. Step-by-Step Implementation Sequence

1. Add backend event/session filter-option queries/endpoints with bounds and ADMIN security.
2. Add daily CSV export query and stable DTO/projection.
3. Implement tested CSV escaping/formula-injection protection and row-limit detection.
4. Add backend tests for auth, bounds, multi-currency, CSV headers/order/escaping/overflow.
5. Extend frontend models/API service for filter options and Blob export.
6. Implement applied-filter state, date controls, event/session dependency behavior, metric selector, and reset.
7. Implement cancellable/race-safe widget refresh.
8. Add session pagination controls.
9. Add export button and error/oversize behavior.
10. Implement distinct loading/refresh/empty/unavailable/validation/freshness states.
11. Add offline rebuild/replay README runbook.
12. Run backend/frontend tests and manually test rapid filter switching plus malicious CSV text fixtures.

---

## 8. Test Requirements

### 8.1 Backend Filter Options

- [ ] event/session options are ADMIN-only;
- [ ] date range follows P14-004 validation;
- [ ] eventId limits session options;
- [ ] unknown projected event returns empty list;
- [ ] label may be null without failure;
- [ ] deterministic ordering;
- [ ] option-count bound enforced.

### 8.2 CSV

- [ ] exact header order;
- [ ] exact deterministic row order;
- [ ] all monetary fields are integer minor units + currency;
- [ ] `stripe_test_mode=true` present;
- [ ] no PII columns;
- [ ] comma/quote/newline escaped correctly;
- [ ] formula-leading text neutralized;
- [ ] 10,000 rows accepted if that is configured max;
- [ ] 10,001 detected/rejected without silent truncation;
- [ ] non-admin cannot export;
- [ ] two currencies remain separate rows.

### 8.3 Frontend Filters

- [ ] reset returns exact default filter state;
- [ ] filter apply resets session page;
- [ ] changing event clears incompatible session;
- [ ] date-only strings sent without timezone conversion;
- [ ] rapid A -> B filter change cannot end displaying late A response;
- [ ] page change does not unnecessarily reload unrelated widgets where implementation separates them.

### 8.4 Frontend Operational States

- [ ] successful empty != API error;
- [ ] 5xx shows unavailable + retry;
- [ ] backend validation shows actionable filter error;
- [ ] no freshness shows `No projected events yet`;
- [ ] recency is not labeled exact Kafka lag;
- [ ] export too large prompts filter narrowing;
- [ ] export button cannot start duplicate concurrent downloads.

### 8.5 Rebuild Runbook Review

- [ ] explicitly stops consumers before offset reset;
- [ ] resets only analytics consumer group/topics;
- [ ] warns about Kafka retention;
- [ ] does not suggest cross-database backfill;
- [ ] no automatic/destructive startup rebuild behavior added.

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
rapidly apply two different filters and confirm latest wins
switch event with a selected session and confirm session resets
export CSV with comma/quote/newline/formula-like event titles
attempt export as non-admin
attempt export beyond row limit
simulate analytics 5xx and distinguish from valid empty data
```

---

## 10. Independent Review Focus

Review must prioritize:

- CSV injection/escaping and absence of PII;
- ADMIN security on export/filter options;
- bounded export and no silent truncation;
- date/timezone consistency;
- frontend response-race handling;
- event/session filter dependency correctness;
- no operational-service fanout;
- honest freshness wording;
- rebuild runbook safety.

---

## 11. Acceptance Criteria

- [ ] Admin can filter analytics by UTC date range, projected event, and projected session.
- [ ] Session pagination and trend metric selection work without unbounded client loads.
- [ ] Filter HTTP races cannot show results for an older selection.
- [ ] CSV export is server-side, bounded, deterministic, currency-safe, PII-free, and formula-injection protected.
- [ ] Loading/refresh/empty/unavailable/validation/freshness/export-too-large states are distinct.
- [ ] No destructive analytics rebuild endpoint exists.
- [ ] Offline replay/rebuild procedure is documented safely.
- [ ] Backend/frontend tests and independent export/security review pass.

---

## 12. Execution Entry Point

```text
Implement TASK-P14-006 using the SeatFlow autonomous orchestration workflow.
Treat filter race-safety, date semantics, CSV injection protection, export bounds, and rebuild safety as acceptance-critical requirements.
```
