import { HttpClient } from '@angular/common/http';
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

  createPaymentIntent(request: CreatePaymentIntentRequest): Observable<PaymentIntentResponse> {
    return this.http.post<PaymentIntentResponse>(`${this.baseUrl}/intent`, request);
  }

  getPaymentStatus(paymentId: string): Observable<PaymentStatusResponse> {
    return this.http.get<PaymentStatusResponse>(`${this.baseUrl}/${paymentId}`);
  }

  previewTax(paymentId: string, request: TaxPreviewRequest): Observable<TaxPreviewResponse> {
    return this.http.post<TaxPreviewResponse>(`${this.baseUrl}/${paymentId}/tax-preview`, request);
  }
}
