import { signal, WritableSignal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { of, Subject } from 'rxjs';
import { UserContextService } from '../../../core/auth/user-context.service';
import { EventDetail, EventSession } from '../../../models/event.model';
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
  let seatLoading: WritableSignal<boolean>;
  let seatAvailabilityError: WritableSignal<boolean>;
  let pendingAvailabilitySettlements: (() => void)[];
  let pendingAvailabilityErrors: (() => void)[];
  let reservationApi: jasmine.SpyObj<ReservationApiService>;
  let eventApi: jasmine.SpyObj<EventApiService>;
  let seatState: {
    seats: ReturnType<WritableSignal<Seat[]>['asReadonly']>;
    isLoading: ReturnType<WritableSignal<boolean>['asReadonly']>;
    availabilityError: ReturnType<WritableSignal<boolean>['asReadonly']>;
    setSeats: jasmine.Spy;
    reconcileAvailability: jasmine.Spy;
    clearSeats: jasmine.Spy;
  };
  let webSocketService: {
    connectionStatus: ReturnType<typeof signal<ConnectionStatus>>;
    connectForSession: jasmine.Spy;
    disconnect: jasmine.Spy;
  };
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  const mockSessions: EventSession[] = [
    {
      id: 'session-1',
      eventId: 'event-1',
      startsAt: '2026-10-10T18:00:00Z',
      endsAt: '2026-10-10T20:00:00Z',
      status: 'SCHEDULED',
      timezone: 'Europe/Bucharest',
    },
    {
      id: 'session-2',
      eventId: 'event-1',
      startsAt: '2026-10-11T18:00:00Z',
      endsAt: '2026-10-11T20:00:00Z',
      status: 'SCHEDULED',
      timezone: 'Europe/Bucharest',
    },
  ];

  const mockEventDetail: EventDetail = {
    id: 'event-1',
    venueId: 'venue-1',
    title: 'Live at SeatFlow',
    description: 'A great show',
    category: 'CONCERT',
    bannerUrl: 'https://example.com/concert.jpg',
    status: 'PUBLISHED',
    pricingTiers: [
      {
        id: 'tier-1',
        sectionId: 'section-1',
        categoryName: 'Standard',
        price: 42.5,
        currency: 'EUR',
      },
    ],
    sessions: mockSessions,
    createdAt: '2026-08-01T10:00:00Z',
  };

  const response: EventSeatMapResponse = {
    eventId: 'event-1',
    venueId: 'venue-1',
    eventTitle: 'Live at SeatFlow',
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
    seatLoading = signal(false);
    seatAvailabilityError = signal(false);
    pendingAvailabilitySettlements = [];
    pendingAvailabilityErrors = [];
    eventApi = jasmine.createSpyObj<EventApiService>('EventApiService', [
      'getEventById',
      'getEventSessions',
      'getEventSeatMap',
    ]);
    eventApi.getEventById.and.returnValue(of(mockEventDetail));
    eventApi.getEventSessions.and.returnValue(of(mockSessions));
    eventApi.getEventSeatMap.and.returnValue(of(response));

    reservationApi = jasmine.createSpyObj<ReservationApiService>('ReservationApiService', [
      'createReservation',
      'cancelReservation',
      'clearStoredCustomerEmailProof',
    ]);
    const reservation: ReservationResponse = {
      id: 'reservation-1',
      eventSessionId: 'session-1',
      eventId: 'event-1',
      status: 'PENDING',
      expiresAt: '2026-10-10T18:15:00Z',
      totalAmount: 85,
      seats: [],
    };
    reservationApi.createReservation.and.returnValue(of(reservation));
    reservationApi.cancelReservation.and.returnValue(of(undefined));

    webSocketService = {
      connectionStatus: signal<ConnectionStatus>('DISCONNECTED'),
      connectForSession: jasmine.createSpy('connectForSession'),
      disconnect: jasmine.createSpy('disconnect'),
    };

    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);

    const seatStateValue = {
      seats: seatSignal.asReadonly(),
      isLoading: seatLoading.asReadonly(),
      availabilityError: seatAvailabilityError.asReadonly(),
      setSeats: jasmine.createSpy('setSeats').and.callFake((seats: Seat[]) => {
        seatSignal.set(seats);
        seatAvailabilityError.set(false);
      }),
      applySeatStatusUpdate: jasmine.createSpy('applySeatStatusUpdate'),
      // Deterministic deferred availability: marks loading, clears the error
      // flag at attempt start (mirroring SeatStateService), and captures both
      // the onSettled and onError callbacks so tests settle or fail B's REST
      // baseline explicitly.
      reconcileAvailability: jasmine.createSpy('reconcileAvailability').and.callFake((...args: unknown[]) => {
        seatLoading.set(true);
        seatAvailabilityError.set(false);
        const onSettled = args[3] as (() => void) | undefined;
        const onError = args[4] as (() => void) | undefined;
        if (typeof onSettled === 'function') {
          pendingAvailabilitySettlements.push(onSettled);
        }
        if (typeof onError === 'function') {
          pendingAvailabilityErrors.push(onError);
        }
        if (typeof onSettled !== 'function' && typeof onError !== 'function') {
          seatLoading.set(false);
        }
      }),
      clearSeats: jasmine.createSpy('clearSeats').and.callFake(() => {
        seatSignal.set([]);
        seatAvailabilityError.set(false);
      }),
    };

    const userContext = {
      currentUser: signal({
        id: 'user-1',
        email: 'customer@example.com',
        role: 'CUSTOMER',
      }),
      isAuthenticated: signal(true),
      userEmail: signal('customer@example.com'),
    };

    await TestBed.configureTestingModule({
      imports: [SeatSelectionComponent],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: {
            paramMap: of(convertToParamMap({ id: 'event-1' })),
            queryParamMap: of(convertToParamMap({ sessionId: 'session-1' })),
            snapshot: {
              paramMap: convertToParamMap({ id: 'event-1' }),
              queryParamMap: convertToParamMap({ sessionId: 'session-1' }),
            },
          },
        },
        { provide: EventApiService, useValue: eventApi },
        { provide: ReservationApiService, useValue: reservationApi },
        { provide: SeatStateService, useValue: seatStateValue },
        { provide: WebSocketService, useValue: webSocketService },
        { provide: MatSnackBar, useValue: snackBar },
        { provide: UserContextService, useValue: userContext },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(SeatSelectionComponent);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    seatState = seatStateValue;
    fixture.detectChanges();
    // Settle the initial session's authoritative availability baseline so
    // each test starts from SESSION_READY.
    settleAvailability();
    fixture.detectChanges();
  });

  function settleAvailability(): void {
    seatLoading.set(false);
    const pending = [...pendingAvailabilitySettlements];
    pendingAvailabilitySettlements = [];
    pendingAvailabilityErrors = [];
    pending.forEach((settle) => settle());
  }

  // Mirrors SeatStateService failure ordering: error handler (flag + onError)
  // runs before finalize (onSettled, which must NOT re-enable the controls).
  function failAvailability(): void {
    seatAvailabilityError.set(true);
    seatLoading.set(false);
    const errors = [...pendingAvailabilityErrors];
    pendingAvailabilityErrors = [];
    errors.forEach((notify) => notify());
    const pending = [...pendingAvailabilitySettlements];
    pendingAvailabilitySettlements = [];
    pending.forEach((settle) => settle());
  }

  it('loads the event and seat map on initialization', () => {
    expect(eventApi.getEventById).toHaveBeenCalledWith('event-1');
    expect(eventApi.getEventSeatMap).toHaveBeenCalledWith('event-1');
    expect(component.seats().length).toBe(11);
    expect(component.selectedSession()?.id).toBe('session-1');
  });

  it('toggles seat selection up to the max selection limit', () => {
    const seats = component.seats();

    for (let i = 0; i < 10; i++) {
      component.toggleSeat(seats[i]!);
    }
    expect(component.selectedSeatIds().size).toBe(10);

    component.toggleSeat(seats[10]!);
    expect(component.selectedSeatIds().size).toBe(10);
    expect(snackBar.open).toHaveBeenCalledWith(
      'Maximum 10 seats allowed per reservation.',
      'Close',
      jasmine.any(Object),
    );

    component.toggleSeat(seats[0]!);
    expect(component.selectedSeatIds().size).toBe(9);
  });

  it('deselects seat on conflict', () => {
    const seat = component.seats()[0]!;
    component.toggleSeat(seat);
    expect(component.selectedSeatIds().has(seat.id)).toBeTrue();

    const conflictCallback = webSocketService.connectForSession.calls.mostRecent()
      .args[1] as (seatId: string) => void;

    conflictCallback(seat.id);

    expect(component.selectedSeatIds().has(seat.id)).toBeFalse();
    expect(snackBar.open).toHaveBeenCalledWith(
      'Seat A-1 was just reserved by another user.',
      'Close',
      jasmine.objectContaining({ politeness: 'assertive' }),
    );
  });

  it('posts aligned seat prices with one idempotency key and session ID to checkout', () => {
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
      eventSessionId: 'session-1',
      // P12-007: session is the sole booking key; no eventId is sent.
      customerEmail: 'customer@example.com',
      seatIds: ['seat-1', 'seat-2'],
      seatPrices: [42.5, 42.5],
      idempotencyKey: '123e4567-e89b-42d3-a456-426614174000',
    });
    expect(randomUuid).toHaveBeenCalledTimes(1);
    expect(navigate).toHaveBeenCalledWith(['/checkout', 'reservation-1']);
  });

  it('performs atomic session switch clearing selection and updating session', () => {
    component.toggleSeat(component.seats()[0]!);
    expect(component.selectedSeatIds().size).toBe(1);

    component.switchSession(mockSessions[1]);

    expect(component.selectedSession()?.id).toBe('session-2');
    expect(component.selectedSeatIds().size).toBe(0);
    expect(webSocketService.connectForSession).toHaveBeenCalledWith(
      'session-2',
      jasmine.any(Function),
      jasmine.any(Function),
    );
  });

  it('cancels active hold on session switch if reservation hold exists', () => {
    component.currentReservationId.set('hold-123');
    component.switchSession(mockSessions[1]);
    expect(reservationApi.cancelReservation).toHaveBeenCalledWith('hold-123');
    expect(component.selectedSession()?.id).toBe('session-2');
  });

  it('warns when query param sessionId does not match any scheduled session', () => {
    const route = TestBed.inject(ActivatedRoute);
    spyOn(route.snapshot.queryParamMap, 'get').and.returnValue('non-existent-session');

    component.ngOnInit();

    expect(component.sessionWarning()).toContain('The requested showtime is not available for this event');
    expect(component.selectedSession()).toBeNull();
  });

  describe('session-switch races (TASK-P12-006 REV-001/REV-002/REV-003)', () => {
    function startDeferredLoads(): { sessionA: Subject<EventSeatMapResponse>; sessionB: Subject<EventSeatMapResponse> } {
      const sessionA = new Subject<EventSeatMapResponse>();
      const sessionB = new Subject<EventSeatMapResponse>();
      eventApi.getEventSeatMap.and.returnValues(sessionA.asObservable(), sessionB.asObservable());
      return { sessionA, sessionB };
    }

    it('REV-001 ignores a late A seat-map response arriving after B resolved', () => {
      const { sessionA, sessionB } = startDeferredLoads();
      component.seatMap.set(null);

      component.loadSeatMap('event-1', 'session-1');
      component.switchSession(mockSessions[1]);

      sessionB.next(response);
      settleAvailability();
      expect(component.selectedSession()?.id).toBe('session-2');
      const appliedCalls = seatState.setSeats.calls.count();
      const subscribedCalls = webSocketService.connectForSession.calls.count();

      sessionA.next(response);

      expect(component.selectedSession()?.id).toBe('session-2');
      expect(seatState.setSeats.calls.count()).toBe(appliedCalls);
      expect(webSocketService.connectForSession.calls.count()).toBe(subscribedCalls);
      expect(webSocketService.connectForSession.calls.mostRecent().args[0]).toBe('session-2');
      expect(seatState.setSeats.calls.mostRecent().args[2]).toBe('session-2');
    });

    it('REV-001 ignores an early A response when B is still pending, then applies B', () => {
      const { sessionA, sessionB } = startDeferredLoads();
      component.seatMap.set(null);

      component.loadSeatMap('event-1', 'session-1');
      component.switchSession(mockSessions[1]);

      sessionA.next(response);
      expect(component.selectedSession()?.id).toBe('session-2');
      expect(component.seatMap()).toBeNull();

      sessionB.next(response);
      settleAvailability();

      expect(component.selectedSession()?.id).toBe('session-2');
      expect(component.seatMap()).toBe(response);
      expect(seatState.setSeats.calls.mostRecent().args[2]).toBe('session-2');
      expect(webSocketService.connectForSession.calls.mostRecent().args[0]).toBe('session-2');
    });

    it('REV-002 keeps selection and hold disabled until B availability settles', () => {
      component.switchSession(mockSessions[1]);

      expect(component.bookingState()).toBe('SESSION_SELECTED_LOADING_AVAILABILITY');
      expect(seatState.reconcileAvailability).toHaveBeenCalledWith(
        'session-2',
        jasmine.any(Set),
        jasmine.any(Function),
        jasmine.any(Function),
        jasmine.any(Function),
      );

      const seat = component.seats()[0]!;
      component.toggleSeat(seat);
      expect(component.selectedSeatIds().size).toBe(0);
      component.createHold();
      expect(reservationApi.createReservation).not.toHaveBeenCalled();

      settleAvailability();
      fixture.detectChanges();

      expect(component.bookingState()).toBe('SESSION_READY');
      component.toggleSeat(seat);
      expect(component.selectedSeatIds().size).toBe(1);
    });

    it('REV-002 never offers a B-held seat as selectable after reconciliation', () => {
      component.switchSession(mockSessions[1]);
      settleAvailability();

      seatSignal.set(
        component.seats().map((seat, index) =>
          index === 0 ? { ...seat, status: 'HELD' as const } : seat,
        ),
      );
      const heldSeat = component.seats()[0]!;

      component.toggleSeat(heldSeat);

      expect(component.selectedSeatIds().size).toBe(0);
    });

    it('REV-002 (FIX-2) keeps selection and hold disabled on availability error until retry succeeds', () => {
      component.switchSession(mockSessions[1]);

      // B's authoritative availability baseline fails.
      failAvailability();
      fixture.detectChanges();

      expect(component.seatAvailabilityError()).toBeTrue();
      expect(component.bookingState()).toBe('SESSION_SELECTED_LOADING_AVAILABILITY');

      // Neither selection nor hold submission is possible on the
      // non-authoritative AVAILABLE statuses.
      const seat = component.seats()[0]!;
      expect(seat.status).toBe('AVAILABLE');
      component.toggleSeat(seat);
      expect(component.selectedSeatIds().size).toBe(0);
      component.selectedSeatIds.set(new Set([seat.id]));
      component.createHold();
      expect(reservationApi.createReservation).not.toHaveBeenCalled();
      component.selectedSeatIds.set(new Set());

      // Error/retry state is shown instead of the seat map.
      expect(
        fixture.nativeElement.querySelector('[aria-label="Seat availability unavailable"]'),
      ).not.toBeNull();
      expect(fixture.nativeElement.querySelector('app-seat-map')).toBeNull();

      // Retry success re-enables booking correctly.
      component.retryAvailability();
      expect(component.seatAvailabilityError()).toBeFalse();
      settleAvailability();
      fixture.detectChanges();

      expect(component.bookingState()).toBe('SESSION_READY');
      expect(fixture.nativeElement.querySelector('app-seat-map')).not.toBeNull();
      component.toggleSeat(seat);
      expect(component.selectedSeatIds().size).toBe(1);
    });

    it('REV-003 blocks session switching while a hold request is in flight', () => {
      const pendingHold = new Subject<ReservationResponse>();
      reservationApi.createReservation.and.returnValue(pendingHold.asObservable());
      component.toggleSeat(component.seats()[0]!);
      component.createHold();
      expect(component.isCreatingHold()).toBeTrue();

      component.switchSession(mockSessions[1]);

      expect(component.selectedSession()?.id).toBe('session-1');
      expect(snackBar.open).toHaveBeenCalledWith(
        'Please wait until your hold request completes before switching showtimes.',
        'Close',
        jasmine.any(Object),
      );
      pendingHold.complete();
    });

    it('REV-003 cancels a stale hold without navigating when the session changed mid-flight', () => {
      const pendingHold = new Subject<ReservationResponse>();
      reservationApi.createReservation.and.returnValue(pendingHold.asObservable());
      const navigate = spyOn(router, 'navigate').and.resolveTo(true);
      component.toggleSeat(component.seats()[0]!);
      component.createHold();

      // The selection is invalidated (event reload with an unknown deep link)
      // while the hold request is outstanding.
      const route = TestBed.inject(ActivatedRoute);
      spyOn(route.snapshot.queryParamMap, 'get').and.returnValue('non-existent-session');
      component.retryLoad();

      const staleReservation: ReservationResponse = {
        id: 'reservation-stale',
        eventSessionId: 'session-1',
        eventId: 'event-1',
        status: 'PENDING',
        expiresAt: '2026-10-10T18:15:00Z',
        totalAmount: 42.5,
        seats: [],
      };
      pendingHold.next(staleReservation);

      expect(reservationApi.cancelReservation).toHaveBeenCalledWith('reservation-stale');
      expect(component.currentReservationId()).toBeNull();
      expect(navigate).not.toHaveBeenCalledWith(['/checkout', 'reservation-stale']);
    });
  });

  describe('two-session browser flow (TASK-P12-006 REV-006)', () => {
    it('drives A -> B -> A with URL/topic/availability transitions and no carry-over', () => {
      const navigate = spyOn(router, 'navigate').and.resolveTo(true);

      // Initial state: session A selected from the deep link, baseline settled.
      expect(component.selectedSession()?.id).toBe('session-1');
      expect(component.bookingState()).toBe('SESSION_READY');
      expect(fixture.nativeElement.querySelector('app-seat-map')).not.toBeNull();

      // A -> B: URL, topic, and availability transition together.
      component.switchSession(mockSessions[1]);
      fixture.detectChanges();

      expect(navigate).toHaveBeenCalledWith(
        [],
        jasmine.objectContaining({ queryParams: { sessionId: 'session-2' } }),
      );
      expect(webSocketService.connectForSession.calls.mostRecent().args[0]).toBe('session-2');
      expect(component.bookingState()).toBe('SESSION_SELECTED_LOADING_AVAILABILITY');
      // While B's baseline is pending the map (and hold dock) stay hidden.
      expect(fixture.nativeElement.querySelector('app-seat-map')).toBeNull();
      expect(fixture.nativeElement.querySelector('[aria-label="Loading seat map"]')).not.toBeNull();

      settleAvailability();
      fixture.detectChanges();

      expect(component.bookingState()).toBe('SESSION_READY');
      expect(fixture.nativeElement.querySelector('app-seat-map')).not.toBeNull();
      component.toggleSeat(component.seats()[0]!);
      expect(component.selectedSeatIds().size).toBe(1);

      // B -> A: selection is cleared, URL/topic/availability return to A.
      component.switchSession(mockSessions[0]);
      fixture.detectChanges();

      expect(component.selectedSeatIds().size).toBe(0);
      expect(navigate).toHaveBeenCalledWith(
        [],
        jasmine.objectContaining({ queryParams: { sessionId: 'session-1' } }),
      );
      expect(webSocketService.connectForSession.calls.mostRecent().args[0]).toBe('session-1');

      settleAvailability();
      fixture.detectChanges();

      expect(component.selectedSession()?.id).toBe('session-1');
      expect(component.selectedSeatIds().size).toBe(0);
      expect(component.bookingState()).toBe('SESSION_READY');
      expect(fixture.nativeElement.querySelector('app-seat-map')).not.toBeNull();
    });
  });

  describe('deep-link sale-window guard (TASK-P12-006 REV-004)', () => {
    const upcomingSession: EventSession = {
      id: 'session-upcoming',
      eventId: 'event-1',
      startsAt: '2027-06-01T18:00:00Z',
      endsAt: '2027-06-01T20:00:00Z',
      saleStartsAt: '2027-01-01T00:00:00Z',
      status: 'SCHEDULED',
      timezone: 'UTC',
    };
    const closedSession: EventSession = {
      id: 'session-closed',
      eventId: 'event-1',
      startsAt: '2027-06-01T18:00:00Z',
      endsAt: '2027-06-01T20:00:00Z',
      saleEndsAt: '2020-01-01T00:00:00Z',
      status: 'SCHEDULED',
      timezone: 'UTC',
    };
    // Already started but not yet ended at test time (REV-004 FIX-2): the
    // reservation service rejects startsAt <= now, so the UI must too.
    const startedSession: EventSession = {
      id: 'session-started',
      eventId: 'event-1',
      startsAt: new Date(Date.now() - 30 * 60 * 1000).toISOString(),
      endsAt: new Date(Date.now() + 90 * 60 * 1000).toISOString(),
      status: 'SCHEDULED',
      timezone: 'UTC',
    };

    function reloadWithDeepLink(sessionId: string, sessions: EventSession[]): void {
      eventApi.getEventSessions.and.returnValue(of(sessions));
      const route = TestBed.inject(ActivatedRoute);
      spyOn(route.snapshot.queryParamMap, 'get').and.returnValue(sessionId);
      const seatMapCalls = eventApi.getEventSeatMap.calls.count();
      component.loadEventAndSessions('event-1');
      expect(eventApi.getEventSeatMap.calls.count()).toBe(seatMapCalls);
    }

    it('rejects a direct link to a sale-upcoming session without loading seats', () => {
      reloadWithDeepLink('session-upcoming', [upcomingSession, mockSessions[0]]);

      expect(component.selectedSession()).toBeNull();
      expect(component.seatMap()).toBeNull();
      expect(component.sessionWarning()).toContain('not available');
    });

    it('rejects a direct link to a sale-closed session without loading seats', () => {
      reloadWithDeepLink('session-closed', [closedSession, mockSessions[0]]);

      expect(component.selectedSession()).toBeNull();
      expect(component.seatMap()).toBeNull();
      expect(component.sessionWarning()).toContain('not available');
    });

    it('rejects a direct link to an already-started session without loading seats', () => {
      reloadWithDeepLink('session-started', [startedSession, mockSessions[0]]);

      expect(component.selectedSession()).toBeNull();
      expect(component.seatMap()).toBeNull();
      expect(component.sessionWarning()).toContain('not available');
    });

    it('preserves a direct link to a bookable future session', () => {
      eventApi.getEventSessions.and.returnValue(of(mockSessions));
      const route = TestBed.inject(ActivatedRoute);
      spyOn(route.snapshot.queryParamMap, 'get').and.returnValue('session-1');
      const seatMapCalls = eventApi.getEventSeatMap.calls.count();

      component.loadEventAndSessions('event-1');

      expect(eventApi.getEventSeatMap.calls.count()).toBe(seatMapCalls + 1);
      expect(component.selectedSession()?.id).toBe('session-1');
      expect(component.sessionWarning()).toBeNull();
    });
  });

  it('disconnects the root-scoped live service when destroyed', () => {
    const disconnectCount = webSocketService.disconnect.calls.count();
    fixture.destroy();
    expect(webSocketService.disconnect.calls.count()).toBe(disconnectCount + 1);
  });

  it('formats event date with sfDate full variant in 24-hour format matching event detail', () => {
    const pipe = new DateFormatPipe();
    const formatted = pipe.transform(mockSessions[0].startsAt, 'full');
    expect(formatted).toContain('•');
    expect(formatted).not.toMatch(/AM|PM/);
  });

  describe('advanced geometry flattening (TASK-P11-012)', () => {
    const advancedResponse: EventSeatMapResponse = {
      eventId: 'event-1',
      venueId: 'venue-1',
      eventTitle: 'Live at SeatFlow',
      venueName: 'Main Hall',
      venueCapacity: 100,
      totalConfiguredSeats: 3,
      layoutVersion: 7,
      layoutElements: [
        {
          elementId: 'stage-1',
          type: 'STAGE',
          label: 'Main Stage',
          geometry: { x: 0, y: 0, width: 528, height: 60, rotationDeg: 0 },
          zIndex: 0,
        },
      ],
      sections: [
        {
          sectionId: 'section-1',
          name: 'Orchestra',
          rowCount: 1,
          colCount: 10,
          isActive: true,
          positionX: 10.5,
          positionY: 20,
          width: 440.5,
          height: 44,
          rotationDeg: 15,
          zIndex: 3,
          seats: [
            {
              seatId: 'seat-x1',
              rowLabel: 'A',
              seatNumber: 1,
              gridX: 0,
              gridY: 0,
              isActive: true,
              positionX: 44.5,
              positionY: 12,
            },
          ],
          pricingTiers: [
            {
              id: 'tier-a',
              sectionId: 'section-1',
              categoryName: 'Categoria A',
              price: 150,
              currency: 'RON',
            },
          ],
        },
        {
          sectionId: 'section-legacy',
          name: 'Legacy Hall',
          rowCount: 2,
          colCount: 10,
          seats: [
            {
              seatId: 'seat-legacy',
              rowLabel: 'B',
              seatNumber: 3,
              gridX: 2,
              gridY: 1,
              isActive: true,
            },
          ],
          pricingTiers: [
            {
              id: 'tier-legacy',
              sectionId: 'section-legacy',
              categoryName: 'Standard',
              price: 80,
              currency: 'RON',
            },
          ],
        },
        {
          sectionId: 'section-closed',
          name: 'Closed Loft',
          rowCount: 1,
          colCount: 1,
          isActive: false,
          seats: [
            {
              seatId: 'seat-closed',
              rowLabel: 'A',
              seatNumber: 1,
              gridX: 0,
              gridY: 0,
              isActive: true,
            },
          ],
          pricingTiers: [
            {
              id: 'tier-closed',
              sectionId: 'section-closed',
              categoryName: 'Standard',
              price: 80,
              currency: 'RON',
            },
          ],
        },
      ],
    };

    function loadAdvanced(): void {
      eventApi.getEventSeatMap.and.returnValue(of(advancedResponse));
      component.retryLoad();
    }

    it('copies continuous geometry without changing IDs, pricing, currency, or status', () => {
      loadAdvanced();

      const seat = component.seats().find((current) => current.id === 'seat-x1')!;
      expect(seat).toEqual(
        jasmine.objectContaining({
          id: 'seat-x1',
          sectionId: 'section-1',
          sectionName: 'Orchestra',
          price: 150,
          currency: 'RON',
          status: 'AVAILABLE',
          isActive: true,
          positionX: 44.5,
          positionY: 12,
          sectionPositionX: 10.5,
          sectionPositionY: 20,
          sectionWidth: 440.5,
          sectionHeight: 44,
          sectionRotationDeg: 15,
          sectionZIndex: 3,
          categoryName: 'Categoria A',
          pricingTierId: 'tier-a',
        }),
      );
    });

    it('derives legacy grid fallbacks matching the event-service adapter defaults', () => {
      loadAdvanced();

      const seat = component.seats().find((current) => current.id === 'seat-legacy')!;
      expect(seat.positionX).toBe(88);
      expect(seat.positionY).toBe(44);
      expect(seat.sectionPositionX).toBe(0);
      expect(seat.sectionPositionY).toBe(0);
      expect(seat.sectionWidth).toBe(440);
      expect(seat.sectionHeight).toBe(88);
      expect(seat.sectionRotationDeg).toBe(0);
      expect(seat.sectionZIndex).toBe(0);
    });

    it('excludes inactive sections while preserving the layout elements contract', () => {
      loadAdvanced();

      expect(component.seats().some((seat) => seat.id === 'seat-closed')).toBeFalse();
      expect(component.seatMap()?.layoutVersion).toBe(7);
      expect(component.seatMap()?.layoutElements?.length).toBe(1);
      expect(component.seatMap()?.layoutElements?.[0]).toEqual(
        jasmine.objectContaining({
          elementId: 'stage-1',
          type: 'STAGE',
          label: 'Main Stage',
        }),
      );
    });
  });
});

