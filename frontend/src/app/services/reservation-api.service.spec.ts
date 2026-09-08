import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import {
  CreateReservationRequest,
  ReservationApiService,
  ReservationResponse,
  UpdateReservationPricingRequest,
} from './reservation-api.service';

describe('ReservationApiService', () => {
  let service: ReservationApiService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [ReservationApiService, provideHttpClient(), provideHttpClientTesting()],
    });

    service = TestBed.inject(ReservationApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should create a reservation hold via POST /api/reservations', () => {
    const request: CreateReservationRequest = {
      eventSessionId: 'sess-101',
      customerEmail: 'guest@example.com',
      seatIds: ['s-1', 's-2'],
      seatPrices: [50, 75],
      idempotencyKey: 'idem-test-123',
    };

    const mockResponse: ReservationResponse = {
      id: 'res-999',
      eventId: 'ev-101',
      eventSessionId: 'sess-101',
      customerEmail: 'guest@example.com',
      status: 'PENDING',
      expiresAt: '2026-10-10T18:15:00Z',
      totalAmount: 125,
      seats: [
        { seatId: 's-1', rowNumber: 'A', seatNumber: 1, price: 50 },
        { seatId: 's-2', rowNumber: 'A', seatNumber: 2, price: 75 },
      ],
    };

    service.createReservation(request).subscribe((res) => {
      expect(res.id).toBe('res-999');
      expect(res.eventSessionId).toBe('sess-101');
      expect(res.status).toBe('PENDING');
      expect(res.totalAmount).toBe(125);
      expect(res.seats.length).toBe(2);
    });

    const req = httpMock.expectOne('/api/reservations');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(request);
    req.flush(mockResponse);
  });

  it('should get a reservation by ID with optional guest proof header', () => {
    const mockResponse: ReservationResponse = {
      id: 'res-999',
      eventId: 'ev-101',
      customerEmail: 'guest@example.com',
      status: 'PENDING',
      expiresAt: '2026-10-10T18:15:00Z',
      totalAmount: 125,
      seats: [],
    };

    service.getReservation('res-999', 'guest@example.com').subscribe((res) => {
      expect(res.id).toBe('res-999');
    });

    const req = httpMock.expectOne('/api/reservations/res-999');
    expect(req.request.method).toBe('GET');
    expect(req.request.headers.get('X-Customer-Email')).toBe('guest@example.com');
    req.flush(mockResponse);
  });

  it('should get seat availability via GET /api/event-sessions/:id/seats/availability', () => {
    service.getSeatAvailability('sess-101').subscribe((res) => {
      expect(res.eventSessionId).toBe('sess-101');
      expect(res.seatStatuses?.length).toBe(1);
    });

    const req = httpMock.expectOne('/api/event-sessions/sess-101/seats/availability');
    expect(req.request.method).toBe('GET');
    req.flush({
      eventSessionId: 'sess-101',
      seatStatuses: [{ seatId: 's-1', status: 'AVAILABLE' }],
    });
  });

  it('should cancel a reservation hold with optional guest proof header', () => {
    service.cancelReservation('res-999', 'guest@example.com').subscribe();

    const req = httpMock.expectOne('/api/reservations/res-999/cancel');
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('X-Customer-Email')).toBe('guest@example.com');
    req.flush(null);
  });

  it('should persist the guest email proof only for guest checkout (P16-002)', () => {
    sessionStorage.clear();
    const request: CreateReservationRequest = {
      eventSessionId: 'sess-101',
      customerEmail: 'guest@example.com',
      seatIds: ['s-1'],
      seatPrices: [50],
      idempotencyKey: 'idem-guest-1',
    };
    const mockResponse: ReservationResponse = {
      id: 'res-guest-1',
      eventId: 'ev-101',
      eventSessionId: 'sess-101',
      customerEmail: 'guest@example.com',
      status: 'PENDING',
      expiresAt: '2026-10-10T18:15:00Z',
      totalAmount: 50,
      seats: [{ seatId: 's-1', price: 50 }],
    };

    service.createReservation(request).subscribe();
    httpMock.expectOne('/api/reservations').flush(mockResponse);
    expect(service.getStoredCustomerEmailProof('res-guest-1')).toBe('guest@example.com');

    service.clearStoredCustomerEmailProof('res-guest-1');
    expect(service.getStoredCustomerEmailProof('res-guest-1')).toBeUndefined();
    sessionStorage.clear();
  });

  it('should not create a guest proof entry for authenticated checkout (P16-002)', () => {
    sessionStorage.clear();
    const request: CreateReservationRequest = {
      eventSessionId: 'sess-101',
      customerEmail: 'member@example.com',
      seatIds: ['s-1'],
      seatPrices: [50],
      idempotencyKey: 'idem-auth-1',
    };
    const mockResponse: ReservationResponse = {
      id: 'res-auth-1',
      eventId: 'ev-101',
      eventSessionId: 'sess-101',
      customerEmail: 'member@example.com',
      status: 'PENDING',
      expiresAt: '2026-10-10T18:15:00Z',
      totalAmount: 50,
      seats: [{ seatId: 's-1', price: 50 }],
    };

    service.createReservation(request, { persistGuestProof: false }).subscribe();
    httpMock.expectOne('/api/reservations').flush(mockResponse);
    expect(service.getStoredCustomerEmailProof('res-auth-1')).toBeUndefined();
    sessionStorage.clear();
  });

  it('should update ticket types with optional guest proof header', () => {
    const request: UpdateReservationPricingRequest = {
      seats: [{ seatId: 's-1', pricingTierId: 'tier-student' }],
    };

    service.updateReservationPricing('res-999', request, 'guest@example.com').subscribe((res) => {
      expect(res.id).toBe('res-999');
    });

    const req = httpMock.expectOne('/api/reservations/res-999/pricing');
    expect(req.request.method).toBe('PUT');
    expect(req.request.headers.get('X-Customer-Email')).toBe('guest@example.com');
    expect(req.request.body).toEqual(request);
    req.flush({
      id: 'res-999',
      eventId: 'ev-101',
      customerEmail: 'guest@example.com',
      status: 'PENDING',
      expiresAt: '2026-10-10T18:15:00Z',
      totalAmount: 35,
      seats: [{ seatId: 's-1', pricingTierId: 'tier-student', price: 35 }],
    });
  });
});

