import { TestBed } from '@angular/core/testing';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import {
  HttpErrorResponse,
  HttpHeaders,
  HttpResponse,
  provideHttpClient,
} from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import {
  AdminAnalyticsApiService,
  resolveAnalyticsExportFilename,
} from './admin-analytics-api.service';
import { AnalyticsSummary, AnalyticsTimeSeries } from '../models/admin-analytics.model';
import { PagedResult } from '../models/event.model';

describe('AdminAnalyticsApiService', () => {
  let service: AdminAnalyticsApiService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), AdminAnalyticsApiService],
    });
    service = TestBed.inject(AdminAnalyticsApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should call the exact summary path and omit undefined filters', () => {
    const mock: AnalyticsSummary = {
      from: '2026-08-08',
      to: '2026-09-06',
      filters: { eventId: null, eventSessionId: null },
      reservations: { created: 10, confirmed: 7, expired: 2, refunded: 1 },
      tickets: { issued: 14, revoked: 2, scanned: 9 },
      payments: {
        succeeded: 7,
        withFailure: 1,
        refundsCompleted: 1,
        revenueByCurrency: [
          { currency: 'RON', grossMinor: 315000, refundedMinor: 12000, netMinor: 303000, testMode: true },
        ],
      },
      rates: {
        reservationToPayment: { numerator: 7, denominator: 10, ratio: 0.7 },
        expiration: { numerator: 2, denominator: 10, ratio: 0.2 },
        refund: { numerator: 1, denominator: 7, ratio: 0.142857 },
      },
      freshness: {
        generatedAt: '2026-09-06T12:00:00Z',
        lastProjectedEventAt: '2026-09-06T11:59:42Z',
        lastProcessedAt: '2026-09-06T11:59:43Z',
        eventuallyConsistent: true,
      },
    };

    service.getSummary({ from: '2026-08-08', to: '2026-09-06' }).subscribe((res) => {
      expect(res).toEqual(mock);
      // minor units preserved exactly, no float conversion
      expect(res.payments.revenueByCurrency[0].grossMinor).toBe(315000);
      expect(res.payments.revenueByCurrency[0].netMinor).toBe(303000);
    });

    const req = httpMock.expectOne(
      (r) => r.url === '/api/admin/analytics/summary' && r.method === 'GET',
    );
    expect(req.request.params.get('from')).toBe('2026-08-08');
    expect(req.request.params.get('to')).toBe('2026-09-06');
    expect(req.request.params.has('eventId')).toBeFalse();
    expect(req.request.params.has('eventSessionId')).toBeFalse();
    req.flush(mock);
  });

  it('should call the exact timeseries path with metric and omit undefined filters', () => {
    const mock: AnalyticsTimeSeries = {
      metric: 'NET_REVENUE',
      from: '2026-08-08',
      to: '2026-09-06',
      series: [
        {
          currency: 'RON',
          testMode: true,
          points: [{ date: '2026-09-06', value: 303000 }],
        },
      ],
    };

    service.getTimeSeries('NET_REVENUE').subscribe((res) => {
      expect(res.series.length).toBe(1);
      expect(res.series[0]).toEqual(
        jasmine.objectContaining({ currency: 'RON', testMode: true }),
      );
    });

    const req = httpMock.expectOne(
      (r) => r.url === '/api/admin/analytics/timeseries' && r.method === 'GET',
    );
    expect(req.request.params.get('metric')).toBe('NET_REVENUE');
    expect(req.request.params.has('from')).toBeFalse();
    expect(req.request.params.has('to')).toBeFalse();
    req.flush(mock);
  });

  it('should call the exact sessions path with paging params and omit undefined', () => {
    const mock: PagedResult<never> = {
      content: [],
      page: 0,
      size: 25,
      totalElements: 0,
      totalPages: 0,
      isFirst: true,
      isLast: true,
    };

    service.getSessions({ page: 0, size: 25, sort: 'startsAt,desc' }).subscribe();
    const req = httpMock.expectOne(
      (r) => r.url === '/api/admin/analytics/sessions' && r.method === 'GET',
    );
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('size')).toBe('25');
    expect(req.request.params.get('sort')).toBe('startsAt,desc');
    expect(req.request.params.has('currency')).toBeFalse();
    expect(req.request.params.has('from')).toBeFalse();
    req.flush(mock);
  });

  it('should pass the currency through for financial session sorts', () => {
    const mock: PagedResult<never> = {
      content: [],
      page: 0,
      size: 25,
      totalElements: 0,
      totalPages: 0,
      isFirst: true,
      isLast: true,
    };

    service
      .getSessions({ page: 0, size: 25, sort: 'grossRevenue,desc', currency: 'RON' })
      .subscribe();
    const req = httpMock.expectOne(
      (r) => r.url === '/api/admin/analytics/sessions' && r.method === 'GET',
    );
    expect(req.request.params.get('sort')).toBe('grossRevenue,desc');
    expect(req.request.params.get('currency')).toBe('RON');
    req.flush(mock);
  });

  it('should preserve separate per-currency revenue entries without merging', () => {
    const mock: AnalyticsSummary = {
      from: '2026-09-06',
      to: '2026-09-06',
      filters: { eventId: null, eventSessionId: null },
      reservations: { created: 2, confirmed: 2, expired: 0, refunded: 0 },
      tickets: { issued: 2, revoked: 0, scanned: 0 },
      payments: {
        succeeded: 2,
        withFailure: 0,
        refundsCompleted: 0,
        revenueByCurrency: [
          { currency: 'RON', grossMinor: 10000, refundedMinor: 0, netMinor: 10000, testMode: true },
          { currency: 'EUR', grossMinor: 2000, refundedMinor: 0, netMinor: 2000, testMode: true },
        ],
      },
      rates: {
        reservationToPayment: { numerator: 2, denominator: 2, ratio: 1 },
        expiration: { numerator: 0, denominator: 2, ratio: 0 },
        refund: { numerator: 0, denominator: 2, ratio: 0 },
      },
      freshness: {
        generatedAt: '2026-09-06T12:00:00Z',
        lastProjectedEventAt: '2026-09-06T11:59:42Z',
        lastProcessedAt: '2026-09-06T11:59:43Z',
        eventuallyConsistent: true,
      },
    };

    service.getSummary({ from: '2026-09-06', to: '2026-09-06' }).subscribe((res) => {
      expect(res.payments.revenueByCurrency.length).toBe(2);
      expect(
        res.payments.revenueByCurrency.find((r) => r.currency === 'RON')?.grossMinor,
      ).toBe(10000);
      expect(
        res.payments.revenueByCurrency.find((r) => r.currency === 'EUR')?.grossMinor,
      ).toBe(2000);
      // no mixed-currency total is ever synthesized client-side
      expect(
        res.payments.revenueByCurrency.find((r) => r.currency === 'TOTAL'),
      ).toBeUndefined();
      expect(res.payments.revenueByCurrency.every((r) => r.testMode)).toBeTrue();
    });

    const req = httpMock.expectOne(
      (r) => r.url === '/api/admin/analytics/summary' && r.method === 'GET',
    );
    req.flush(mock);
  });

  it('should call the event filter-options path with the date range unchanged', () => {
    service.getEventFilterOptions({ from: '2026-09-05', to: '2026-09-06' }).subscribe((res) => {
      expect(res.truncated).toBeFalse();
      expect(res.totalProjected).toBe(0);
    });
    const req = httpMock.expectOne(
      (r) => r.url === '/api/admin/analytics/filter-options/events' && r.method === 'GET',
    );
    expect(req.request.params.get('from')).toBe('2026-09-05');
    expect(req.request.params.get('to')).toBe('2026-09-06');
    req.flush({ items: [], totalProjected: 0, truncated: false });
  });

  it('should call the session filter-options path with the optional event scope', () => {
    service
      .getSessionFilterOptions({ from: '2026-09-05', to: '2026-09-06', eventId: 'event-1' })
      .subscribe();
    const req = httpMock.expectOne(
      (r) => r.url === '/api/admin/analytics/filter-options/sessions' && r.method === 'GET',
    );
    expect(req.request.params.get('from')).toBe('2026-09-05');
    expect(req.request.params.get('to')).toBe('2026-09-06');
    expect(req.request.params.get('eventId')).toBe('event-1');
    req.flush({ items: [], totalProjected: 0, truncated: false });
  });

  it('should request the CSV export as a Blob response with applied filters', () => {
    service
      .exportDailyCsv({ from: '2026-09-05', to: '2026-09-06', eventId: 'event-1' })
      .subscribe((res) => {
        expect(res.body instanceof Blob).toBeTrue();
      });
    const req = httpMock.expectOne(
      (r) => r.url === '/api/admin/analytics/export/daily.csv' && r.method === 'GET',
    );
    expect(req.request.responseType).toBe('blob');
    expect(req.request.params.get('from')).toBe('2026-09-05');
    expect(req.request.params.get('eventId')).toBe('event-1');
    req.flush(new Blob(['row_type,metric_date\n'], { type: 'text/csv' }));
  });

  it('should decode a Blob JSON error envelope for CSV export failures', async () => {
    // REV-003: the browser delivers the JSON error body as a Blob because the
    // request uses responseType 'blob'; the service must restore the stable code.
    const envelope = {
      status: 400,
      error: 'Bad Request',
      errorCode: 'ANALYTICS_EXPORT_TOO_LARGE',
      message: 'Analytics export matches 10001 rows, maximum is 10000.',
      path: '/api/admin/analytics/export/daily.csv',
      timestamp: '2026-09-06T12:00:00Z',
    };
    const pending = firstValueFrom(
      service.exportDailyCsv({ from: '2026-09-05', to: '2026-09-06' }),
    );
    const req = httpMock.expectOne(
      (r) => r.url === '/api/admin/analytics/export/daily.csv' && r.method === 'GET',
    );
    req.flush(new Blob([JSON.stringify(envelope)], { type: 'application/json' }), {
      status: 400,
      statusText: 'Bad Request',
    });

    try {
      await pending;
      fail('expected the CSV export to fail');
    } catch (err: unknown) {
      expect(err instanceof HttpErrorResponse).toBeTrue();
      expect((err as HttpErrorResponse).status).toBe(400);
      expect(
        ((err as HttpErrorResponse).error as { errorCode?: string }).errorCode,
      ).toBe('ANALYTICS_EXPORT_TOO_LARGE');
    }
  });

  it('should pass through a malformed Blob export error body for a safe fallback', async () => {
    const pending = firstValueFrom(
      service.exportDailyCsv({ from: '2026-09-05', to: '2026-09-06' }),
    );
    const req = httpMock.expectOne(
      (r) => r.url === '/api/admin/analytics/export/daily.csv' && r.method === 'GET',
    );
    req.flush(new Blob(['<html>not json</html>'], { type: 'text/html' }), {
      status: 400,
      statusText: 'Bad Request',
    });

    try {
      await pending;
      fail('expected the CSV export to fail');
    } catch (err: unknown) {
      expect(err instanceof HttpErrorResponse).toBeTrue();
      expect((err as HttpErrorResponse).error instanceof Blob).toBeTrue();
    }
  });

  describe('resolveAnalyticsExportFilename', () => {
    function responseWith(header: string | null): HttpResponse<Blob> {
      let headers = new HttpHeaders();
      if (header !== null) {
        headers = headers.set('Content-Disposition', header);
      }
      return new HttpResponse<Blob>({ body: new Blob(['x']), headers });
    }

    it('parses the quoted server-generated filename', () => {
      expect(
        resolveAnalyticsExportFilename(
          responseWith('attachment; filename="seatflow-analytics-2026-09-05-2026-09-06.csv"'),
          'fallback.csv',
        ),
      ).toBe('seatflow-analytics-2026-09-05-2026-09-06.csv');
    });

    it('falls back when the header is missing', () => {
      expect(resolveAnalyticsExportFilename(responseWith(null), 'fallback.csv')).toBe(
        'fallback.csv',
      );
    });

    it('falls back for path-traversal filenames instead of trusting the header', () => {
      expect(
        resolveAnalyticsExportFilename(
          responseWith('attachment; filename="../../evil.csv"'),
          'fallback.csv',
        ),
      ).toBe('fallback.csv');
    });
  });

  it('should call the exact session detail path', () => {
    service.getSession('session-1').subscribe();
    const req = httpMock.expectOne('/api/admin/analytics/sessions/session-1');
    expect(req.request.method).toBe('GET');
    req.flush({
      eventId: 'event-1',
      eventSessionId: 'session-1',
      eventTitle: null,
      sessionLabel: null,
      startsAt: null,
      status: null,
      capacitySnapshot: null,
      reservationsCreated: 0,
      reservationsConfirmed: 0,
      reservationsExpired: 0,
      paymentsSucceeded: 0,
      paymentsWithFailure: 0,
      refundsCompleted: 0,
      ticketsIssued: 0,
      ticketsRevoked: 0,
      ticketsScanned: 0,
      revenueByCurrency: [],
      occupancyRatio: null,
      attendanceRatio: null,
      lastProjectedEventAt: null,
    });
  });
});
