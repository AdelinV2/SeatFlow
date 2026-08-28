import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { PagedResult } from '../models/event.model';
import {
  CreateSectionRequest,
  CreateVenueRequest,
  UpdateSeatStatusRequest,
  UpdateVenueRequest,
  VenueLayout,
  VenueSectionLayout,
  VenueSectionSeat,
  VenueSummary,
} from '../models/venue.model';

@Injectable({ providedIn: 'root' })
export class AdminVenueApiService {
  private readonly http = inject(HttpClient);
  private readonly publicVenuesUrl = '/api/venues';
  private readonly adminVenuesUrl = '/api/admin/venues';

  getVenues(filters?: {
    city?: string;
    name?: string;
    page?: number;
    size?: number;
  }): Observable<PagedResult<VenueSummary>> {
    let params = new HttpParams();
    if (filters?.city) {
      params = params.set('city', filters.city);
    }
    if (filters?.name) {
      params = params.set('name', filters.name);
    }
    if (filters?.page !== undefined) {
      params = params.set('page', filters.page.toString());
    }
    if (filters?.size !== undefined) {
      params = params.set('size', filters.size.toString());
    }

    return this.http.get<PagedResult<VenueSummary>>(this.publicVenuesUrl, { params });
  }

  getVenueById(venueId: string): Observable<VenueSummary> {
    return this.http.get<VenueSummary>(`${this.publicVenuesUrl}/${venueId}`);
  }

  getVenueLayout(venueId: string): Observable<VenueLayout> {
    return this.http.get<VenueLayout>(`${this.publicVenuesUrl}/${venueId}/layout`);
  }

  createVenue(req: CreateVenueRequest): Observable<VenueSummary> {
    return this.http.post<VenueSummary>(this.adminVenuesUrl, req);
  }

  updateVenue(venueId: string, req: UpdateVenueRequest): Observable<VenueSummary> {
    return this.http.put<VenueSummary>(`${this.adminVenuesUrl}/${venueId}`, req);
  }

  createSection(
    venueId: string,
    req: CreateSectionRequest
  ): Observable<VenueSectionLayout> {
    return this.http.post<VenueSectionLayout>(
      `${this.adminVenuesUrl}/${venueId}/sections`,
      req
    );
  }

  toggleSeat(
    venueId: string,
    sectionId: string,
    seatId: string,
    isActive: boolean
  ): Observable<VenueSectionSeat> {
    const payload: UpdateSeatStatusRequest = { isActive };
    return this.http.patch<VenueSectionSeat>(
      `${this.adminVenuesUrl}/${venueId}/sections/${sectionId}/seats/${seatId}`,
      payload
    );
  }
}
