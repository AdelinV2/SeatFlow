import { HttpClient, HttpHeaders } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import {
  CreatePaymentIntentRequest,
  PaymentIntentResponse,
  PaymentStatusResponse,
  TaxPreviewRequest,
  TaxPreviewResponse,
} from '../models/payment.model';

@Injectable({ providedIn: 'root' })
export class PaymentApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/payments';

  createPaymentIntent(
    request: CreatePaymentIntentRequest,
    customerEmailProof?: string,
  ): Observable<PaymentIntentResponse> {
    return this.http.post<PaymentIntentResponse>(`${this.baseUrl}/intent`, request, {
      headers: this.guestProofHeaders(customerEmailProof),
    });
  }

  getPaymentStatus(paymentId: string, customerEmailProof?: string): Observable<PaymentStatusResponse> {
    return this.http.get<PaymentStatusResponse>(`${this.baseUrl}/${paymentId}`, {
      headers: this.guestProofHeaders(customerEmailProof),
    });
  }

  previewTax(
    paymentId: string,
    request: TaxPreviewRequest,
    customerEmailProof?: string,
  ): Observable<TaxPreviewResponse> {
    return this.http.post<TaxPreviewResponse>(`${this.baseUrl}/${paymentId}/tax-preview`, request, {
      headers: this.guestProofHeaders(customerEmailProof),
    });
  }

  private guestProofHeaders(customerEmailProof?: string): HttpHeaders {
    const normalizedEmail = customerEmailProof?.trim();
    return normalizedEmail ? new HttpHeaders({ 'X-Customer-Email': normalizedEmail }) : new HttpHeaders();
  }
}
