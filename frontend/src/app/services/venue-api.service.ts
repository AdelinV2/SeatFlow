import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { VenueDetail } from '../models/event.model';

@Injectable({ providedIn: 'root' })
export class VenueApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/venues';

  getVenueById(venueId: string): Observable<VenueDetail> {
    return this.http.get<VenueDetail>(`${this.baseUrl}/${venueId}`);
  }

  getVenues(): Observable<VenueDetail[]> {
    return this.http.get<VenueDetail[]>(this.baseUrl);
  }
}
