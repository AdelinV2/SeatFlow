import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { CreatePaymentIntentRequest, TaxPreviewRequest } from '../models/payment.model';
import { PaymentApiService } from './payment-api.service';

describe('PaymentApiService', () => {
  let service: PaymentApiService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [PaymentApiService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(PaymentApiService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('creates a payment intent with the deterministic idempotency key', () => {
    const request: CreatePaymentIntentRequest = {
      reservationId: 'reservation-007',
      idempotencyKey: 'pay-intent-reservation-007',
    };

    service.createPaymentIntent(request).subscribe((response) => {
      expect(response.paymentId).toBe('payment-007');
      expect(response.amount).toBe(120);
    });

    const httpRequest = httpTesting.expectOne('/api/payments/intent');
    expect(httpRequest.request.method).toBe('POST');
    expect(httpRequest.request.body).toEqual(request);
    httpRequest.flush({
      paymentId: 'payment-007',
      clientSecret: 'pi_test_secret',
      amount: 120,
      currency: 'USD',
      status: 'INITIATED',
    });
  });

  it('adds a normalized guest email proof header to payment requests', () => {
    const request: TaxPreviewRequest = {
      line1: '1 Test Avenue',
      city: 'Bucharest',
      postalCode: '010101',
      country: 'RO',
    };

    service.previewTax('payment-007', request, ' guest@example.com ').subscribe();

    const httpRequest = httpTesting.expectOne('/api/payments/payment-007/tax-preview');
    expect(httpRequest.request.headers.get('X-Customer-Email')).toBe('guest@example.com');
    httpRequest.flush({ taxAmount: 19, effectiveRate: 19, currency: 'USD' });
  });

  it('loads payment status by payment id', () => {
    service.getPaymentStatus('payment-007').subscribe((response) => {
      expect(response.status).toBe('SUCCESS');
      expect(response.reservationId).toBe('reservation-007');
    });

    const httpRequest = httpTesting.expectOne('/api/payments/payment-007');
    expect(httpRequest.request.method).toBe('GET');
    httpRequest.flush({
      id: 'payment-007',
      reservationId: 'reservation-007',
      customerEmail: 'guest@example.com',
      amount: 120,
      currency: 'USD',
      status: 'SUCCESS',
      createdAt: '2026-08-30T10:00:00Z',
    });
  });

  it('previews the Stripe tax rate for a billing address', () => {
    const request: TaxPreviewRequest = {
      line1: '1 Test Avenue',
      city: 'Bucharest',
      postalCode: '010101',
      country: 'RO',
    };

    service.previewTax('payment-007', request).subscribe((response) => {
      expect(response.effectiveRate).toBe(19);
      expect(response.taxAmount).toBe(19);
    });

    const httpRequest = httpTesting.expectOne('/api/payments/payment-007/tax-preview');
    expect(httpRequest.request.method).toBe('POST');
    expect(httpRequest.request.body).toEqual(request);
    httpRequest.flush({ taxAmount: 19, effectiveRate: 19, currency: 'USD' });
  });
});
