import { TestBed } from '@angular/core/testing';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { AdminAnalyticsApiService } from './admin-analytics-api.service';
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
