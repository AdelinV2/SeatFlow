import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Seat, SeatAvailabilityResponse } from '../models/seat.model';
import { SeatStateService } from './seat-state.service';

describe('SeatStateService', () => {
  let service: SeatStateService;
  let httpMock: HttpTestingController;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

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
      status: 'DISABLED',
      isActive: false,
    },
  ];

  beforeEach(() => {
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);

    TestBed.configureTestingModule({
      providers: [
        SeatStateService,
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: snackBar },
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
    expect(service.isLoading()).toBeFalse();
  });

  it('should set seats and derive available, held, and sold collections', () => {
    service.setSeats(seats, 'event-1');

    expect(service.currentEventId()).toBe('event-1');
    expect(service.availableSeats().map((seat) => seat.id)).toEqual(['seat-1']);
    expect(service.heldSeats().map((seat) => seat.id)).toEqual(['seat-2']);
    expect(service.soldSeats().map((seat) => seat.id)).toEqual(['seat-3']);
  });

  it('should update only the matching seat status', () => {
    service.setSeats(seats, 'event-1');

    service.updateSeatStatus('seat-1', 'SOLD');

    expect(service.seats().find((seat) => seat.id === 'seat-1')?.status).toBe('SOLD');
    expect(service.seats().find((seat) => seat.id === 'seat-2')?.status).toBe('HELD');
  });

  it('should reconcile local seats with authoritative availability', () => {
    service.setSeats(seats, 'event-1');

    service.reconcileAvailability('event-1');

    expect(service.isLoading()).toBeTrue();
    const request = httpMock.expectOne('/api/reservations/events/event-1/availability');
    expect(request.request.method).toBe('GET');
    request.flush({
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

  it('should ignore a stale reconciliation response that arrives after a newer response', () => {
    service.setSeats(seats, 'event-1');

    service.reconcileAvailability('event-1');
    service.reconcileAvailability('event-1');
    const requests = httpMock.match('/api/reservations/events/event-1/availability');

    requests[1].flush({
      eventId: 'event-1',
      seatStatuses: [{ seatId: 'seat-1', status: 'HELD' }],
    } satisfies SeatAvailabilityResponse);
    requests[0].flush({
      eventId: 'event-1',
      seatStatuses: [{ seatId: 'seat-1', status: 'SOLD' }],
    } satisfies SeatAvailabilityResponse);

    expect(service.seats().find((seat) => seat.id === 'seat-1')?.status).toBe('HELD');
    expect(service.isLoading()).toBeFalse();
  });

  it('should preserve a live update received while reconciliation is in flight', () => {
    service.setSeats(seats, 'event-1');

    service.reconcileAvailability('event-1');
    const request = httpMock.expectOne('/api/reservations/events/event-1/availability');
    service.updateSeatStatus('seat-1', 'SOLD');
    request.flush({
      eventId: 'event-1',
      seatStatuses: [{ seatId: 'seat-1', status: 'HELD' }],
    } satisfies SeatAvailabilityResponse);

    expect(service.seats().find((seat) => seat.id === 'seat-1')?.status).toBe('SOLD');
  });

  it('should report and notify when an authoritative update takes a selected seat', () => {
    const onConflict = jasmine.createSpy('onConflict');
    service.setSeats(seats, 'event-1');

    service.reconcileAvailability('event-1', new Set(['seat-1']), onConflict);
    httpMock.expectOne('/api/reservations/events/event-1/availability').flush({
      eventId: 'event-1',
      seatStatuses: [{ seatId: 'seat-1', status: 'HELD' }],
    } satisfies SeatAvailabilityResponse);

    expect(onConflict).toHaveBeenCalledOnceWith('seat-1');
    expect(snackBar.open).toHaveBeenCalledOnceWith(
      'Seat A-1 was just reserved by another user.',
      'Close',
      { duration: 5000, panelClass: 'snack-warning' },
    );
  });

  it('should not report a conflict when a selected seat remains available', () => {
    const onConflict = jasmine.createSpy('onConflict');
    service.setSeats(seats, 'event-1');

    service.reconcileAvailability('event-1', new Set(['seat-1']), onConflict);
    httpMock.expectOne('/api/reservations/events/event-1/availability').flush({
      eventId: 'event-1',
      seatStatuses: [{ seatId: 'seat-1', status: 'AVAILABLE' }],
    } satisfies SeatAvailabilityResponse);

    expect(onConflict).not.toHaveBeenCalled();
    expect(snackBar.open).not.toHaveBeenCalled();
  });

  it('should clear loading state and preserve seats when reconciliation fails', () => {
    const consoleError = spyOn(console, 'error');
    service.setSeats(seats, 'event-1');

    service.reconcileAvailability('event-1');
    httpMock
      .expectOne('/api/reservations/events/event-1/availability')
      .flush('Unavailable', { status: 503, statusText: 'Service Unavailable' });

    expect(service.seats()).toEqual(seats);
    expect(service.isLoading()).toBeFalse();
    expect(consoleError).toHaveBeenCalled();
  });

  it('should format the conflict snackbar cleanly when rowLabel is empty', () => {
    const seatsWithoutRow: Seat[] = [
      {
        id: 'seat-norow',
        sectionId: 'section-1',
        rowLabel: '',
        seatNumber: 42,
        gridX: 0,
        gridY: 0,
        price: 50,
        status: 'AVAILABLE',
        isActive: true,
      },
    ];
    service.setSeats(seatsWithoutRow, 'event-1');

    service.reconcileAvailability('event-1', new Set(['seat-norow']), () => {});
    httpMock.expectOne('/api/reservations/events/event-1/availability').flush({
      eventId: 'event-1',
      seatStatuses: [{ seatId: 'seat-norow', status: 'HELD' }],
    } satisfies SeatAvailabilityResponse);

    expect(snackBar.open).toHaveBeenCalledWith(
      'Seat #42 was just reserved by another user.',
      'Close',
      jasmine.any(Object),
    );
  });

  it('should cancel active loading and increment request ID on ngOnDestroy', () => {
    service.setSeats(seats, 'event-1');
    service.reconcileAvailability('event-1');
    expect(service.isLoading()).toBeTrue();

    service.ngOnDestroy();

    expect(service.isLoading()).toBeFalse();
    // Flush after destroy should be ignored
    httpMock.expectOne('/api/reservations/events/event-1/availability').flush({
      eventId: 'event-1',
      seatStatuses: [{ seatId: 'seat-1', status: 'HELD' }],
    } satisfies SeatAvailabilityResponse);

    expect(service.seats().find((s) => s.id === 'seat-1')?.status).toBe('AVAILABLE');
  });
});
