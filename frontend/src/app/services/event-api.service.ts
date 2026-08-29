import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import {
  EventCategory,
  EventDetail,
  EventSummary,
  PagedResult,
  VenueDetail,
} from '../models/event.model';
import { EventSeatMapResponse } from '../models/seat.model';

@Injectable({ providedIn: 'root' })
export class EventApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/events';
  private readonly venuesUrl = '/api/venues';

  getEvents(params?: {
    category?: EventCategory | string | null;
    search?: string | null;
    page?: number;
    size?: number;
    sort?: string;
  }): Observable<PagedResult<EventSummary>> {
    let httpParams = new HttpParams();

    if (params?.category) {
      httpParams = httpParams.set('category', params.category);
    }
    if (params?.search && params.search.trim() !== '') {
      httpParams = httpParams.set('search', params.search.trim());
    }
    if (params?.page !== undefined && params?.page !== null) {
      httpParams = httpParams.set('page', params.page.toString());
    }
    if (params?.size !== undefined && params?.size !== null) {
      httpParams = httpParams.set('size', params.size.toString());
    }
    if (params?.sort) {
      httpParams = httpParams.set('sort', params.sort);
    }

    return this.http.get<PagedResult<EventSummary>>(this.baseUrl, { params: httpParams });
  }

  getEventById(id: string): Observable<EventDetail> {
    return this.http.get<EventDetail>(`${this.baseUrl}/${id}`);
  }

  getVenueById(venueId: string): Observable<VenueDetail> {
    return this.http.get<VenueDetail>(`${this.venuesUrl}/${venueId}`);
  }

  getEventSeatMap(eventId: string): Observable<EventSeatMapResponse> {
    return this.http.get<EventSeatMapResponse>(`${this.baseUrl}/${eventId}/seat-map`);
  }
}
