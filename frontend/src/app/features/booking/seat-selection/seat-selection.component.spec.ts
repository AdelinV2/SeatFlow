import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';
import { UserContextService } from '../../../core/auth/user-context.service';
import { EventSeatMapResponse, Seat } from '../../../models/seat.model';
import { EventApiService } from '../../../services/event-api.service';
import {
  CreateReservationRequest,
  ReservationApiService,
  ReservationResponse,
} from '../../../services/reservation-api.service';
import { SeatStateService } from '../../../services/seat-state.service';
import { ConnectionStatus, WebSocketService } from '../../../services/websocket.service';
import { DateFormatPipe } from '../../../shared/pipes/date-format.pipe';
import { SeatSelectionComponent } from './seat-selection.component';

describe('SeatSelectionComponent', () => {
  let fixture: ComponentFixture<SeatSelectionComponent>;
  let component: SeatSelectionComponent;
  let router: Router;
  let seatSignal: ReturnType<typeof signal<Seat[]>>;
  let reservationApi: jasmine.SpyObj<ReservationApiService>;
  let webSocketService: {
    connectionStatus: ReturnType<typeof signal<ConnectionStatus>>;
    connectForEvent: jasmine.Spy;
    disconnect: jasmine.Spy;
  };
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  const response: EventSeatMapResponse = {
    eventId: 'event-1',
    venueId: 'venue-1',
    eventTitle: 'Live at SeatFlow',
    eventDate: '2026-10-10T18:00:00Z',
    venueName: 'Main Hall',
    venueCapacity: 100,
    totalConfiguredSeats: 11,
    sections: [
      {
        sectionId: 'section-1',
        name: 'Orchestra',
        rowCount: 1,
        colCount: 11,
        seats: Array.from({ length: 11 }, (_, index) => ({
          seatId: `seat-${index + 1}`,
          rowLabel: 'A',
          seatNumber: index + 1,
          gridX: index,
          gridY: 0,
          isActive: true,
        })),
        pricingTiers: [
          {
            id: 'tier-1',
            sectionId: 'section-1',
            categoryName: 'Standard',
            price: 42.5,
            currency: 'EUR',
          },
        ],
      },
    ],
  };

  beforeEach(async () => {
    seatSignal = signal<Seat[]>([]);
    const eventApi = jasmine.createSpyObj<EventApiService>('EventApiService', ['getEventSeatMap']);
    eventApi.getEventSeatMap.and.returnValue(of(response));
    reservationApi = jasmine.createSpyObj<ReservationApiService>('ReservationApiService', [
      'createReservation',
    ]);
    const reservation: ReservationResponse = {
      id: 'reservation-1',
      eventId: 'event-1',
      status: 'PENDING',
      expiresAt: '2026-10-10T18:15:00Z',
      totalAmount: 85,
      seats: [],
    };
    reservationApi.createReservation.and.returnValue(of(reservation));
    webSocketService = {
      connectionStatus: signal<ConnectionStatus>('DISCONNECTED'),
      connectForEvent: jasmine.createSpy('connectForEvent'),
      disconnect: jasmine.createSpy('disconnect'),
    };
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);

    const seatStateService = {
      seats: seatSignal,
      setSeats: jasmine
        .createSpy('setSeats')
        .and.callFake((seats: Seat[]) => seatSignal.set(seats)),
    };
    const userContext = {
      isAuthenticated: signal(true),
      userEmail: signal('customer@example.com'),
    };

    await TestBed.configureTestingModule({
      imports: [SeatSelectionComponent],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { paramMap: of(convertToParamMap({ id: 'event-1' })) },
        },
        { provide: EventApiService, useValue: eventApi },
        { provide: ReservationApiService, useValue: reservationApi },
        { provide: SeatStateService, useValue: seatStateService },
        { provide: WebSocketService, useValue: webSocketService },
        { provide: UserContextService, useValue: userContext },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    })
      .overrideComponent(SeatSelectionComponent, { set: { template: '', imports: [] } })
      .compileComponents();

    fixture = TestBed.createComponent(SeatSelectionComponent);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    fixture.detectChanges();
  });

  afterEach(() => fixture.destroy());

  it('loads and flattens the seat map before connecting live updates', () => {
    expect(component.seats().length).toBe(11);
    expect(component.seats()[0]).toEqual(
      jasmine.objectContaining({ price: 42.5, currency: 'EUR', status: 'AVAILABLE' }),
    );
    expect(webSocketService.connectForEvent).toHaveBeenCalledWith(
      'event-1',
      jasmine.any(Function),
      jasmine.any(Function),
    );
  });

  it('rejects an eleventh seat and computes the selected total', () => {
    component
      .seats()
      .slice(0, 10)
      .forEach((seat) => component.toggleSeat(seat));

    expect(component.selectedSeats().length).toBe(10);
    expect(component.selectedSeats().reduce((sum, seat) => sum + seat.price, 0)).toBe(425);

    component.toggleSeat(component.seats()[10]!);
    expect(component.selectedSeats().length).toBe(10);
    expect(snackBar.open).toHaveBeenCalledWith(
      'Maximum 10 seats allowed per reservation.',
      'Close',
      jasmine.objectContaining({ panelClass: 'snack-warning' }),
    );
  });

  it('ejects a selected seat when a live conflict arrives', () => {
    const seat = component.seats()[0]!;
    component.toggleSeat(seat);
    seatSignal.update((seats) =>
      seats.map((current) => (current.id === seat.id ? { ...current, status: 'HELD' } : current)),
    );
    const conflictCallback = webSocketService.connectForEvent.calls.mostRecent().args[1] as (
      seatId: string,
    ) => void;

    conflictCallback(seat.id);

    expect(component.selectedSeatIds().has(seat.id)).toBeFalse();
    expect(snackBar.open).toHaveBeenCalledWith(
      'Seat A-1 was just reserved by another user.',
      'Close',
      jasmine.objectContaining({ politeness: 'assertive' }),
    );
  });

  it('posts aligned seat prices with one idempotency key and navigates to checkout', () => {
    const navigate = spyOn(router, 'navigate').and.resolveTo(true);
    const randomUuid = spyOn(globalThis.crypto, 'randomUUID').and.returnValue(
      '123e4567-e89b-42d3-a456-426614174000',
    );
    component.toggleSeat(component.seats()[0]!);
    component.toggleSeat(component.seats()[1]!);

    component.createHold();

    const request = reservationApi.createReservation.calls.mostRecent()
      .args[0] as CreateReservationRequest;
    expect(request).toEqual({
      eventId: 'event-1',
      customerEmail: 'customer@example.com',
      seatIds: ['seat-1', 'seat-2'],
      seatPrices: [42.5, 42.5],
      idempotencyKey: '123e4567-e89b-42d3-a456-426614174000',
    });
    expect(randomUuid).toHaveBeenCalledTimes(1);
    expect(navigate).toHaveBeenCalledWith(['/checkout', 'reservation-1']);
  });

  it('disconnects the root-scoped live service when destroyed', () => {
    const disconnectCount = webSocketService.disconnect.calls.count();
    fixture.destroy();
    expect(webSocketService.disconnect.calls.count()).toBe(disconnectCount + 1);
  });

  it('formats event date with sfDate full variant in 24-hour format matching event detail', () => {
    const pipe = new DateFormatPipe();
    const formatted = pipe.transform(response.eventDate, 'full');
    expect(formatted).toContain('•');
    expect(formatted).not.toMatch(/AM|PM/);
  });
});
