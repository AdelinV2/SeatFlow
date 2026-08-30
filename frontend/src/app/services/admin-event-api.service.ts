import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { EventDetail, EventPricingTier, PagedResult } from '../models/event.model';
import {
  ConfigurePricingRequest,
  CreateEventRequest,
  UpdateEventRequest,
} from '../models/admin-event.model';

@Injectable({ providedIn: 'root' })
export class AdminEventApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/admin/events';

  getAdminEvents(params?: {
    status?: string;
    search?: string;
    page?: number;
    size?: number;
  }): Observable<PagedResult<EventDetail>> {
    let httpParams = new HttpParams();
    if (params?.status && params.status !== 'ALL') {
      httpParams = httpParams.set('status', params.status);
    }
    if (params?.search && params.search.trim() !== '') {
      httpParams = httpParams.set('search', params.search.trim());
    }
    if (params?.page !== undefined && params.page !== null) {
      httpParams = httpParams.set('page', params.page.toString());
    }
    if (params?.size !== undefined && params.size !== null) {
      httpParams = httpParams.set('size', params.size.toString());
    }

    return this.http.get<PagedResult<EventDetail>>(this.baseUrl, { params: httpParams });
  }

  getEventById(id: string): Observable<EventDetail> {
    return this.http.get<EventDetail>(`${this.baseUrl}/${id}`);
  }

  createEvent(req: CreateEventRequest): Observable<EventDetail> {
    return this.http.post<EventDetail>(this.baseUrl, req);
  }

  updateEvent(id: string, req: UpdateEventRequest): Observable<EventDetail> {
    return this.http.put<EventDetail>(`${this.baseUrl}/${id}`, req);
  }

  configurePricing(eventId: string, req: ConfigurePricingRequest): Observable<EventPricingTier[]> {
    return this.http.post<EventPricingTier[]>(`${this.baseUrl}/${eventId}/pricing`, req);
  }
}
