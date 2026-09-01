import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ValidateTicketRequest, ValidationResultResponse } from '../models/scanner.model';

@Injectable({ providedIn: 'root' })
export class ScannerApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/scanner/tickets';

  validateTicket(ticketCode: string, scannerDeviceId: string): Observable<ValidationResultResponse> {
    const payload: ValidateTicketRequest = { ticketCode, scannerDeviceId };
    return this.http.post<ValidationResultResponse>(`${this.baseUrl}/validate`, payload);
  }
}
