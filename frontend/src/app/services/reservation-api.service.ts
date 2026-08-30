import { HttpClient, HttpHeaders } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, tap } from 'rxjs';

export interface CreateReservationRequest {
  eventId: string;
  customerEmail?: string;
  seatIds: string[];
  seatPrices: number[];
  idempotencyKey: string;
}

export interface ReservationSeatDetail {
  id?: string;
  seatId: string;
  status?: 'HELD' | 'CONFIRMED' | 'RELEASED' | 'EXPIRED';
  rowNumber?: string;
  seatNumber?: number;
  pricingTierId?: string;
  ticketType?: string;
  price: number;
}

export interface SeatPricingSelection {
  seatId: string;
  pricingTierId: string;
}

export interface UpdateReservationPricingRequest {
  seats: SeatPricingSelection[];
}

export interface ReservationResponse {
  id: string;
  eventId: string;
  userId?: string;
  customerEmail?: string;
  customerName?: string;
  status: 'PENDING' | 'CONFIRMED' | 'CANCELLED' | 'EXPIRED';
  expiresAt: string;
  totalAmount: number;
  seatCount?: number;
  seats: ReservationSeatDetail[];
  createdAt?: string;
}

@Injectable({ providedIn: 'root' })
export class ReservationApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/reservations';
  private readonly guestProofStoragePrefix = 'seatflow:reservation-email:';

  createReservation(request: CreateReservationRequest): Observable<ReservationResponse> {
    return this.http.post<ReservationResponse>(this.baseUrl, request).pipe(
      tap((reservation) => this.rememberGuestProof(reservation.id, request.customerEmail)),
    );
  }

  getStoredCustomerEmailProof(reservationId: string): string | undefined {
    try {
      return globalThis.sessionStorage.getItem(this.guestProofStoragePrefix + reservationId) ?? undefined;
    } catch {
      return undefined;
    }
  }

  clearStoredCustomerEmailProof(reservationId: string): void {
    try {
      globalThis.sessionStorage.removeItem(this.guestProofStoragePrefix + reservationId);
    } catch {
      // Session storage can be unavailable in privacy-restricted browser contexts.
    }
  }

  getReservation(
    reservationId: string,
    customerEmailProof?: string,
  ): Observable<ReservationResponse> {
    return this.http.get<ReservationResponse>(`${this.baseUrl}/${reservationId}`, {
      headers: this.guestProofHeaders(customerEmailProof),
    });
  }

  updateReservationPricing(
    reservationId: string,
    request: UpdateReservationPricingRequest,
    customerEmailProof?: string,
  ): Observable<ReservationResponse> {
    return this.http.put<ReservationResponse>(
      `${this.baseUrl}/${reservationId}/pricing`,
      request,
      { headers: this.guestProofHeaders(customerEmailProof) },
    );
  }

  cancelReservation(reservationId: string, customerEmailProof?: string): Observable<void> {
    return this.http.post<void>(
      `${this.baseUrl}/${reservationId}/cancel`,
      {},
      { headers: this.guestProofHeaders(customerEmailProof) },
    );
  }

  private guestProofHeaders(customerEmailProof?: string): HttpHeaders | undefined {
    const normalizedEmail = customerEmailProof?.trim();
    return normalizedEmail ? new HttpHeaders({ 'X-Customer-Email': normalizedEmail }) : undefined;
  }

  private rememberGuestProof(reservationId: string, customerEmail?: string): void {
    const normalizedEmail = customerEmail?.trim();
    if (!normalizedEmail) {
      return;
    }
    try {
      globalThis.sessionStorage.setItem(this.guestProofStoragePrefix + reservationId, normalizedEmail);
    } catch {
      // Session storage can be unavailable in privacy-restricted browser contexts.
    }
  }
}
