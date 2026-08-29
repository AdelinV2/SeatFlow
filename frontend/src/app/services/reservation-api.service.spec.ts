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
      eventId: 'ev-101',
      customerEmail: 'guest@example.com',
      seatIds: ['s-1', 's-2'],
      seatPrices: [50, 75],
      idempotencyKey: 'idem-test-123',
    };

    const mockResponse: ReservationResponse = {
      id: 'res-999',
      eventId: 'ev-101',
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

  it('should cancel a reservation hold with optional guest proof header', () => {
    service.cancelReservation('res-999', 'guest@example.com').subscribe();

    const req = httpMock.expectOne('/api/reservations/res-999/cancel');
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('X-Customer-Email')).toBe('guest@example.com');
    req.flush(null);
  });
});
