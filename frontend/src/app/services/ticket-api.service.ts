import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { PagedResult } from '../models/event.model';
import { TicketItem } from '../models/ticket.model';

@Injectable({ providedIn: 'root' })
export class TicketApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/tickets';

  getMyTickets(page = 0, size = 10): Observable<PagedResult<TicketItem>> {
    const params = new HttpParams().set('page', page.toString()).set('size', size.toString());
    return this.http.get<PagedResult<TicketItem>>(`${this.baseUrl}/my-tickets`, { params });
  }

  getGuestTicket(ticketCode: string): Observable<TicketItem> {
    return this.http.get<TicketItem>(`${this.baseUrl}/guest/${ticketCode}`);
  }

  getTicketById(ticketId: string): Observable<TicketItem> {
    return this.http.get<TicketItem>(`${this.baseUrl}/${ticketId}`);
  }

  downloadTicketPdf(ticketId: string): Observable<Blob> {
    return this.http.get(`${this.baseUrl}/${ticketId}/pdf`, { responseType: 'blob' });
  }
}
