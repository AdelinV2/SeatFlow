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

  const seats: Seat[] = [
    {
      id: 'seat-1',
      sectionId: 'section-1',
      sectionName: 'Orchestra',
      rowLabel: 'A',
      seatNumber: 1,
      gridX: 0,
      gridY: 0,
      price: 50,
      currency: 'USD',
      status: 'AVAILABLE',
      isActive: true,
    },
    {
      id: 'seat-2',
      sectionId: 'section-1',
      rowLabel: 'A',
      seatNumber: 2,
      gridX: 1,
      gridY: 0,
      price: 50,
      currency: 'USD',
      status: 'HELD',
      isActive: true,
    },
    {
      id: 'seat-3',
      sectionId: 'section-1',
      rowLabel: 'A',
      seatNumber: 3,
      gridX: 2,
      gridY: 0,
      price: 50,
      currency: 'USD',
      status: 'RESERVED',
      isActive: true,
    },
    {
      id: 'seat-4',
      sectionId: 'section-1',
      rowLabel: 'A',
      seatNumber: 4,
      gridX: 3,
      gridY: 0,
      price: 50,
      currency: 'USD',
      status: 'DISABLED',
      isActive: false,
    },
  ];

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

  it('should expose the initial state', () => {
    expect(service.seats()).toEqual([]);
    expect(service.currentEventId()).toBeNull();
    expect(service.currentSessionId()).toBeNull();
    expect(service.hasSession()).toBeFalse();
    expect(service.isLoading()).toBeFalse();
  });

  it('should set seats with session and derive available, held, and sold collections', () => {
    service.setSeats(seats, 'event-1', 'session-1');

    expect(service.currentEventId()).toBe('event-1');
    expect(service.currentSessionId()).toBe('session-1');
    expect(service.hasSession()).toBeTrue();
    expect(service.availableSeats().map((seat) => seat.id)).toEqual(['seat-1']);
    expect(service.heldSeats().map((seat) => seat.id)).toEqual(['seat-2']);
    expect(service.soldSeats().map((seat) => seat.id)).toEqual(['seat-3']);
  });

  it('should clear seats and reset session', () => {
    service.setSeats(seats, 'event-1', 'session-1');
    expect(service.seats().length).toBe(4);

    service.clearSeats();

    expect(service.seats()).toEqual([]);
    expect(service.currentSessionId()).toBeNull();
    expect(service.hasSession()).toBeFalse();
  });

  it('should update only the matching seat status', () => {
    service.setSeats(seats, 'event-1', 'session-1');

    service.updateSeatStatus('seat-1', 'SOLD');

    expect(service.seats().find((seat) => seat.id === 'seat-1')?.status).toBe('SOLD');
    expect(service.seats().find((seat) => seat.id === 'seat-2')?.status).toBe('HELD');
  });

  it('should reconcile local seats with authoritative session availability', () => {
    service.setSeats(seats, 'event-1', 'session-1');

    service.reconcileAvailability('session-1');

    expect(service.isLoading()).toBeTrue();
    const request = httpMock.expectOne('/api/event-sessions/session-1/seats/availability');
    expect(request.request.method).toBe('GET');
    request.flush({
      eventSessionId: 'session-1',
      eventId: 'event-1',
      seatStatuses: [
        { seatId: 'seat-1', status: 'HELD' },
        { seatId: 'seat-2', status: 'AVAILABLE' },
        { seatId: 'unknown-seat', status: 'SOLD' },
      ],
    } satisfies SeatAvailabilityResponse);

    expect(service.seats().find((seat) => seat.id === 'seat-1')?.status).toBe('HELD');
    expect(service.seats().find((seat) => seat.id === 'seat-2')?.status).toBe('AVAILABLE');
    expect(service.seats().find((seat) => seat.id === 'seat-3')?.status).toBe('AVAILABLE');
    expect(service.seats().find((seat) => seat.id === 'seat-4')?.status).toBe('DISABLED');
    expect(service.isLoading()).toBeFalse();
  });

  it('should ignore a stale reconciliation response that arrives after session switch', () => {
    service.setSeats(seats, 'event-1', 'session-A');
    service.reconcileAvailability('session-A');

    const reqA = httpMock.expectOne('/api/event-sessions/session-A/seats/availability');

    // Switch to session-B
    service.setSeats(seats, 'event-1', 'session-B');
    service.reconcileAvailability('session-B');

    const reqB = httpMock.expectOne('/api/event-sessions/session-B/seats/availability');

    // Late response for session-A arrives
    reqA.flush({
      eventSessionId: 'session-A',
      seatStatuses: [{ seatId: 'seat-1', status: 'SOLD' }],
    } satisfies SeatAvailabilityResponse);

    // Seat-1 should not be changed by session-A's late response
    expect(service.seats().find((seat) => seat.id === 'seat-1')?.status).toBe('AVAILABLE');

    // Response for session-B arrives
    reqB.flush({
      eventSessionId: 'session-B',
      seatStatuses: [{ seatId: 'seat-1', status: 'HELD' }],
    } satisfies SeatAvailabilityResponse);

    expect(service.seats().find((seat) => seat.id === 'seat-1')?.status).toBe('HELD');
    expect(service.isLoading()).toBeFalse();
  });

  it('should ignore a stale reconciliation response that arrives after a newer response', () => {
    service.setSeats(seats, 'event-1', 'session-1');

    service.reconcileAvailability('session-1');
    service.reconcileAvailability('session-1');
    const requests = httpMock.match('/api/event-sessions/session-1/seats/availability');

    requests[1].flush({
      eventSessionId: 'session-1',
      seatStatuses: [{ seatId: 'seat-1', status: 'HELD' }],
    } satisfies SeatAvailabilityResponse);
    requests[0].flush({
      eventSessionId: 'session-1',
      seatStatuses: [{ seatId: 'seat-1', status: 'SOLD' }],
    } satisfies SeatAvailabilityResponse);

    expect(service.seats().find((seat) => seat.id === 'seat-1')?.status).toBe('HELD');
    expect(service.isLoading()).toBeFalse();
  });

  it('should preserve a live update received while reconciliation is in flight', () => {
    service.setSeats(seats, 'event-1', 'session-1');

    service.reconcileAvailability('session-1');
    const request = httpMock.expectOne('/api/event-sessions/session-1/seats/availability');
    service.updateSeatStatus('seat-1', 'SOLD');
    request.flush({
      eventSessionId: 'session-1',
      seatStatuses: [{ seatId: 'seat-1', status: 'HELD' }],
    } satisfies SeatAvailabilityResponse);

    expect(service.seats().find((seat) => seat.id === 'seat-1')?.status).toBe('SOLD');
  });

  it('should report conflict when an authoritative update takes a selected seat', () => {
    const onConflict = jasmine.createSpy('onConflict');
    service.setSeats(seats, 'event-1', 'session-1');

    service.reconcileAvailability('session-1', new Set(['seat-1']), onConflict);
    httpMock.expectOne('/api/event-sessions/session-1/seats/availability').flush({
      eventSessionId: 'session-1',
      seatStatuses: [{ seatId: 'seat-1', status: 'HELD' }],
    } satisfies SeatAvailabilityResponse);

    expect(onConflict).toHaveBeenCalledOnceWith('seat-1');
  });

  it('should not report a conflict when a selected seat remains available', () => {
    const onConflict = jasmine.createSpy('onConflict');
    service.setSeats(seats, 'event-1', 'session-1');

    service.reconcileAvailability('session-1', new Set(['seat-1']), onConflict);
    httpMock.expectOne('/api/event-sessions/session-1/seats/availability').flush({
      eventSessionId: 'session-1',
      seatStatuses: [{ seatId: 'seat-1', status: 'AVAILABLE' }],
    } satisfies SeatAvailabilityResponse);

    expect(onConflict).not.toHaveBeenCalled();
  });

  it('should clear loading state and preserve seats when reconciliation fails', () => {
    const consoleError = spyOn(console, 'error');
    service.setSeats(seats, 'event-1', 'session-1');

    service.reconcileAvailability('session-1');
    httpMock
      .expectOne('/api/event-sessions/session-1/seats/availability')
      .flush('Unavailable', { status: 503, statusText: 'Service Unavailable' });

    expect(service.seats()).toEqual(seats);
    expect(service.isLoading()).toBeFalse();
    expect(consoleError).toHaveBeenCalled();
  });

  it('should enter the non-bookable availability-error state and notify onError when reconciliation fails (REV-002 FIX-2)', () => {
    spyOn(console, 'error');
    const onError = jasmine.createSpy('onError');
    const onSettled = jasmine.createSpy('onSettled');
    service.setSeats(seats, 'event-1', 'session-1');
    expect(service.availabilityError()).toBeFalse();

    service.reconcileAvailability('session-1', undefined, undefined, onSettled, onError);
    httpMock
      .expectOne('/api/event-sessions/session-1/seats/availability')
      .flush('Unavailable', { status: 503, statusText: 'Service Unavailable' });

    expect(service.availabilityError()).toBeTrue();
    expect(onError).toHaveBeenCalledTimes(1);
    expect(onSettled).toHaveBeenCalledTimes(1);
    expect(service.isLoading()).toBeFalse();
    // Non-authoritative statuses are preserved, not promoted to bookable.
    expect(service.seats()).toEqual(seats);
  });

  it('should clear the availability-error state on retry start, on success, and on setSeats (REV-002 FIX-2)', () => {
    spyOn(console, 'error');
    service.setSeats(seats, 'event-1', 'session-1');

    service.reconcileAvailability('session-1');
    httpMock
      .expectOne('/api/event-sessions/session-1/seats/availability')
      .flush('Unavailable', { status: 503, statusText: 'Service Unavailable' });
    expect(service.availabilityError()).toBeTrue();

    // Retry clears the flag at attempt start, before the response arrives.
    service.reconcileAvailability('session-1');
    expect(service.availabilityError()).toBeFalse();
    httpMock.expectOne('/api/event-sessions/session-1/seats/availability').flush({
      eventSessionId: 'session-1',
      seatStatuses: [{ seatId: 'seat-1', status: 'HELD' }],
    } satisfies SeatAvailabilityResponse);
    expect(service.availabilityError()).toBeFalse();
    expect(service.seats().find((seat) => seat.id === 'seat-1')?.status).toBe('HELD');

    // A fresh failure re-enters the error state; switching seats clears it.
    service.reconcileAvailability('session-1');
    httpMock
      .expectOne('/api/event-sessions/session-1/seats/availability')
      .flush('Unavailable', { status: 503, statusText: 'Service Unavailable' });
    expect(service.availabilityError()).toBeTrue();
    service.setSeats(seats, 'event-1', 'session-2');
    expect(service.availabilityError()).toBeFalse();
  });

  it('should cancel active loading and increment request ID on ngOnDestroy', () => {
    service.setSeats(seats, 'event-1', 'session-1');
    service.reconcileAvailability('session-1');
    expect(service.isLoading()).toBeTrue();

    service.ngOnDestroy();

    expect(service.isLoading()).toBeFalse();
    // Flush after destroy should be ignored
    httpMock.expectOne('/api/event-sessions/session-1/seats/availability').flush({
      eventSessionId: 'session-1',
      seatStatuses: [{ seatId: 'seat-1', status: 'HELD' }],
    } satisfies SeatAvailabilityResponse);

    expect(service.seats().find((s) => s.id === 'seat-1')?.status).toBe('AVAILABLE');
  });
});
