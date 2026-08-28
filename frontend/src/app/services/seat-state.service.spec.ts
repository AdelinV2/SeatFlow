import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Seat, SeatAvailabilityResponse } from '../models/seat.model';
import { SeatStateService } from './seat-state.service';

describe('SeatStateService', () => {
  let service: SeatStateService;
  let httpMock: HttpTestingController;

  const createSeat = (id: string, status: Seat['status'] = 'AVAILABLE'): Seat => ({
    id,
    sectionId: 'sec-1',
    sectionName: 'Orchestra',
    rowLabel: 'A',
    seatNumber: 1,
    gridX: 0,
    gridY: 0,
    price: 50,
    currency: 'USD',
    status,
    isActive: true,
  });

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        SeatStateService,
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });

    service = TestBed.inject(SeatStateService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should initialize and filter available, held, and sold seats', () => {
    const seats: Seat[] = [
      createSeat('s-1', 'AVAILABLE'),
      createSeat('s-2', 'HELD'),
      createSeat('s-3', 'SOLD'),
      createSeat('s-4', 'RESERVED'),
    ];

    service.setSeats(seats, 'ev-100');

    expect(service.currentEventId()).toBe('ev-100');
    expect(service.seats().length).toBe(4);
    expect(service.availableSeats().map((s) => s.id)).toEqual(['s-1']);
    expect(service.heldSeats().map((s) => s.id)).toEqual(['s-2']);
    expect(service.soldSeats().map((s) => s.id)).toEqual(['s-3', 's-4']);
  });

  it('should update individual seat status', () => {
    service.setSeats([createSeat('s-1', 'AVAILABLE')], 'ev-100');
    service.updateSeatStatus('s-1', 'HELD');

    expect(service.seats()[0].status).toBe('HELD');
  });

  it('should reconcile availability from backend and trigger conflict callback for held selected seat', () => {
    service.setSeats(
      [createSeat('s-1', 'AVAILABLE'), createSeat('s-2', 'AVAILABLE')],
      'ev-100',
    );

    const onConflictSpy = jasmine.createSpy('onConflict');
    const selectedSeatIds = new Set(['s-1']);

    service.reconcileAvailability('ev-100', selectedSeatIds, onConflictSpy);

    expect(service.isLoading()).toBeTrue();

    const mockResponse: SeatAvailabilityResponse = {
      eventId: 'ev-100',
      seatStatuses: [
        { seatId: 's-1', status: 'HELD' },
        { seatId: 's-2', status: 'AVAILABLE' },
      ],
    };

    const req = httpMock.expectOne('/api/reservations/events/ev-100/availability');
    expect(req.request.method).toBe('GET');
    req.flush(mockResponse);

    expect(service.isLoading()).toBeFalse();
    expect(service.seats()[0].status).toBe('HELD');
    expect(onConflictSpy).toHaveBeenCalledWith('s-1');
  });
});
