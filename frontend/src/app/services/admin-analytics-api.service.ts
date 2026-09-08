import { HttpClient, HttpErrorResponse, HttpParams, HttpResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { catchError, from, mergeMap, Observable, throwError } from 'rxjs';
import { PagedResult } from '../models/event.model';
import { ApiErrorResponse } from '../models/api-error.model';
import {
  AnalyticsEventFilterOptions,
  AnalyticsMetric,
  AnalyticsQueryParams,
  AnalyticsSessionFilterOptions,
  AnalyticsSessionQueryParams,
  AnalyticsSummary,
  AnalyticsTimeSeries,
  EventSessionAnalytics,
} from '../models/admin-analytics.model';

/**
 * Narrow an unknown error payload to the shared `ApiErrorResponse` envelope.
 * Returns null for Blobs (async decode via `decodeExportErrorBody`), malformed
 * JSON, or any shape without a string `errorCode` — callers fall back safely.
 */
export function asApiErrorEnvelope(value: unknown): ApiErrorResponse | null {
  if (!value || typeof value !== 'object' || value instanceof Blob) {
    return null;
  }
  const code = (value as { errorCode?: unknown }).errorCode;
  if (typeof code !== 'string' || code === '') {
    return null;
  }
  return value as ApiErrorResponse;
}

/**
 * Decode a CSV-export error body, which the browser delivers as a `Blob`
 * because the request uses `responseType: 'blob'`. Resolves to the validated
 * envelope, or null when the body is missing/malformed/non-JSON.
 */
export function decodeExportErrorBody(error: unknown): Promise<ApiErrorResponse | null> {
  if (!(error instanceof Blob)) {
    return Promise.resolve(asApiErrorEnvelope(error));
  }
  return error.text().then(
    (text) => {
      if (!text) {
        return null;
      }
      try {
        return asApiErrorEnvelope(JSON.parse(text) as unknown);
      } catch {
        return null;
      }
    },
    () => null,
  );
}

/**
 * Re-throw a CSV-export failure with a decoded `Blob` JSON error body, so
 * downstream mapping (e.g. `ANALYTICS_EXPORT_TOO_LARGE`) sees the stable
 * error code. Malformed bodies pass through untouched for a safe fallback.
 */
function withDecodedExportError(err: unknown): Observable<never> {
  if (err instanceof HttpErrorResponse && err.error instanceof Blob) {
    return from(decodeExportErrorBody(err.error)).pipe(
      mergeMap((parsed) =>
        throwError(() =>
          parsed
            ? new HttpErrorResponse({
                error: parsed,
                headers: err.headers,
                status: err.status,
                statusText: err.statusText,
                url: err.url ?? undefined,
              })
            : err,
        ),
      ),
    );
  }
  return throwError(() => err);
}

/**
 * Parse a `Content-Disposition` attachment filename defensively.
 * Returns the `fallback` when the header is missing, unparsable, or unsafe
 * (path segments, control characters, reserved filename characters).
 */
export function resolveAnalyticsExportFilename(
  response: HttpResponse<Blob>,
  fallback: string,
): string {
  const header = response.headers.get('Content-Disposition') ?? '';
  const match = /filename\*?\s*=\s*(?:"([^"]+)"|([^;,\s]+))/i.exec(header);
  let raw = (match?.[1] ?? match?.[2] ?? '').trim().replace(/^UTF-8''/i, '');
  try {
    raw = decodeURIComponent(raw);
  } catch {
    // Keep the raw value; sanitization below still applies.
  }
  // A trusted server filename is a bare name: any path separator, reserved
  // character, or control character rejects the header in favor of the fallback.
  if (
    raw === '' ||
    raw === '.' ||
    raw === '..' ||
    raw.includes('/') ||
    raw.includes('\\') ||
    // eslint-disable-next-line no-control-regex
    /[<>:"|?*\x00-\x1f]/.test(raw)
  ) {
    return fallback;
  }
  return raw;
}

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

  getEventFilterOptions(params?: AnalyticsQueryParams): Observable<AnalyticsEventFilterOptions> {
    return this.http.get<AnalyticsEventFilterOptions>(
      `${this.baseUrl}/filter-options/events`,
      { params: this.buildRangeParams(params) },
    );
  }

  getSessionFilterOptions(params?: AnalyticsQueryParams): Observable<AnalyticsSessionFilterOptions> {
    return this.http.get<AnalyticsSessionFilterOptions>(
      `${this.baseUrl}/filter-options/sessions`,
      { params: this.buildRangeParams(params) },
    );
  }

  /**
   * Request the server-built CSV for the **applied** filters. The full export is
   * generated server-side; the dashboard never builds CSV from the table page.
   */
  exportDailyCsv(params?: AnalyticsQueryParams): Observable<HttpResponse<Blob>> {
    return this.http
      .get(`${this.baseUrl}/export/daily.csv`, {
        params: this.buildRangeParams(params),
        observe: 'response',
        responseType: 'blob',
      })
      .pipe(catchError((err: unknown) => withDecodedExportError(err)));
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
