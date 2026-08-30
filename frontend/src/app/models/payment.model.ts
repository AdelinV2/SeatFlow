export interface CreatePaymentIntentRequest {
  reservationId: string;
  idempotencyKey: string;
}

export interface PaymentIntentResponse {
  paymentId: string;
  clientSecret: string;
  amount: number;
  currency: string;
  status: string;
}

export type PaymentStatus = 'INITIATED' | 'SUCCESS' | 'FAILED' | 'REFUNDED';

export interface PaymentStatusResponse {
  id: string;
  reservationId: string;
  customerEmail: string;
  amount: number;
  taxAmount: number;
  netAmount: number;
  currency: string;
  status: PaymentStatus;
  createdAt: string;
}

export interface TaxPreviewRequest {
  line1: string;
  line2?: string;
  city: string;
  state?: string;
  postalCode: string;
  country: string;
}

export interface TaxPreviewResponse {
  taxAmount: number;
  effectiveRate: number;
  currency: string;
}
