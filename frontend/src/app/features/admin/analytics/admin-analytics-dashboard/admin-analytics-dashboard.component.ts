import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, catchError, map, of, switchMap } from 'rxjs';
import { HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { PagedResult } from '../../../../models/event.model';
import {
  ANALYTICS_EXPORT_TOO_LARGE_MESSAGE,
  ANALYTICS_FILTER_OPTIONS_TRUNCATED_MESSAGE,
  ANALYTICS_SESSION_PAGE_SIZES,
  AnalyticsAppliedFilters,
  AnalyticsEventFilterOption,
  AnalyticsMetric,
  AnalyticsSessionFilterOption,
  AnalyticsSummary,
  AnalyticsTimeSeries,
  DEFAULT_ANALYTICS_PAGE_SIZE,
  DEFAULT_ANALYTICS_TREND_METRIC,
  EventSessionAnalytics,
  MoneyMetric,
  RateMetric,
} from '../../../../models/admin-analytics.model';
import {
  AdminAnalyticsApiService,
  asApiErrorEnvelope,
  resolveAnalyticsExportFilename,
} from '../../../../services/admin-analytics-api.service';
import {
  defaultAnalyticsDateRange,
  isValidIsoDateRange,
} from '../../../../shared/utils/utc-date';
import { SkeletonLoaderComponent } from '../../../../shared/components/skeleton-loader/skeleton-loader.component';
import { AnalyticsLineChartComponent } from '../analytics-line-chart/analytics-line-chart.component';

/**
 * Widget lifecycle states (TASK-P14-006 §6.12, REV-005). Initial `loading` and
 * post-success `refreshing` render distinctly; backend 400 validation failures
 * (`validation-error`, actionable message) render distinctly from
 * network/5xx `unavailable` (retry). Auth failures keep the existing global
 * auth handling and surface here as unavailable.
 */
export type WidgetState =
  | 'idle'
  | 'loading'
  | 'refreshing'
  | 'success'
  | 'validation-error'
  | 'unavailable';

const TREND_METRICS: readonly AnalyticsMetric[] = [
  'NET_REVENUE',
  'GROSS_REVENUE',
  'TICKETS_ISSUED',
  'TICKETS_SCANNED',
  'RESERVATIONS_CREATED',
  'PAYMENTS_SUCCEEDED',
];

const MONEY_METRICS: readonly AnalyticsMetric[] = ['NET_REVENUE', 'GROSS_REVENUE'];

const SESSION_TABLE_SORT = 'startsAt,desc';

interface RangeRequest {
  from: string;
  to: string;
  eventId?: string;
  eventSessionId?: string;
}

interface SessionOptionsRequest extends RangeRequest {
  reconcileDraft: boolean;
  /** Draft event scope this request reconciles; null means "All events". */
  eventKey: string | null;
}

/** Normalized widget failure: HTTP status plus the backend message when the
 * common error envelope is present. Auth handling stays global; the dashboard
 * only distinguishes validation (400) from unavailable. */
interface WidgetFailure {
  status: number;
  message: string | null;
}

function toWidgetFailure(err: unknown): WidgetFailure {
  if (err instanceof HttpErrorResponse) {
    return { status: err.status, message: asApiErrorEnvelope(err.error)?.message ?? null };
  }
  return { status: 0, message: null };
}

function failureState(failure: WidgetFailure): 'validation-error' | 'unavailable' {
  return failure.status === 400 ? 'validation-error' : 'unavailable';
}

/**
 * Admin analytics dashboard with explicit UTC filters (TASK-P14-006).
 *
 * Filter discipline:
 * - visible controls edit **draft** state; `Apply filters` copies it to the
 *   **applied** filters, resets server pagination to page 0, and refreshes;
 * - CSV export, summary, trend, and sessions always use the applied filters;
 * - every widget pipeline uses `switchMap`, so a slow response for filter A can
 *   never overwrite the newer filter B selection;
 * - data signals are cleared when a new request starts, so stale figures can
 *   never be mistaken for the new filter selection (no prior-content reuse).
 *
 * The sessions table scopes by event only: the P14-004 `/sessions` contract has
 * no `eventSessionId` parameter and is preserved unchanged. The applied session
 * filter scopes summary, trend, and CSV export.
 */
@Component({
  selector: 'app-admin-analytics-dashboard',
  standalone: true,
  imports: [CommonModule, SkeletonLoaderComponent, AnalyticsLineChartComponent],
  templateUrl: './admin-analytics-dashboard.component.html',
  styleUrl: './admin-analytics-dashboard.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminAnalyticsDashboardComponent implements OnInit {
  private readonly api = inject(AdminAnalyticsApiService);
  private readonly destroyRef = inject(DestroyRef);

  readonly summaryState = signal<WidgetState>('idle');
  readonly trendState = signal<WidgetState>('idle');
  readonly sessionsState = signal<WidgetState>('idle');

  readonly summary = signal<AnalyticsSummary | null>(null);
  readonly trend = signal<AnalyticsTimeSeries | null>(null);
  readonly sessions = signal<PagedResult<EventSessionAnalytics> | null>(null);

  /** Actionable backend message for 400 validation failures, per widget. */
  readonly summaryErrorDetail = signal<string | null>(null);
  readonly trendErrorDetail = signal<string | null>(null);
  readonly sessionsErrorDetail = signal<string | null>(null);

  // Draft form state (visible controls; CSV and widgets ignore it until applied).
  readonly draftFrom = signal('');
  readonly draftTo = signal('');
  readonly draftEventId = signal<string | null>(null);
  readonly draftSessionId = signal<string | null>(null);

  // Applied filters (source of truth for widgets and CSV export).
  readonly appliedFilters = signal<AnalyticsAppliedFilters>(this.createDefaultFilters());

  readonly selectedTrendMetric = computed(() => this.appliedFilters().trendMetric);
  readonly trendMetricOptions: readonly AnalyticsMetric[] = TREND_METRICS;
  readonly pageSizeOptions: readonly number[] = ANALYTICS_SESSION_PAGE_SIZES;

  readonly eventOptions = signal<AnalyticsEventFilterOption[]>([]);
  readonly eventOptionsTruncated = signal(false);
  readonly eventOptionsLoading = signal(false);
  readonly eventOptionsError = signal(false);
  readonly sessionOptions = signal<AnalyticsSessionFilterOption[]>([]);
  readonly sessionOptionsTruncated = signal(false);
  readonly sessionOptionsLoading = signal(false);
  readonly sessionOptionsError = signal(false);
  /**
   * REV-006: combined warning derived from both independent endpoint results,
   * so one endpoint's success can never mask the sibling's failure.
   */
  readonly filterOptionsError = computed(
    () => this.eventOptionsError() || this.sessionOptionsError(),
  );
  /**
   * REV-002: true while the current draft event's session-options
   * reconciliation is still in flight. `sessionReconcileEvent` keys the
   * obligation (null = "All events"); a superseding event change re-keys it.
   */
  readonly sessionReconcilePending = signal(false);
  readonly sessionReconcileEvent = signal<string | null>(null);
  /**
   * REV-002 (reopened): draft-event scope that produced the currently
   * displayed session options (null = "All events", undefined = never
   * settled). Displayed options are authoritative for the draft session
   * only when loading is settled AND this key matches the current draft
   * event; otherwise the draft session is cleared deterministically instead
   * of risking an incompatible event/session pair.
   */
  readonly sessionOptionsEventKey = signal<string | null | undefined>(undefined);
  readonly optionsTruncatedMessage = ANALYTICS_FILTER_OPTIONS_TRUNCATED_MESSAGE;

  readonly filterError = signal<string | null>(null);
  readonly exporting = signal(false);
  readonly exportError = signal<string | null>(null);

  private readonly summaryRequests = new Subject<RangeRequest>();
  private readonly trendRequests = new Subject<{ metric: AnalyticsMetric } & RangeRequest>();
  private readonly sessionsRequests = new Subject<
    RangeRequest & { page: number; size: number }
  >();
  private readonly eventOptionsRequests = new Subject<{ req: RangeRequest; id: number }>();
  private readonly sessionOptionsRequests = new Subject<{
    req: SessionOptionsRequest;
    id: number;
  }>();
  /** REV-006: independent current-request identity per options endpoint. */
  private eventOptionsSeq = 0;
  private sessionOptionsSeq = 0;
  private latestEventOptionsId = 0;
  private latestSessionOptionsId = 0;

  readonly isMoneyTrend = computed(() =>
    (MONEY_METRICS as readonly string[]).includes(this.selectedTrendMetric()),
  );

  readonly summaryIsEmpty = computed(() => {
    const s = this.summary();
    if (!s) {
      return false;
    }
    return (
      s.reservations.created === 0 &&
      s.reservations.confirmed === 0 &&
      s.reservations.expired === 0 &&
      s.reservations.refunded === 0 &&
      s.tickets.issued === 0 &&
      s.tickets.scanned === 0 &&
      s.tickets.revoked === 0 &&
      s.payments.succeeded === 0 &&
      s.payments.refundsCompleted === 0 &&
      s.payments.revenueByCurrency.length === 0
    );
  });

  /** Contract discrepancy: backend unexpectedly reports non-test revenue.
   * Covers every rendered financial payload: summary, trend money series,
   * and session revenue rows. Count series (currency/testMode null) are not
   * financial payloads and must not trigger this state. */
  readonly hasNonTestRevenue = computed(() => {
    const summaryNonTest = (this.summary()?.payments.revenueByCurrency ?? []).some(
      (m) => m.testMode !== true,
    );
    if (summaryNonTest) {
      return true;
    }
    const trendNonTest = (this.trend()?.series ?? []).some(
      (s) => s.currency !== null && (s.testMode as boolean | null) !== true,
    );
    if (trendNonTest) {
      return true;
    }
    return (this.sessions()?.content ?? []).some((row) =>
      (row.revenueByCurrency ?? []).some((m) => m.testMode !== true),
    );
  });

  readonly freshnessText = computed(() => {
    // REV-005: freshness is only meaningful after a successful summary
    // response. Otherwise the header shows a non-assertive pending state and
    // never the false "No projected events yet" claim.
    if (this.summaryState() !== 'success') {
      return null;
    }
    return this.summary()?.freshness.lastProjectedEventAt ?? null;
  });

  readonly freshnessReady = computed(() => this.summaryState() === 'success');

  ngOnInit(): void {
    this.summaryRequests
      .pipe(
        switchMap((req) =>
          this.api
            .getSummary(this.toQueryParams(req))
            .pipe(
              map((data) => ({ ok: true as const, data })),
              catchError((err: unknown) =>
                of({ ok: false as const, failure: toWidgetFailure(err) }),
              ),
            ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((res) => {
        if (!res.ok) {
          this.summaryState.set(failureState(res.failure));
          this.summaryErrorDetail.set(res.failure.message);
          return;
        }
        this.summary.set(res.data);
        this.summaryErrorDetail.set(null);
        this.summaryState.set('success');
      });

    this.trendRequests
      .pipe(
        switchMap((req) =>
          this.api
            .getTimeSeries(req.metric, this.toQueryParams(req))
            .pipe(
              map((data) => ({ ok: true as const, data })),
              catchError((err: unknown) =>
                of({ ok: false as const, failure: toWidgetFailure(err) }),
              ),
            ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((res) => {
        if (!res.ok) {
          this.trendState.set(failureState(res.failure));
          this.trendErrorDetail.set(res.failure.message);
          return;
        }
        this.trend.set(res.data);
        this.trendErrorDetail.set(null);
        this.trendState.set('success');
      });

    this.sessionsRequests
      .pipe(
        switchMap((req) =>
          this.api
            .getSessions({
              ...this.toQueryParams(req),
              page: req.page,
              size: req.size,
              sort: SESSION_TABLE_SORT,
            })
            .pipe(
              map((data) => ({ ok: true as const, data })),
              catchError((err: unknown) =>
                of({ ok: false as const, failure: toWidgetFailure(err) }),
              ),
            ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((res) => {
        if (!res.ok) {
          this.sessionsState.set(failureState(res.failure));
          this.sessionsErrorDetail.set(res.failure.message);
          return;
        }
        this.sessions.set(res.data);
        this.sessionsErrorDetail.set(null);
        this.sessionsState.set('success');
      });

    this.eventOptionsRequests
      .pipe(
        switchMap(({ req, id }) =>
          this.api
            .getEventFilterOptions(this.toQueryParams(req))
            .pipe(
              map((data) => ({ ok: true as const, data, id })),
              catchError(() => of({ ok: false as const, id })),
            ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((res) => {
        // REV-006: stale responses (superseded request identity) never touch
        // current state; each endpoint settles only its own loading/error.
        if (res.id !== this.latestEventOptionsId) {
          return;
        }
        this.eventOptionsLoading.set(false);
        if (!res.ok) {
          // Never present stale choices as current for the newly applied scope.
          this.eventOptions.set([]);
          this.eventOptionsTruncated.set(false);
          this.eventOptionsError.set(true);
          return;
        }
        this.eventOptionsError.set(false);
        this.eventOptions.set(res.data.items);
        this.eventOptionsTruncated.set(res.data.truncated);
      });

    this.sessionOptionsRequests
      .pipe(
        switchMap(({ req, id }) =>
          this.api
            .getSessionFilterOptions(this.toQueryParams(req))
            .pipe(
              map((data) => ({ ok: true as const, data, req, id })),
              catchError(() => of({ ok: false as const, req, id })),
            ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((res) => {
        if (res.id !== this.latestSessionOptionsId) {
          return;
        }
        this.sessionOptionsLoading.set(false);
        if (!res.ok) {
          this.sessionOptions.set([]);
          this.sessionOptionsTruncated.set(false);
          this.sessionOptionsError.set(true);
          // The settled scope is authoritative-but-empty: with no truncation
          // flag, Apply treats an unknown owner as unprovable and clears
          // deterministically rather than applying a stale session.
          this.sessionOptionsEventKey.set(res.req.eventKey);
          // The failed options cannot prove incompatibility: the pending
          // reconciliation obligation is deliberately retained, so Apply still
          // clears deterministically instead of applying a stale session.
          return;
        }
        this.sessionOptionsError.set(false);
        this.sessionOptions.set(res.data.items);
        this.sessionOptionsTruncated.set(res.data.truncated);
        this.sessionOptionsEventKey.set(res.req.eventKey);
        // REV-002: reconcile only for the currently drafted event. A response
        // for a superseded event scope never clears or restores the session.
        if (res.req.reconcileDraft && res.req.eventKey === this.draftEventId()) {
          const current = this.draftSessionId();
          if (
            current !== null &&
            !res.data.items.some((item) => item.eventSessionId === current)
          ) {
            this.draftSessionId.set(null);
          }
          this.sessionReconcilePending.set(false);
          this.sessionReconcileEvent.set(null);
        }
      });

    this.reloadAll();
  }

  loadSummary(): void {
    const f = this.appliedFilters();
    // REV-005: a post-success reload is "refreshing" (prior content may remain
    // only behind an explicit indicator); a first load is "loading".
    const refreshing = this.summaryState() === 'success';
    this.summary.set(null);
    this.summaryErrorDetail.set(null);
    this.summaryState.set(refreshing ? 'refreshing' : 'loading');
    this.summaryRequests.next(this.appliedRange(f));
  }

  loadTrend(): void {
    const f = this.appliedFilters();
    const refreshing = this.trendState() === 'success';
    this.trend.set(null);
    this.trendErrorDetail.set(null);
    this.trendState.set(refreshing ? 'refreshing' : 'loading');
    this.trendRequests.next({ ...this.appliedRange(f), metric: f.trendMetric });
  }

  loadSessions(): void {
    const f = this.appliedFilters();
    const refreshing = this.sessionsState() === 'success';
    this.sessions.set(null);
    this.sessionsErrorDetail.set(null);
    this.sessionsState.set(refreshing ? 'refreshing' : 'loading');
    // The P14-004 /sessions contract accepts an event scope only (no
    // eventSessionId); it is preserved unchanged, so the selected session is
    // deliberately not sent here. Summary, trend, and CSV export do honor it.
    this.sessionsRequests.next({
      from: f.fromDate,
      to: f.toDate,
      eventId: f.eventId ?? undefined,
      page: f.sessionPage,
      size: f.sessionPageSize,
    });
  }

  reloadFilterOptions(): void {
    const f = this.appliedFilters();
    // Filter-options endpoints scope by range (+ event for sessions); the applied
    // session is deliberately not sent — it is a result filter, not an option scope.
    const range = { from: f.fromDate, to: f.toDate };
    this.latestEventOptionsId = ++this.eventOptionsSeq;
    this.eventOptionsLoading.set(true);
    this.eventOptionsError.set(false);
    this.eventOptionsRequests.next({ req: range, id: this.latestEventOptionsId });
    // REV-002: while the current draft event's reconciliation is in flight,
    // its request already refreshes session options — a second request would
    // cancel it through the shared switchMap and drop the obligation.
    if (this.sessionReconcilePending()) {
      return;
    }
    this.latestSessionOptionsId = ++this.sessionOptionsSeq;
    this.sessionOptionsLoading.set(true);
    this.sessionOptionsError.set(false);
    this.sessionOptionsRequests.next({
      req: {
        ...range,
        eventId: f.eventId ?? undefined,
        eventKey: f.eventId,
        reconcileDraft: false,
      },
      id: this.latestSessionOptionsId,
    });
  }

  applyFilters(): void {
    const from = this.draftFrom().trim();
    const to = this.draftTo().trim();
    if (from === '' || to === '') {
      this.filterError.set('Select both a start and an end date (YYYY-MM-DD).');
      return;
    }
    if (!isValidIsoDateRange(from, to)) {
      this.filterError.set(
        'Date range is invalid: use YYYY-MM-DD with the start on or before the end.',
      );
      return;
    }
    this.filterError.set(null);
    // REV-002 (reopened): never issue a filtered-data request with a
    // possibly-stale session. When the current draft event's reconciliation is
    // still in flight, clear the session deterministically (safe direction:
    // broader, never wrong-empty) before copying the draft to the applied
    // filters. The same deterministic clear applies whenever the displayed
    // session options are unsettled for the current draft event (still loading
    // or produced for a different scope), or when they prove the session is
    // out of scope (known different owner, or a settled complete list that
    // does not contain it). An unprovable session under truncated options is
    // preserved: it may legitimately sit beyond the first 500.
    if (
      this.sessionReconcilePending() &&
      this.sessionReconcileEvent() === this.draftEventId()
    ) {
      this.draftSessionId.set(null);
      this.sessionReconcilePending.set(false);
      this.sessionReconcileEvent.set(null);
    } else {
      this.clearUnprovableDraftSession();
    }
    this.appliedFilters.update((current) => ({
      ...current,
      fromDate: from,
      toDate: to,
      eventId: this.draftEventId(),
      eventSessionId: this.draftSessionId(),
      sessionPage: 0,
    }));
    this.reloadAll();
  }

  resetFilters(): void {
    const defaults = this.createDefaultFilters();
    this.draftFrom.set(defaults.fromDate);
    this.draftTo.set(defaults.toDate);
    this.draftEventId.set(null);
    this.draftSessionId.set(null);
    this.appliedFilters.set(defaults);
    this.filterError.set(null);
    this.exportError.set(null);
    this.sessionOptions.set([]);
    this.sessionOptionsTruncated.set(false);
    // Invalidate any in-flight options responses so they cannot repopulate
    // state the reset just cleared (identity check in each subscriber).
    this.latestEventOptionsId = ++this.eventOptionsSeq;
    this.latestSessionOptionsId = ++this.sessionOptionsSeq;
    this.eventOptionsLoading.set(false);
    this.sessionOptionsLoading.set(false);
    this.eventOptionsError.set(false);
    this.sessionOptionsError.set(false);
    this.sessionReconcilePending.set(false);
    this.sessionReconcileEvent.set(null);
    this.sessionOptionsEventKey.set(null);
    this.reloadAll();
  }

  onDraftFromChange(value: string): void {
    this.draftFrom.set(value);
  }

  onDraftToChange(value: string): void {
    this.draftTo.set(value);
  }

  onDraftEventChange(value: string | null): void {
    const eventId = value === null || value === '' ? null : value;
    this.draftEventId.set(eventId);
    if (!isValidIsoDateRange(this.draftFrom().trim(), this.draftTo().trim())) {
      this.draftSessionId.set(null);
      this.sessionReconcilePending.set(false);
      this.sessionReconcileEvent.set(null);
      return;
    }
    // REV-002: reconcile synchronously from known option ownership where
    // possible — a selected session owned by a different event is
    // deterministically incompatible with the new scope.
    const selected = this.draftSessionId();
    if (selected !== null && eventId !== null) {
      const owner =
        this.sessionOptions().find((item) => item.eventSessionId === selected)?.eventId ??
        null;
      if (owner !== null && owner !== eventId) {
        this.draftSessionId.set(null);
      }
    }
    const range = {
      from: this.draftFrom().trim(),
      to: this.draftTo().trim(),
    };
    // REV-002 (reopened): the previously displayed options belong to the old
    // event scope. They are cleared immediately so a stale option can never be
    // selected as current while the new scope loads (the select is additionally
    // disabled via sessionOptionsLoading until the load settles). A superseding
    // change re-keys the pending obligation to the newest event.
    // The reconciliation obligation is keyed even when no session is selected
    // yet: a stale option picked mid-load must still be rejected by
    // onDraftSessionChange and cleared by applyFilters.
    this.sessionOptions.set([]);
    this.sessionOptionsTruncated.set(false);
    this.sessionOptionsError.set(false);
    this.sessionReconcilePending.set(true);
    this.sessionReconcileEvent.set(eventId);
    this.latestSessionOptionsId = ++this.sessionOptionsSeq;
    this.sessionOptionsLoading.set(true);
    this.sessionOptionsRequests.next({
      req: { ...range, eventId: eventId ?? undefined, eventKey: eventId, reconcileDraft: true },
      id: this.latestSessionOptionsId,
    });
  }

  onDraftSessionChange(value: string | null): void {
    const next = value === null || value === '' ? null : value;
    if (next === null) {
      this.draftSessionId.set(null);
      return;
    }
    // REV-002 (reopened): validate ownership against the current draft event
    // plus the current request identity. While the current event's options are
    // unsettled (reconciliation pending, loading, or the displayed options were
    // produced for a different scope), compatibility cannot be proven, so the
    // selection is rejected in the safe direction (broader, never wrong-empty).
    const eventId = this.draftEventId();
    if (
      (this.sessionReconcilePending() && this.sessionReconcileEvent() === eventId) ||
      this.sessionOptionsLoading() ||
      this.sessionOptionsEventKey() !== eventId
    ) {
      this.draftSessionId.set(null);
      return;
    }
    if (eventId !== null) {
      const owner =
        this.sessionOptions().find((item) => item.eventSessionId === next)?.eventId ?? null;
      if (owner !== null && owner !== eventId) {
        this.draftSessionId.set(null);
        return;
      }
      if (owner === null && !this.sessionOptionsTruncated()) {
        // Settled, complete option list proves the session is not in scope.
        this.draftSessionId.set(null);
        return;
      }
      // owner === null with truncated options: compatibility is unprovable but
      // the session may legitimately sit beyond the first 500, so preserve it.
    }
    this.draftSessionId.set(next);
  }

  selectTrendMetric(metric: AnalyticsMetric): void {
    if (metric === this.selectedTrendMetric()) {
      return;
    }
    this.appliedFilters.update((current) => ({ ...current, trendMetric: metric }));
    this.loadTrend();
  }

  goToPage(page: number): void {
    const size = this.appliedFilters().sessionPageSize;
    const totalPages = this.sessions()?.totalPages ?? 1;
    const clamped = Math.min(Math.max(0, page), Math.max(0, totalPages - 1));
    if (clamped === this.appliedFilters().sessionPage) {
      return;
    }
    this.appliedFilters.update((current) => ({ ...current, sessionPage: clamped }));
    this.loadSessions();
  }

  changePageSize(size: number): void {
    if (!ANALYTICS_SESSION_PAGE_SIZES.includes(size)) {
      return;
    }
    if (
      size === this.appliedFilters().sessionPageSize &&
      this.appliedFilters().sessionPage === 0
    ) {
      return;
    }
    this.appliedFilters.update((current) => ({
      ...current,
      sessionPageSize: size,
      sessionPage: 0,
    }));
    this.loadSessions();
  }

  exportCsv(): void {
    if (this.exporting()) {
      return;
    }
    const f = this.appliedFilters();
    this.exporting.set(true);
    this.exportError.set(null);
    const fallback = `seatflow-analytics-${f.fromDate}-${f.toDate}.csv`;
    this.api
      .exportDailyCsv({
        from: f.fromDate,
        to: f.toDate,
        eventId: f.eventId ?? undefined,
        eventSessionId: f.eventSessionId ?? undefined,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response: HttpResponse<Blob>) => {
          this.exporting.set(false);
          if (!response.body) {
            this.exportError.set('Export failed: empty response. Please retry.');
            return;
          }
          const url = window.URL.createObjectURL(response.body);
          const anchor = document.createElement('a');
          anchor.href = url;
          anchor.download = resolveAnalyticsExportFilename(response, fallback);
          anchor.click();
          setTimeout(() => window.URL.revokeObjectURL(url), 1000);
        },
        error: (err: unknown) => {
          this.exporting.set(false);
          this.exportError.set(this.describeExportError(err));
        },
      });
  }

  optionLabel(label: string | null, id: string): string {
    if (label !== null && label.trim() !== '') {
      return label;
    }
    return this.shortenId(id);
  }

  formatMoney(minor: number, currency: string): string {
    try {
      const probe = new Intl.NumberFormat(undefined, { style: 'currency', currency });
      const fractionDigits = probe.resolvedOptions().maximumFractionDigits ?? 2;
      const major = minor / Math.pow(10, fractionDigits);
      return new Intl.NumberFormat(undefined, {
        style: 'currency',
        currency,
      }).format(major);
    } catch {
      return `${minor} ${currency}`;
    }
  }

  formatChartValue = (value: number, currency: string | null): string => {
    if (currency) {
      return this.formatMoney(value, currency);
    }
    return new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 }).format(value);
  };

  formatRate(rate: RateMetric | null | undefined): string {
    if (!rate || rate.ratio === null || rate.ratio === undefined) {
      return '—';
    }
    return new Intl.NumberFormat(undefined, {
      style: 'percent',
      maximumFractionDigits: 1,
    }).format(rate.ratio);
  }

  formatRatio(ratio: number | null | undefined): string {
    if (ratio === null || ratio === undefined) {
      return '—';
    }
    return new Intl.NumberFormat(undefined, {
      style: 'percent',
      maximumFractionDigits: 1,
    }).format(ratio);
  }

  rateDetail(rate: RateMetric | null | undefined): string {
    if (!rate) {
      return 'Not available';
    }
    if (rate.ratio === null || rate.ratio === undefined) {
      return 'Not available (no cohort)';
    }
    return `${rate.numerator} of ${rate.denominator}`;
  }

  sessionLabel(session: EventSessionAnalytics): string {
    if (session.eventTitle && session.eventTitle.trim() !== '') {
      return session.eventTitle;
    }
    if (session.sessionLabel && session.sessionLabel.trim() !== '') {
      return session.sessionLabel;
    }
    return this.shortenId(session.eventSessionId);
  }

  shortenId(id: string): string {
    return id.length > 8 ? `${id.slice(0, 8)}…` : id;
  }

  revenueFor(session: EventSessionAnalytics): MoneyMetric[] {
    return session.revenueByCurrency ?? [];
  }

  trackByCurrency(_index: number, item: MoneyMetric): string {
    return item.currency;
  }

  trackBySessionId(_index: number, item: EventSessionAnalytics): string {
    return item.eventSessionId;
  }

  trackByEventId(_index: number, item: AnalyticsEventFilterOption): string {
    return item.eventId;
  }

  trackBySessionOptionId(_index: number, item: AnalyticsSessionFilterOption): string {
    return item.eventSessionId;
  }

  private createDefaultFilters(): AnalyticsAppliedFilters {
    const range = defaultAnalyticsDateRange(new Date());
    this.draftFrom.set(range.from);
    this.draftTo.set(range.to);
    this.draftEventId.set(null);
    this.draftSessionId.set(null);
    return {
      fromDate: range.from,
      toDate: range.to,
      eventId: null,
      eventSessionId: null,
      trendMetric: DEFAULT_ANALYTICS_TREND_METRIC,
      sessionPage: 0,
      sessionPageSize: DEFAULT_ANALYTICS_PAGE_SIZE,
    };
  }

  private reloadAll(): void {
    this.loadSummary();
    this.loadTrend();
    this.loadSessions();
    this.reloadFilterOptions();
  }

  /**
   * REV-002 (reopened): deterministic Apply-time guard. Clears the draft
   * session unless the settled, current-scope options prove it compatible.
   * Unsettled options (still loading or produced for another scope), a known
   * different owner, or a settled complete list without the session all clear;
   * only an unprovable session under truncated options is preserved.
   */
  private clearUnprovableDraftSession(): void {
    const eventId = this.draftEventId();
    const sessionId = this.draftSessionId();
    if (sessionId === null || eventId === null) {
      return;
    }
    if (this.sessionOptionsLoading() || this.sessionOptionsEventKey() !== eventId) {
      this.draftSessionId.set(null);
      return;
    }
    const owner =
      this.sessionOptions().find((item) => item.eventSessionId === sessionId)?.eventId ?? null;
    if (owner !== null && owner !== eventId) {
      this.draftSessionId.set(null);
      return;
    }
    if (owner === null && !this.sessionOptionsTruncated()) {
      this.draftSessionId.set(null);
    }
  }

  private appliedRange(f: AnalyticsAppliedFilters): RangeRequest {
    return {
      from: f.fromDate,
      to: f.toDate,
      eventId: f.eventId ?? undefined,
      eventSessionId: f.eventSessionId ?? undefined,
    };
  }

  private toQueryParams(req: RangeRequest): {
    from: string;
    to: string;
    eventId?: string;
    eventSessionId?: string;
  } {
    return {
      from: req.from,
      to: req.to,
      eventId: req.eventId,
      eventSessionId: req.eventSessionId,
    };
  }

  private describeExportError(err: unknown): string {
    // REV-003: the API service decodes Blob JSON error bodies before they
    // reach this mapping; an undecodable body (still a Blob, or malformed)
    // safely falls back to the generic message.
    const code = asApiErrorEnvelope((err as { error?: unknown })?.error)?.errorCode;
    if (code === 'ANALYTICS_EXPORT_TOO_LARGE') {
      return ANALYTICS_EXPORT_TOO_LARGE_MESSAGE;
    }
    return 'Export failed. Please retry.';
  }
}
