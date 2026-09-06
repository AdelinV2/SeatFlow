import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { PagedResult } from '../models/event.model';
import {
  AnalyticsMetric,
  AnalyticsQueryParams,
  AnalyticsSessionQueryParams,
  AnalyticsSummary,
  AnalyticsTimeSeries,
  EventSessionAnalytics,
} from '../models/admin-analytics.model';

@Injectable({ providedIn: 'root' })
export class AdminAnalyticsApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/admin/analytics';

  getSummary(params?: AnalyticsQueryParams): Observable<AnalyticsSummary> {
    return this.http.get<AnalyticsSummary>(`${this.baseUrl}/summary`, {
      params: this.buildRangeParams(params),
    });
  }

  getTimeSeries(
    metric: AnalyticsMetric,
    params?: AnalyticsQueryParams,
  ): Observable<AnalyticsTimeSeries> {
    let httpParams = this.buildRangeParams(params);
    httpParams = httpParams.set('metric', metric);
    return this.http.get<AnalyticsTimeSeries>(`${this.baseUrl}/timeseries`, {
      params: httpParams,
    });
  }

  getSessions(
    params?: AnalyticsSessionQueryParams,
  ): Observable<PagedResult<EventSessionAnalytics>> {
    let httpParams = this.buildRangeParams(params);
    if (params?.page !== undefined && params.page !== null) {
      httpParams = httpParams.set('page', String(params.page));
    }
    if (params?.size !== undefined && params.size !== null) {
      httpParams = httpParams.set('size', String(params.size));
    }
    if (params?.sort !== undefined && params.sort !== null && params.sort.trim() !== '') {
      httpParams = httpParams.set('sort', params.sort.trim());
    }
    if (params?.currency !== undefined && params.currency !== null && params.currency.trim() !== '') {
      httpParams = httpParams.set('currency', params.currency.trim());
    }
    return this.http.get<PagedResult<EventSessionAnalytics>>(`${this.baseUrl}/sessions`, {
      params: httpParams,
    });
  }

  getSession(sessionId: string): Observable<EventSessionAnalytics> {
    return this.http.get<EventSessionAnalytics>(`${this.baseUrl}/sessions/${sessionId}`);
  }

  private buildRangeParams(params?: AnalyticsQueryParams): HttpParams {
    let httpParams = new HttpParams();
    if (params?.from !== undefined && params.from !== null && params.from.trim() !== '') {
      httpParams = httpParams.set('from', params.from.trim());
    }
    if (params?.to !== undefined && params.to !== null && params.to.trim() !== '') {
      httpParams = httpParams.set('to', params.to.trim());
    }
    if (params?.eventId !== undefined && params.eventId !== null && params.eventId.trim() !== '') {
      httpParams = httpParams.set('eventId', params.eventId.trim());
    }
    if (
      params?.eventSessionId !== undefined &&
      params.eventSessionId !== null &&
      params.eventSessionId.trim() !== ''
    ) {
      httpParams = httpParams.set('eventSessionId', params.eventSessionId.trim());
    }
    return httpParams;
  }
}
