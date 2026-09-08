import { ComponentFixture, TestBed } from '@angular/core/testing';
import {
  HttpErrorResponse,
  HttpResponse,
  provideHttpClient,
} from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { of, Subject, throwError } from 'rxjs';
import { AdminAnalyticsDashboardComponent } from './admin-analytics-dashboard.component';
import { AdminAnalyticsApiService } from '../../../../services/admin-analytics-api.service';
import {
  AnalyticsEventFilterOptions,
  AnalyticsSessionFilterOptions,
  AnalyticsSummary,
  AnalyticsTimeSeries,
  EventSessionAnalytics,
} from '../../../../models/admin-analytics.model';
import { PagedResult } from '../../../../models/event.model';

describe('AdminAnalyticsDashboardComponent', () => {
  let component: AdminAnalyticsDashboardComponent;
  let fixture: ComponentFixture<AdminAnalyticsDashboardComponent>;
  let apiSpy: jasmine.SpyObj<AdminAnalyticsApiService>;

  const summaryFixture: AnalyticsSummary = {
    from: '2026-08-08',
    to: '2026-09-06',
    filters: { eventId: null, eventSessionId: null },
    reservations: { created: 120, confirmed: 87, expired: 25, refunded: 4 },
    tickets: { issued: 174, revoked: 8, scanned: 103 },
    payments: {
      succeeded: 87,
      withFailure: 11,
      refundsCompleted: 4,
      revenueByCurrency: [
        { currency: 'RON', grossMinor: 3150000, refundedMinor: 120000, netMinor: 3030000, testMode: true },
        { currency: 'EUR', grossMinor: 50000, refundedMinor: 0, netMinor: 50000, testMode: true },
      ],
    },
    rates: {
      reservationToPayment: { numerator: 87, denominator: 120, ratio: 0.725 },
      expiration: { numerator: 25, denominator: 120, ratio: 0.208333 },
      refund: { numerator: 4, denominator: 87, ratio: 0.045977 },
    },
    freshness: {
      generatedAt: '2026-09-06T12:00:00Z',
      lastProjectedEventAt: '2026-09-06T11:59:42Z',
      lastProcessedAt: '2026-09-06T11:59:43Z',
      eventuallyConsistent: true,
    },
  };

  const trendFixture: AnalyticsTimeSeries = {
    metric: 'NET_REVENUE',
    from: '2026-08-08',
    to: '2026-09-06',
    series: [
      {
        currency: 'RON',
        testMode: true,
        points: [
          { date: '2026-09-05', value: 100000 },
          { date: '2026-09-06', value: 0 },
        ],
      },
    ],
  };

  function sessionFixture(): PagedResult<EventSessionAnalytics> {
    return {
      content: [
        {
          eventId: 'event-1',
          eventSessionId: 'session-1',
          eventTitle: null,
          sessionLabel: null,
          startsAt: null,
          status: 'SCHEDULED',
          capacitySnapshot: null,
          reservationsCreated: 5,
          reservationsConfirmed: 4,
          reservationsExpired: 1,
          paymentsSucceeded: 4,
          paymentsWithFailure: 0,
          refundsCompleted: 0,
          ticketsIssued: 8,
          ticketsRevoked: 0,
          ticketsScanned: 3,
          revenueByCurrency: [
            { currency: 'RON', grossMinor: 80000, refundedMinor: 0, netMinor: 80000, testMode: true },
          ],
          occupancyRatio: null,
          attendanceRatio: null,
          lastProjectedEventAt: null,
        },
      ],
      page: 0,
      size: 25,
      totalElements: 1,
      totalPages: 1,
      isFirst: true,
      isLast: true,
    };
  }

  const emptySessionOptions: AnalyticsSessionFilterOptions = {
    items: [],
    totalProjected: 0,
    truncated: false,
  };

  async function setup(): Promise<void> {
    apiSpy = jasmine.createSpyObj('AdminAnalyticsApiService', [
      'getSummary',
      'getTimeSeries',
      'getSessions',
      'getEventFilterOptions',
      'getSessionFilterOptions',
      'exportDailyCsv',
    ]);
    apiSpy.getSummary.and.returnValue(of(summaryFixture));
    apiSpy.getTimeSeries.and.returnValue(of(trendFixture));
    apiSpy.getSessions.and.returnValue(of(sessionFixture()));
    apiSpy.getEventFilterOptions.and.returnValue(
      of({ items: [], totalProjected: 0, truncated: false }),
    );
    apiSpy.getSessionFilterOptions.and.returnValue(of(emptySessionOptions));
    apiSpy.exportDailyCsv.and.returnValue(
      of(
        new HttpResponse({
          body: new Blob(['row_type,metric_date\n'], { type: 'text/csv' }),
        }),
      ),
    );

    await TestBed.configureTestingModule({
      imports: [AdminAnalyticsDashboardComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AdminAnalyticsApiService, useValue: apiSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AdminAnalyticsDashboardComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('should create with NET_REVENUE as the default trend metric', async () => {
    await setup();
    expect(component).toBeTruthy();
    expect(component.selectedTrendMetric()).toBe('NET_REVENUE');
    expect(apiSpy.getTimeSeries).toHaveBeenCalledWith(
      'NET_REVENUE',
      jasmine.objectContaining({
        from: component.appliedFilters().fromDate,
        to: component.appliedFilters().toDate,
      }),
    );
  });

  it('should render exact counts, separate currency groups, and the Test Mode badge', async () => {
    await setup();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-testid="kpi-reservations-created"]')?.textContent).toContain('120');
    // two currencies render as two groups, never one total
    expect(el.querySelector('[data-testid="revenue-RON"]')).toBeTruthy();
    expect(el.querySelector('[data-testid="revenue-EUR"]')).toBeTruthy();
    expect(el.querySelector('[data-testid="stripe-test-mode-badge"]')?.textContent).toContain(
      'Stripe Test Mode',
    );
    // freshness disclosure shows projected time
    expect(el.querySelector('[data-testid="freshness-disclosure"]')?.textContent).toContain(
      '2026-09-06T11:59:42Z',
    );
  });

  it('should render null rates as unavailable instead of 0%', async () => {
    await setup();
    const nullRateSummary: AnalyticsSummary = {
      ...summaryFixture,
      rates: {
        reservationToPayment: { numerator: 0, denominator: 0, ratio: null },
        expiration: { numerator: 0, denominator: 0, ratio: null },
        refund: { numerator: 0, denominator: 0, ratio: null },
      },
    };
    apiSpy.getSummary.and.returnValue(of(nullRateSummary));
    component.loadSummary();
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-testid="kpi-conversion"]')?.textContent).toContain('—');
    expect(el.querySelector('[data-testid="kpi-conversion"]')?.textContent).not.toContain('0%');
  });

  it('should fall back to a stable session ID and mark null capacity/ratios unavailable', async () => {
    await setup();
    const el: HTMLElement = fixture.nativeElement;
    const row = el.querySelector('[data-testid="session-session-1"]');
    expect(row?.textContent).toContain('session-');
    expect(row?.textContent).toContain('—');
    expect(row?.textContent).not.toContain('Event Service');
  });

  it('should keep summary and trend visible when the sessions request fails', async () => {
    await setup();
    apiSpy.getSessions.and.returnValue(throwError(() => new Error('sessions down')));
    component.loadSessions();
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-testid="sessions-error"]')).toBeTruthy();
    // successful widgets are not erased
    expect(el.querySelector('[data-testid="kpi-reservations-created"]')).toBeTruthy();
    expect(el.querySelector('app-analytics-line-chart')).toBeTruthy();
    // no raw error body leaks
    expect(el.querySelector('[data-testid="sessions-error"]')?.textContent).not.toContain(
      'sessions down',
    );
  });

  it('should distinguish empty success from network error', async () => {
    await setup();
    const emptySessions: PagedResult<EventSessionAnalytics> = {
      content: [],
      page: 0,
      size: 25,
      totalElements: 0,
      totalPages: 0,
      isFirst: true,
      isLast: true,
    };
    apiSpy.getSessions.and.returnValue(of(emptySessions));
    component.loadSessions();
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-testid="sessions-empty"]')).toBeTruthy();
    expect(el.querySelector('[data-testid="sessions-error"]')).toBeFalsy();
  });

  it('should show "No projected events yet" when freshness has no projected event', async () => {
    await setup();
    apiSpy.getSummary.and.returnValue(
      of({
        ...summaryFixture,
        freshness: {
          generatedAt: '2026-09-06T12:00:00Z',
          lastProjectedEventAt: null,
          lastProcessedAt: null,
          eventuallyConsistent: true,
        },
      }),
    );
    component.loadSummary();
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-testid="freshness-disclosure"]')?.textContent).toContain(
      'No projected events yet',
    );
  });

  it('should retry only the failed widget request', async () => {
    await setup();
    apiSpy.getSummary.calls.reset();
    apiSpy.getTimeSeries.calls.reset();
    apiSpy.getSessions.calls.reset();
    component.loadSummary();
    expect(apiSpy.getSummary).toHaveBeenCalledTimes(1);
    expect(apiSpy.getTimeSeries).not.toHaveBeenCalled();
    expect(apiSpy.getSessions).not.toHaveBeenCalled();
  });

  it('should ignore a stale trend response that resolves after a newer metric selection', async () => {
    await setup();
    const grossSeries: AnalyticsTimeSeries = {
      metric: 'GROSS_REVENUE',
      from: '2026-08-08',
      to: '2026-09-06',
      series: [
        {
          currency: 'RON',
          testMode: true,
          points: [{ date: '2026-09-06', value: 999999 }],
        },
      ],
    };
    const ticketsSeries: AnalyticsTimeSeries = {
      metric: 'TICKETS_ISSUED',
      from: '2026-08-08',
      to: '2026-09-06',
      series: [{ currency: null, testMode: null, points: [{ date: '2026-09-06', value: 42 }] }],
    };
    const gross$ = new Subject<AnalyticsTimeSeries>();
    const tickets$ = new Subject<AnalyticsTimeSeries>();
    apiSpy.getTimeSeries.and.callFake((metric: string) => {
      if (metric === 'GROSS_REVENUE') {
        return gross$.asObservable();
      }
      if (metric === 'TICKETS_ISSUED') {
        return tickets$.asObservable();
      }
      return of(trendFixture);
    });

    // Slow first request, then a fast reselection before the first resolves.
    component.selectTrendMetric('GROSS_REVENUE');
    component.selectTrendMetric('TICKETS_ISSUED');
    fixture.detectChanges();

    tickets$.next(ticketsSeries);
    tickets$.complete();
    fixture.detectChanges();
    // Stale slow response resolves last and must not overwrite the newest metric.
    gross$.next(grossSeries);
    gross$.complete();
    fixture.detectChanges();

    expect(component.selectedTrendMetric()).toBe('TICKETS_ISSUED');
    expect(component.trend()?.metric).toBe('TICKETS_ISSUED');
    const el: HTMLElement = fixture.nativeElement;
    // Chart title follows the newest selection, not the stale response.
    expect(el.querySelector('app-analytics-line-chart')?.textContent).toContain('TICKETS_ISSUED');
    expect(el.querySelector('app-analytics-line-chart')?.textContent).not.toContain('GROSS_REVENUE');
  });

  it('should flag non-test trend money even when summary revenue is empty', async () => {
    await setup();
    const emptySummary: AnalyticsSummary = {
      ...summaryFixture,
      payments: { ...summaryFixture.payments, revenueByCurrency: [] },
    };
    const nonTestTrend: AnalyticsTimeSeries = {
      metric: 'NET_REVENUE',
      from: '2026-08-08',
      to: '2026-09-06',
      series: [
        {
          currency: 'RON',
          testMode: false,
          points: [{ date: '2026-09-06', value: 50000 }],
        },
      ],
    };
    apiSpy.getSummary.and.returnValue(of(emptySummary));
    apiSpy.getTimeSeries.and.returnValue(of(nonTestTrend));
    component.loadSummary();
    component.loadTrend();
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-testid="payment-mode-warning"]')).toBeTruthy();
    expect(el.querySelector('[data-testid="payment-mode-unverified-badge"]')).toBeTruthy();
    expect(el.querySelector('[data-testid="stripe-test-mode-badge"]')).toBeFalsy();
  });

  it('should flag non-test session revenue without a Test assertion on the row', async () => {
    await setup();
    const nonTestSessions = sessionFixture();
    nonTestSessions.content[0].revenueByCurrency = [
      { currency: 'RON', grossMinor: 80000, refundedMinor: 0, netMinor: 80000, testMode: false },
    ];
    apiSpy.getSessions.and.returnValue(of(nonTestSessions));
    component.loadSessions();
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-testid="payment-mode-warning"]')).toBeTruthy();
    expect(el.querySelector('[data-testid="stripe-test-mode-badge"]')).toBeFalsy();
    const row = el.querySelector('[data-testid="session-session-1"]');
    expect(row?.textContent).toContain('Payment mode unverified');
    const miniBadge = row?.querySelector('.analytics-dashboard__mini-badge');
    expect(miniBadge?.textContent).not.toContain('Test');
  });

  it('should reset to the exact 30-day UTC default with a frozen clock', async () => {
    jasmine.clock().install();
    jasmine.clock().mockDate(new Date('2026-09-06T12:00:00Z'));
    try {
      await setup();
      component.selectTrendMetric('GROSS_REVENUE');
      component.onDraftFromChange('2026-01-01');
      component.onDraftToChange('2026-01-31');
      component.resetFilters();
      fixture.detectChanges();

      expect(component.appliedFilters()).toEqual({
        fromDate: '2026-08-08',
        toDate: '2026-09-06',
        eventId: null,
        eventSessionId: null,
        trendMetric: 'NET_REVENUE',
        sessionPage: 0,
        sessionPageSize: 25,
      });
      expect(component.draftFrom()).toBe('2026-08-08');
      expect(component.draftTo()).toBe('2026-09-06');
      expect(component.filterError()).toBeNull();
      const el: HTMLElement = fixture.nativeElement;
      expect(
        (el.querySelector('[data-testid="filter-from"]') as HTMLInputElement).value,
      ).toBe('2026-08-08');
    } finally {
      jasmine.clock().uninstall();
    }
  });

  it('should send date-only strings unchanged and apply resets the page', async () => {
    jasmine.clock().install();
    jasmine.clock().mockDate(new Date('2026-09-06T12:00:00Z'));
    try {
      await setup();
      const multiPage = sessionFixture();
      (multiPage as { totalPages: number }).totalPages = 3;
      (multiPage as { isLast: boolean }).isLast = false;
      apiSpy.getSessions.and.returnValue(of(multiPage));
      component.loadSessions();
      component.goToPage(2);
      expect(component.appliedFilters().sessionPage).toBe(2);

      component.onDraftFromChange('2026-08-08');
      component.onDraftToChange('2026-09-06');
      apiSpy.getSummary.calls.reset();
      component.applyFilters();
      fixture.detectChanges();

      expect(component.appliedFilters().sessionPage).toBe(0);
      const summaryArgs = apiSpy.getSummary.calls.mostRecent().args[0] as {
        from: string;
        to: string;
      };
      // Date-only strings pass through with no timezone shift.
      expect(summaryArgs.from).toBe('2026-08-08');
      expect(summaryArgs.to).toBe('2026-09-06');
      expect(apiSpy.getSessions).toHaveBeenCalledWith(
        jasmine.objectContaining({ page: 0, size: 25, sort: 'startsAt,desc' }),
      );
    } finally {
      jasmine.clock().uninstall();
    }
  });

  it('should reject an obviously invalid range client-side without a request', async () => {
    await setup();
    apiSpy.getSummary.calls.reset();
    component.onDraftFromChange('2026-09-06');
    component.onDraftToChange('2026-08-08');
    component.applyFilters();
    fixture.detectChanges();

    expect(component.filterError()).toContain('invalid');
    expect(apiSpy.getSummary).not.toHaveBeenCalled();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-testid="filter-error"]')).toBeTruthy();
  });

  it('should clear an incompatible session when the event filter changes', async () => {
    await setup();
    component.draftSessionId.set('session-gone');
    apiSpy.getSessionFilterOptions.and.returnValue(
      of({
        items: [
          {
            eventSessionId: 'session-kept',
            eventId: 'event-1',
            label: 'Kept',
            startsAt: null,
          },
        ],
        totalProjected: 1,
        truncated: false,
      }),
    );
    component.onDraftEventChange('event-1');
    expect(component.draftSessionId()).toBeNull();
  });

  it('should keep a compatible session when the event filter changes', async () => {
    await setup();
    component.draftSessionId.set('session-kept');
    apiSpy.getSessionFilterOptions.and.returnValue(
      of({
        items: [
          {
            eventSessionId: 'session-kept',
            eventId: 'event-1',
            label: 'Kept',
            startsAt: null,
          },
        ],
        totalProjected: 1,
        truncated: false,
      }),
    );
    component.onDraftEventChange('event-1');
    expect(component.draftSessionId()).toBe('session-kept');
  });

  it('should keep the newer filter selection when an older summary response arrives late', async () => {
    await setup();
    const first$ = new Subject<AnalyticsSummary>();
    const second$ = new Subject<AnalyticsSummary>();
    let calls = 0;
    apiSpy.getSummary.and.callFake(() => {
      calls += 1;
      return (calls === 1 ? first$ : second$).asObservable();
    });

    component.onDraftFromChange('2026-08-01');
    component.onDraftToChange('2026-08-10');
    component.applyFilters();
    component.onDraftFromChange('2026-08-11');
    component.onDraftToChange('2026-08-20');
    component.applyFilters();
    fixture.detectChanges();

    second$.next({ ...summaryFixture, reservations: { ...summaryFixture.reservations, created: 777 } });
    second$.complete();
    fixture.detectChanges();
    first$.next(summaryFixture);
    first$.complete();
    fixture.detectChanges();

    expect(component.summary()?.reservations.created).toBe(777);
    expect(component.summaryState()).toBe('success');
  });

  it('should show the explicit truncation warning when options overflow', async () => {
    await setup();
    apiSpy.getEventFilterOptions.and.returnValue(
      of({ items: [], totalProjected: 501, truncated: true }),
    );
    component.reloadFilterOptions();
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-testid="options-truncated-warning"]')?.textContent).toContain(
      'first 500',
    );
  });

  it('should scope the sessions table by event only and page server-side', async () => {
    await setup();
    const multiPage = sessionFixture();
    (multiPage as { totalPages: number }).totalPages = 3;
    (multiPage as { isLast: boolean }).isLast = false;
    apiSpy.getSessions.and.returnValue(of(multiPage));
    component.loadSessions();
    apiSpy.getSummary.calls.reset();
    apiSpy.getTimeSeries.calls.reset();
    apiSpy.getSessions.calls.reset();

    component.goToPage(1);
    expect(apiSpy.getSessions).toHaveBeenCalledTimes(1);
    expect(apiSpy.getSessions).toHaveBeenCalledWith(
      jasmine.objectContaining({ page: 1, size: 25 }),
    );
    // A page change never refetches the other widgets and never pages client-side.
    expect(apiSpy.getSummary).not.toHaveBeenCalled();
    expect(apiSpy.getTimeSeries).not.toHaveBeenCalled();

    component.changePageSize(50);
    expect(apiSpy.getSessions).toHaveBeenCalledWith(
      jasmine.objectContaining({ page: 0, size: 50 }),
    );
  });

  it('should export with applied filters, never draft edits', async () => {
    await setup();
    apiSpy.exportDailyCsv.calls.reset();
    component.onDraftFromChange('2020-01-01');
    component.onDraftToChange('2020-01-31');
    component.exportCsv();
    expect(apiSpy.exportDailyCsv).toHaveBeenCalledWith(
      jasmine.objectContaining({
        from: component.appliedFilters().fromDate,
        to: component.appliedFilters().toDate,
      }),
    );
    expect(
      (apiSpy.exportDailyCsv.calls.mostRecent().args[0] as { from: string }).from,
    ).not.toBe('2020-01-01');
    expect(component.exporting()).toBeFalse();
    expect(component.exportError()).toBeNull();
  });

  it('should disable the export button while a download is in flight', async () => {
    await setup();
    const pending$ = new Subject<HttpResponse<Blob>>();
    apiSpy.exportDailyCsv.and.returnValue(pending$.asObservable());
    component.exportCsv();
    fixture.detectChanges();
    expect(component.exporting()).toBeTrue();
    const el: HTMLElement = fixture.nativeElement;
    expect(
      (el.querySelector('[data-testid="export-csv"]') as HTMLButtonElement).disabled,
    ).toBeTrue();
    // A duplicate click while active is ignored: still exactly one request.
    component.exportCsv();
    expect(apiSpy.exportDailyCsv).toHaveBeenCalledTimes(1);
    pending$.next(new HttpResponse({ body: new Blob(['a']) }));
    pending$.complete();
    expect(component.exporting()).toBeFalse();
  });

  it('should explain an oversized export with the narrow-filters message', async () => {
    await setup();
    // Post-decode contract: the API service turns the Blob JSON error body
    // into the shared envelope before the component maps the stable code.
    apiSpy.exportDailyCsv.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            error: {
              status: 400,
              error: 'Bad Request',
              errorCode: 'ANALYTICS_EXPORT_TOO_LARGE',
              message: 'Analytics export matches 10001 rows, maximum is 10000.',
              path: '/api/admin/analytics/export/daily.csv',
              timestamp: '2026-09-06T12:00:00Z',
            },
            status: 400,
          }),
      ),
    );
    component.exportCsv();
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-testid="export-error"]')?.textContent).toContain(
      'Narrow the date range',
    );
  });

  it('should fall back to a generic message for an undecodable Blob export error', async () => {
    await setup();
    // Safety net when the API-service decode cannot run (malformed body).
    apiSpy.exportDailyCsv.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            error: new Blob(['<html>not json</html>'], { type: 'text/html' }),
            status: 400,
          }),
      ),
    );
    component.exportCsv();
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-testid="export-error"]')?.textContent).toContain(
      'Export failed. Please retry.',
    );
    expect(el.querySelector('[data-testid="export-error"]')?.textContent).not.toContain(
      'Narrow the date range',
    );
  });

  it('should flag non-test summary revenue without a Test assertion on the row', async () => {
    await setup();
    apiSpy.getSummary.and.returnValue(
      of({
        ...summaryFixture,
        payments: {
          ...summaryFixture.payments,
          revenueByCurrency: [
            {
              currency: 'RON',
              grossMinor: 100000,
              refundedMinor: 0,
              netMinor: 100000,
              testMode: false,
            },
          ],
        },
      }),
    );
    component.loadSummary();
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-testid="payment-mode-warning"]')).toBeTruthy();
    expect(el.querySelector('[data-testid="stripe-test-mode-badge"]')).toBeFalsy();
    const card = el.querySelector('[data-testid="revenue-RON"]');
    expect(card?.textContent).toContain('Payment mode unverified');
    expect(card?.querySelector('.analytics-dashboard__mini-badge')?.textContent).not.toContain(
      'Stripe Test Mode',
    );
  });

  it('should never apply or export a stale session when Apply precedes the options response', async () => {
    await setup();
    const session$ = new Subject<AnalyticsSessionFilterOptions>();
    apiSpy.getSessionFilterOptions.and.returnValue(session$.asObservable());

    // Session selected while ownership is unknown until the options arrive.
    component.draftSessionId.set('session-A');
    component.onDraftEventChange('event-B');
    expect(component.sessionReconcilePending()).toBeTrue();

    // Immediate Apply must not copy the possibly-stale session.
    apiSpy.getSummary.calls.reset();
    apiSpy.getTimeSeries.calls.reset();
    apiSpy.exportDailyCsv.calls.reset();
    component.applyFilters();
    fixture.detectChanges();

    expect(component.appliedFilters().eventId).toBe('event-B');
    expect(component.appliedFilters().eventSessionId).toBeNull();
    const summaryArgs = apiSpy.getSummary.calls.mostRecent().args[0] as {
      eventId?: string;
      eventSessionId?: string;
    };
    expect(summaryArgs.eventId).toBe('event-B');
    expect(summaryArgs.eventSessionId).toBeUndefined();

    // A late options arrival cannot restore the stale session.
    session$.next({ items: [], totalProjected: 0, truncated: false });
    session$.complete();
    fixture.detectChanges();
    expect(component.draftSessionId()).toBeNull();
    expect(component.appliedFilters().eventSessionId).toBeNull();

    component.exportCsv();
    const exportArgs = apiSpy.exportDailyCsv.calls.mostRecent().args[0] as {
      eventId?: string;
      eventSessionId?: string;
    };
    expect(exportArgs.eventId).toBe('event-B');
    expect(exportArgs.eventSessionId).toBeUndefined();
    for (const args of apiSpy.getSummary.calls.allArgs()) {
      expect((args[0] as { eventSessionId?: string }).eventSessionId).toBeUndefined();
    }
    for (const args of apiSpy.getTimeSeries.calls.allArgs()) {
      expect((args[1] as { eventSessionId?: string }).eventSessionId).toBeUndefined();
    }
  });

  it('should key reconciliation to the newest event when options requests supersede', async () => {
    await setup();
    const responses: Record<string, Subject<AnalyticsSessionFilterOptions>> = {
      'event-B': new Subject<AnalyticsSessionFilterOptions>(),
      'event-C': new Subject<AnalyticsSessionFilterOptions>(),
    };
    apiSpy.getSessionFilterOptions.and.callFake((params?: { eventId?: string }) =>
      (responses[params?.eventId ?? ''] ?? responses['event-C']).asObservable(),
    );

    component.draftSessionId.set('session-A');
    component.onDraftEventChange('event-B');
    expect(component.sessionReconcilePending()).toBeTrue();
    component.onDraftEventChange('event-C');
    expect(component.sessionReconcileEvent()).toBe('event-C');

    // The superseded B request is cancelled: its late emission is ignored.
    responses['event-B'].next({
      items: [
        { eventSessionId: 'session-A', eventId: 'event-B', label: 'A', startsAt: null },
      ],
      totalProjected: 1,
      truncated: false,
    });
    responses['event-B'].complete();
    fixture.detectChanges();
    expect(component.draftSessionId()).toBe('session-A');
    expect(component.sessionReconcilePending()).toBeTrue();

    responses['event-C'].next({ items: [], totalProjected: 0, truncated: false });
    responses['event-C'].complete();
    fixture.detectChanges();
    expect(component.draftSessionId()).toBeNull();
    expect(component.sessionReconcilePending()).toBeFalse();
  });

  it('should apply a compatible session once its reconciliation completes', async () => {
    await setup();
    const session$ = new Subject<AnalyticsSessionFilterOptions>();
    apiSpy.getSessionFilterOptions.and.returnValue(session$.asObservable());

    component.draftSessionId.set('session-A');
    component.onDraftEventChange('event-B');
    session$.next({
      items: [
        { eventSessionId: 'session-A', eventId: 'event-B', label: 'A', startsAt: null },
      ],
      totalProjected: 1,
      truncated: false,
    });
    session$.complete();
    fixture.detectChanges();
    expect(component.draftSessionId()).toBe('session-A');
    expect(component.sessionReconcilePending()).toBeFalse();

    apiSpy.getSummary.calls.reset();
    component.applyFilters();
    expect(component.appliedFilters().eventSessionId).toBe('session-A');
    const summaryArgs = apiSpy.getSummary.calls.mostRecent().args[0] as {
      eventSessionId?: string;
    };
    expect(summaryArgs.eventSessionId).toBe('session-A');
  });

  it('should clear a synchronously known incompatible session on event change', async () => {
    await setup();
    component.sessionOptions.set([
      { eventSessionId: 'session-A', eventId: 'event-A', label: 'A', startsAt: null },
    ]);
    const session$ = new Subject<AnalyticsSessionFilterOptions>();
    apiSpy.getSessionFilterOptions.and.returnValue(session$.asObservable());

    component.draftSessionId.set('session-A');
    component.onDraftEventChange('event-B');

    // Ownership was known from the current options: cleared without waiting.
    expect(component.draftSessionId()).toBeNull();
    session$.next({ items: [], totalProjected: 0, truncated: false });
    session$.complete();
  });

  it('should never apply a stale session selected while the new event options are pending', async () => {
    await setup();
    const session$ = new Subject<AnalyticsSessionFilterOptions>();
    apiSpy.getSessionFilterOptions.and.returnValue(session$.asObservable());

    // Old-scope options are still rendered when the event switch happens and
    // no session is selected at that moment (REV-002 reopened sequence).
    component.sessionOptions.set([
      { eventSessionId: 'stale-A', eventId: 'event-A', label: 'Stale A', startsAt: null },
    ]);
    component.draftEventId.set('event-A');
    component.draftSessionId.set(null);
    component.onDraftEventChange('event-B');

    // While the new event's options load, the session select offers no
    // stale option as current.
    fixture.detectChanges();
    const sessionSelect = fixture.nativeElement.querySelector(
      '[data-testid="filter-session"]',
    ) as HTMLSelectElement;
    expect(sessionSelect.disabled).toBeTrue();
    expect(component.sessionOptions()).toEqual([]);

    // While the new event's options are still loading, the user selects the
    // still-visible stale option. The stale selection must be rejected.
    component.onDraftSessionChange('stale-A');
    expect(component.draftSessionId()).toBeNull();

    // Immediate Apply must never copy the incompatible pair.
    apiSpy.getSummary.calls.reset();
    apiSpy.getTimeSeries.calls.reset();
    apiSpy.exportDailyCsv.calls.reset();
    component.applyFilters();
    fixture.detectChanges();

    expect(component.appliedFilters().eventId).toBe('event-B');
    expect(component.appliedFilters().eventSessionId).toBeNull();
    const summaryArgs = apiSpy.getSummary.calls.mostRecent().args[0] as {
      eventId?: string;
      eventSessionId?: string;
    };
    expect(summaryArgs.eventId).toBe('event-B');
    expect(summaryArgs.eventSessionId).toBeUndefined();
    for (const args of apiSpy.getTimeSeries.calls.allArgs()) {
      expect((args[1] as { eventSessionId?: string }).eventSessionId).toBeUndefined();
    }

    component.exportCsv();
    const exportArgs = apiSpy.exportDailyCsv.calls.mostRecent().args[0] as {
      eventId?: string;
      eventSessionId?: string;
    };
    expect(exportArgs.eventId).toBe('event-B');
    expect(exportArgs.eventSessionId).toBeUndefined();

    // The late options arrival settles loading and must not restore the stale
    // session into draft or applied state.
    session$.next({ items: [], totalProjected: 0, truncated: false });
    session$.complete();
    fixture.detectChanges();
    expect(component.draftSessionId()).toBeNull();
    expect(component.appliedFilters().eventSessionId).toBeNull();
    expect(component.sessionOptionsLoading()).toBeFalse();
    expect(
      (fixture.nativeElement.querySelector('[data-testid="filter-session"]') as HTMLSelectElement)
        .disabled,
    ).toBeFalse();
  });

  it('should distinguish post-success refresh from initial loading without false freshness', async () => {
    await setup();
    expect(component.summaryState()).toBe('success');

    const pending$ = new Subject<AnalyticsSummary>();
    apiSpy.getSummary.and.returnValue(pending$.asObservable());
    component.loadSummary();
    fixture.detectChanges();

    expect(component.summaryState()).toBe('refreshing');
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-testid="summary-refreshing"]')).toBeTruthy();
    expect(el.querySelector('[data-testid="freshness-disclosure"]')?.textContent).toContain(
      'unavailable while analytics load',
    );
    expect(el.querySelector('[data-testid="freshness-disclosure"]')?.textContent).not.toContain(
      'No projected events yet',
    );

    pending$.next(summaryFixture);
    pending$.complete();
    fixture.detectChanges();
    expect(component.summaryState()).toBe('success');
    expect(el.querySelector('[data-testid="summary-refreshing"]')).toBeFalsy();
  });

  it('should render initial loading without a refresh indicator or false freshness', async () => {
    await setup();
    const pending$ = new Subject<AnalyticsSummary>();
    apiSpy.getSummary.and.returnValue(pending$.asObservable());
    component.summaryState.set('idle');
    component.summary.set(null);
    component.loadSummary();
    fixture.detectChanges();

    expect(component.summaryState()).toBe('loading');
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-testid="summary-refreshing"]')).toBeFalsy();
    expect(el.querySelector('[data-testid="freshness-disclosure"]')?.textContent).toContain(
      'unavailable while analytics load',
    );
    expect(el.querySelector('[data-testid="freshness-disclosure"]')?.textContent).not.toContain(
      'No projected events yet',
    );
    pending$.next(summaryFixture);
    pending$.complete();
    fixture.detectChanges();
    expect(component.summaryState()).toBe('success');
  });

  it('should render backend validation failures distinctly from unavailable', async () => {
    await setup();
    apiSpy.getSummary.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            error: {
              status: 400,
              error: 'Bad Request',
              errorCode: 'INVALID_ANALYTICS_DATE_RANGE',
              message: 'Analytics range exceeds the 366-day maximum.',
              path: '/api/admin/analytics/summary',
              timestamp: '2026-09-06T12:00:00Z',
            },
            status: 400,
            statusText: 'Bad Request',
          }),
      ),
    );
    component.loadSummary();
    fixture.detectChanges();

    expect(component.summaryState()).toBe('validation-error');
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-testid="summary-validation-error"]')?.textContent).toContain(
      'Analytics range exceeds the 366-day maximum.',
    );
    expect(el.querySelector('[data-testid="summary-error"]')).toBeFalsy();
    expect(el.querySelector('[data-testid="freshness-disclosure"]')?.textContent).not.toContain(
      'No projected events yet',
    );
  });

  it('should render 5xx and network failures as unavailable without false freshness', async () => {
    await setup();
    apiSpy.getSummary.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 500, statusText: 'Server Error' })),
    );
    component.loadSummary();
    fixture.detectChanges();

    expect(component.summaryState()).toBe('unavailable');
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-testid="summary-error"]')).toBeTruthy();
    expect(el.querySelector('[data-testid="summary-validation-error"]')).toBeFalsy();
    expect(el.querySelector('[data-testid="freshness-disclosure"]')?.textContent).not.toContain(
      'No projected events yet',
    );

    apiSpy.getTimeSeries.and.returnValue(
      throwError(
        () => new HttpErrorResponse({ error: new ProgressEvent('error'), status: 0 }),
      ),
    );
    component.loadTrend();
    fixture.detectChanges();
    expect(component.trendState()).toBe('unavailable');
    expect(el.querySelector('[data-testid="trend-error"]')).toBeTruthy();
  });

  it('should keep a session-options failure visible after event options succeed', async () => {
    await setup();
    const event$ = new Subject<AnalyticsEventFilterOptions>();
    const session$ = new Subject<AnalyticsSessionFilterOptions>();
    apiSpy.getEventFilterOptions.and.returnValue(event$.asObservable());
    apiSpy.getSessionFilterOptions.and.returnValue(session$.asObservable());
    component.sessionOptions.set([
      { eventSessionId: 'stale-session', eventId: 'stale-event', label: 'Stale', startsAt: null },
    ]);

    component.reloadFilterOptions();
    session$.error(new HttpErrorResponse({ status: 500, statusText: 'Server Error' }));
    event$.next({ items: [], totalProjected: 0, truncated: false });
    event$.complete();
    fixture.detectChanges();

    // The later event success must not mask the session failure, and stale
    // choices must not be presented as current.
    expect(component.sessionOptionsError()).toBeTrue();
    expect(component.eventOptionsError()).toBeFalse();
    expect(component.filterOptionsError()).toBeTrue();
    expect(component.sessionOptions()).toEqual([]);
    expect(component.eventOptionsLoading()).toBeFalse();
    expect(component.sessionOptionsLoading()).toBeFalse();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-testid="filter-options-error"]')).toBeTruthy();
  });

  it('should keep an event-options failure visible after session options succeed', async () => {
    await setup();
    const event$ = new Subject<AnalyticsEventFilterOptions>();
    const session$ = new Subject<AnalyticsSessionFilterOptions>();
    apiSpy.getEventFilterOptions.and.returnValue(event$.asObservable());
    apiSpy.getSessionFilterOptions.and.returnValue(session$.asObservable());
    component.eventOptions.set([
      { eventId: 'stale-event', label: 'Stale', firstProjectedSessionStart: null },
    ]);

    component.reloadFilterOptions();
    event$.error(new HttpErrorResponse({ status: 500, statusText: 'Server Error' }));
    session$.next({ items: [], totalProjected: 0, truncated: false });
    session$.complete();
    fixture.detectChanges();

    expect(component.eventOptionsError()).toBeTrue();
    expect(component.sessionOptionsError()).toBeFalse();
    expect(component.filterOptionsError()).toBeTrue();
    expect(component.eventOptions()).toEqual([]);
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-testid="filter-options-error"]')).toBeTruthy();
  });
});
