import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { of, Subject, throwError } from 'rxjs';
import { AdminAnalyticsDashboardComponent } from './admin-analytics-dashboard.component';
import { AdminAnalyticsApiService } from '../../../../services/admin-analytics-api.service';
import {
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

  async function setup(): Promise<void> {
    apiSpy = jasmine.createSpyObj('AdminAnalyticsApiService', [
      'getSummary',
      'getTimeSeries',
      'getSessions',
    ]);
    apiSpy.getSummary.and.returnValue(of(summaryFixture));
    apiSpy.getTimeSeries.and.returnValue(of(trendFixture));
    apiSpy.getSessions.and.returnValue(of(sessionFixture()));

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
    expect(apiSpy.getTimeSeries).toHaveBeenCalledWith('NET_REVENUE');
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
});
