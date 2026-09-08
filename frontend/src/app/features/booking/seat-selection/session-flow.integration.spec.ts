import { Location } from '@angular/common';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideLocationMocks } from '@angular/common/testing';
import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { By } from '@angular/platform-browser';
import { provideRouter, Router, RouterOutlet } from '@angular/router';
import { Client, IMessage, StompConfig, StompSubscription } from '@stomp/stompjs';
import { of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { UserContextService } from '../../../core/auth/user-context.service';
import { EventDetail, EventSession } from '../../../models/event.model';
import { EventSeatMapResponse, SeatAvailabilityResponse } from '../../../models/seat.model';
import { EventApiService } from '../../../services/event-api.service';
import { ReservationApiService } from '../../../services/reservation-api.service';
import { SeatStateService } from '../../../services/seat-state.service';
import {
  SOCKJS_FACTORY,
  STOMP_CLIENT_FACTORY,
  WebSocketService,
} from '../../../services/websocket.service';
import { SeatMapComponent } from '../seat-map/seat-map.component';
import { SeatSelectionComponent } from './seat-selection.component';

/**
 * REV-006 Router/DOM integration coverage (TASK-P12-006 FIX-3).
 *
 * Unlike `seat-selection.component.spec.ts` (mocked Router/WebSocketService and
 * direct `component.switchSession()` calls), this suite drives the two-session
 * flow through the actually rendered session-selector controls with a REAL
 * Router, REAL SeatStateService (HttpTestingController) and the REAL
 * WebSocketService (fake STOMP transport underneath):
 *
 * - clicks A -> B -> A through rendered `button[role="radio"]` controls;
 * - asserts the ACTUAL URL/query (`Router.url` / `Location.path()`);
 * - asserts session-specific availability HTTP
 *   (`/api/event-sessions/<id>/seats/availability`) with per-session payloads
 *   and verifies the rendered seat state differs per session;
 * - spies on the real WebSocketService (`callThrough`) and asserts the
 *   canonical `/topic/sessions/<id>/seats` lifecycle plus stale-message
 *   isolation;
 * - asserts no carry-over of selection, hold identity, rendered availability,
 *   or warnings across switches.
 *
 * Runs in real headless Chrome via Karma (`npm run test:session-flow`).
 * NOTE: the frontend runs zoneless (no zone.js), so this suite uses
 * async/await + `whenStable()` instead of `fakeAsync`/`tick` — still fully
 * deterministic, no arbitrary sleeps.
 *
 * Residual gap (documented in the review ledger): no real backend/STOMP
 * transport — the STOMP client factory and HTTP backend are test doubles at
 * the transport edge. Full Playwright E2E is owned by Phase-17 P17-006.
 */
class FakeStompClient {
  active = false;
  readonly subscription = jasmine.createSpyObj<StompSubscription>('StompSubscription', [
    'unsubscribe',
  ]);
  readonly activate = jasmine.createSpy('activate').and.callFake(() => {
    this.active = true;
  });
  readonly deactivate = jasmine.createSpy('deactivate').and.callFake(() => {
    this.active = false;
    return Promise.resolve();
  });
  readonly subscribe = jasmine
    .createSpy('subscribe')
    .and.callFake((_destination: string, callback: (message: IMessage) => void) => {
      this.messageCallback = callback;
      return this.subscription;
    });
  messageCallback: ((message: IMessage) => void) | null = null;
}

@Component({
  standalone: true,
  imports: [RouterOutlet],
  template: '<router-outlet></router-outlet>',
})
class SessionFlowHostComponent {}

describe('SessionFlow Router/DOM integration (TASK-P12-006 REV-006)', () => {
  let fixture: ComponentFixture<SessionFlowHostComponent>;
  let router: Router;
  let location: Location;
  let httpMock: HttpTestingController;
  let webSocketService: WebSocketService;
  let reservationApi: jasmine.SpyObj<ReservationApiService>;
  let clients: FakeStompClient[];
  let configs: StompConfig[];

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

  const mockEvent: EventDetail = {
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

  const seatMapResponse: EventSeatMapResponse = {
    eventId: 'event-1',
    venueId: 'venue-1',
    eventTitle: 'Live at SeatFlow',
    venueName: 'Main Hall',
    venueCapacity: 100,
    totalConfiguredSeats: 3,
    sections: [
      {
        sectionId: 'section-1',
        name: 'Orchestra',
        rowCount: 1,
        colCount: 3,
        seats: [
          { seatId: 'seat-1', rowLabel: 'A', seatNumber: 1, gridX: 0, gridY: 0, isActive: true },
          { seatId: 'seat-2', rowLabel: 'A', seatNumber: 2, gridX: 1, gridY: 0, isActive: true },
          { seatId: 'seat-3', rowLabel: 'A', seatNumber: 3, gridX: 2, gridY: 0, isActive: true },
        ],
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

  // Session-specific authoritative availability: A and B deliberately disagree
  // on seat-1/seat-2 so the test can prove which session's payload rendered.
  const availabilityA: SeatAvailabilityResponse = {
    eventSessionId: 'session-1',
    eventId: 'event-1',
    seatStatuses: [
      { seatId: 'seat-1', status: 'AVAILABLE' },
      { seatId: 'seat-2', status: 'HELD' },
      { seatId: 'seat-3', status: 'AVAILABLE' },
    ],
  };
  const availabilityB: SeatAvailabilityResponse = {
    eventSessionId: 'session-2',
    eventId: 'event-1',
    seatStatuses: [
      { seatId: 'seat-1', status: 'HELD' },
      { seatId: 'seat-2', status: 'AVAILABLE' },
      { seatId: 'seat-3', status: 'AVAILABLE' },
    ],
  };

  function seatComp(): SeatSelectionComponent {
    const debug = fixture.debugElement.query(By.directive(SeatSelectionComponent));
    expect(debug).withContext('SeatSelectionComponent should be routed').not.toBeNull();
    return debug.componentInstance as SeatSelectionComponent;
  }

  function seatStatus(seatId: string): string | undefined {
    return seatComp().seats().find((seat) => seat.id === seatId)?.status;
  }

  /** Rendered seat node (`section-node` sets data-seat-id/data-status/aria-label). */
  function seatNode(seatId: string): Element | null {
    return fixture.nativeElement.querySelector(`[data-seat-id="${seatId}"]`);
  }

  function seatDomStatus(seatId: string): string | null {
    return seatNode(seatId)?.getAttribute('data-status') ?? null;
  }

  function seatAriaLabel(seatId: string): string | null {
    return seatNode(seatId)?.getAttribute('aria-label') ?? null;
  }

  /** Inputs actually received by the REAL rendered seat-map (not stubbed). */
  function renderedSeatMapInputStatuses(): Map<string, string> {
    const debug = fixture.debugElement.query(By.directive(SeatMapComponent));
    expect(debug).withContext('app-seat-map should render the real component').not.toBeNull();
    const seats = (debug.componentInstance as SeatMapComponent).seats();
    return new Map(seats.map((seat) => [seat.id, seat.status]));
  }

  /** The invalid-session warning banner renders the warning text outside the map branch. */
  function isWarningRendered(): boolean {
    return (fixture.nativeElement.textContent as string).includes(
      'not available for this event',
    );
  }

  const STALE_WARNING =
    'The requested showtime is not available for this event. Please select a showtime below.';

  async function stable(): Promise<void> {
    await fixture.whenStable();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  /** Flush every pending availability request for a session (component + WS reconnect). */
  async function flushAvailability(
    sessionId: string,
    payload: SeatAvailabilityResponse,
  ): Promise<void> {
    const pending = httpMock.match(`/api/event-sessions/${sessionId}/seats/availability`);
    expect(pending.length)
      .withContext(`expected availability request(s) for ${sessionId}`)
      .toBeGreaterThan(0);
    pending.forEach((request) => {
      expect(request.request.method).toBe('GET');
      request.flush(payload);
    });
    await stable();
  }

  async function fireOnConnect(clientIndex: number): Promise<void> {
    configs[clientIndex].onConnect?.({} as never);
    await stable();
  }

  /** Settle a session: flush the component's baseline first (so its guarded
   * onSettled clears the transition), then fire STOMP onConnect (triggers the
   * WS-side reconcile) and flush that too. This mirrors production timing —
   * the REST baseline settles before the socket connects — and avoids the
   * WS reconcile superseding the component's request before it settles. */
  async function settleSession(
    clientIndex: number,
    sessionId: string,
    payload: SeatAvailabilityResponse,
  ): Promise<void> {
    await flushAvailability(sessionId, payload);
    await fireOnConnect(clientIndex);
    await flushAvailability(sessionId, payload);
  }

  async function openShowtimePicker(): Promise<void> {
    const toggle = Array.from(
      fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>,
    ).find((button) => button.textContent?.includes('Change showtime'));
    expect(toggle).withContext('Change showtime toggle should render').toBeDefined();
    toggle!.click();
    await stable();
    expect(fixture.nativeElement.querySelector('.showtime-switcher-card'))
      .withContext('showtime picker card should open after toggle click')
      .not.toBeNull();
  }

  /** Click a rendered session radio by sorted position (0 = session-1, 1 = session-2). */
  async function clickSessionRadio(index: number): Promise<void> {
    const radios = fixture.nativeElement.querySelectorAll(
      '.showtime-switcher-card button[role="radio"]',
    ) as NodeListOf<HTMLButtonElement>;
    expect(radios.length).withContext('expected two rendered showtime radios').toBe(2);
    expect(radios[index].disabled)
      .withContext(`session radio ${index} should be enabled`)
      .toBeFalse();
    radios[index].click();
    await stable();
  }

  async function deliverStompMessage(clientIndex: number, body: object): Promise<void> {
    clients[clientIndex].messageCallback?.({ body: JSON.stringify(body) } as IMessage);
    await stable();
  }

  beforeEach(async () => {
    clients = [];
    configs = [];
    const sockJsFactory = jasmine.createSpy('SockJsFactory').and.returnValue({});
    const authService = jasmine.createSpyObj<AuthService>('AuthService', ['getToken']);
    authService.getToken.and.returnValue(null);

    const eventApi = jasmine.createSpyObj<EventApiService>('EventApiService', [
      'getEventById',
      'getEventSessions',
      'getEventSeatMap',
    ]);
    eventApi.getEventById.and.returnValue(of(mockEvent));
    eventApi.getEventSessions.and.returnValue(of(mockSessions));
    eventApi.getEventSeatMap.and.returnValue(of(seatMapResponse));

    reservationApi = jasmine.createSpyObj<ReservationApiService>('ReservationApiService', [
      'createReservation',
      'cancelReservation',
      'clearStoredCustomerEmailProof',
    ]);
    reservationApi.cancelReservation.and.returnValue(of(undefined));

    const snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);

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
      imports: [SessionFlowHostComponent],
      providers: [
        provideRouter([{ path: 'events/:id/seats', component: SeatSelectionComponent }]),
        provideLocationMocks(),
        provideHttpClient(),
        provideHttpClientTesting(),
        SeatStateService,
        WebSocketService,
        { provide: AuthService, useValue: authService },
        { provide: SOCKJS_FACTORY, useValue: sockJsFactory },
        {
          provide: STOMP_CLIENT_FACTORY,
          useValue: (config: StompConfig): Client => {
            const client = new FakeStompClient();
            clients.push(client);
            configs.push(config);
            return client as unknown as Client;
          },
        },
        { provide: EventApiService, useValue: eventApi },
        { provide: ReservationApiService, useValue: reservationApi },
        { provide: MatSnackBar, useValue: snackBar },
        { provide: UserContextService, useValue: userContext },
      ],
    }).compileComponents();

    router = TestBed.inject(Router);
    location = TestBed.inject(Location);
    httpMock = TestBed.inject(HttpTestingController);
    webSocketService = TestBed.inject(WebSocketService);
    // Spy on the REAL service (callThrough): proves canonical topic
    // construction, subscription teardown, and stale-message isolation — not
    // just that a mocked method received an ID.
    spyOn(webSocketService, 'connectForSession').and.callThrough();
    spyOn(webSocketService, 'disconnect').and.callThrough();

    fixture = TestBed.createComponent(SessionFlowHostComponent);
    fixture.detectChanges();
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('clicks A -> B -> A through the rendered selector with URL/availability/topic transitions and no carry-over', async () => {
    // --- Initial deep link: session A -------------------------------------
    await router.navigateByUrl('/events/event-1/seats?sessionId=session-1');
    await stable();

    expect(router.url).withContext('actual URL should carry session A').toContain('sessionId=session-1');
    expect(location.path()).toContain('sessionId=session-1');
    expect(seatComp().selectedSession()?.id).toBe('session-1');

    await settleSession(0, 'session-1', availabilityA);

    // Canonical A topic subscribed on the real service.
    expect(webSocketService.connectForSession).toHaveBeenCalledWith(
      'session-1',
      jasmine.any(Function),
      jasmine.any(Function),
    );
    expect(clients[0].subscribe.calls.mostRecent().args[0]).toBe(
      '/topic/sessions/session-1/seats',
    );
    // Session-specific availability rendered: A says seat-1 AVAILABLE, seat-2 HELD —
    // asserted on the signal, the REAL seat-map inputs, AND the rendered DOM nodes.
    expect(seatStatus('seat-1')).toBe('AVAILABLE');
    expect(seatStatus('seat-2')).toBe('HELD');
    expect(renderedSeatMapInputStatuses().get('seat-1')).toBe('AVAILABLE');
    expect(renderedSeatMapInputStatuses().get('seat-2')).toBe('HELD');
    expect(seatNode('seat-1'))
      .withContext('rendered seat-1 node should exist for session A')
      .not.toBeNull();
    expect(seatDomStatus('seat-1')).withContext('rendered seat-1 should show A status').toBe('AVAILABLE');
    expect(seatDomStatus('seat-2')).withContext('rendered seat-2 should show A status').toBe('HELD');
    expect(seatAriaLabel('seat-1'))
      .withContext('rendered seat-1 aria should expose availability')
      .toContain('available');
    expect(seatAriaLabel('seat-2'))
      .withContext('rendered seat-2 aria should expose held')
      .toContain('held');
    expect(seatComp().bookingState()).toBe('SESSION_READY');
    expect(fixture.nativeElement.querySelector('app-seat-map'))
      .withContext('seat map should render for session A')
      .not.toBeNull();
    expect(fixture.nativeElement.querySelector('[aria-label="Loading seat map"]')).toBeNull();

    // Build A-bound state that must not survive the switch: a selected seat
    // (rendered dock pill) plus an active hold identity.
    const seatA1 = seatComp().seats().find((seat) => seat.id === 'seat-1')!;
    seatComp().toggleSeat(seatA1);
    seatComp().currentReservationId.set('hold-A');
    await stable();
    expect(seatComp().selectedSeatIds().has('seat-1')).toBeTrue();
    expect(fixture.nativeElement.querySelector('.selection-dock'))
      .withContext('selection dock should render the A selection')
      .not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('A-1');

    // Seed a stale A warning (invalid deep-link residue): it must render now
    // AND be cleared by the A -> B switch (signal + banner absence). Deleting
    // the switch's `sessionWarning.set(null)` must fail these assertions.
    seatComp().sessionWarning.set(STALE_WARNING);
    fixture.detectChanges();
    await stable();
    expect(seatComp().sessionWarning()).not.toBeNull();
    expect(isWarningRendered()).withContext('seeded warning should render before A -> B').toBeTrue();

    const connectsBeforeB = (webSocketService.connectForSession as jasmine.Spy).calls.count();
    const disconnectsBeforeB = (webSocketService.disconnect as jasmine.Spy).calls.count();

    // --- A -> B through rendered clicks (never component.switchSession()) --
    await openShowtimePicker();
    await clickSessionRadio(1);

    // Actual URL transition, not spy args.
    expect(router.url).withContext('actual URL should carry session B').toContain('sessionId=session-2');
    expect(location.path()).toContain('sessionId=session-2');
    expect(seatComp().selectedSession()?.id).toBe('session-2');

    // Atomic switch side effects: A hold cancelled + identity cleared,
    // selection cleared (state AND rendered dock), realtime torn down.
    expect(reservationApi.cancelReservation).toHaveBeenCalledWith('hold-A');
    expect(seatComp().currentReservationId()).toBeNull();
    expect(seatComp().selectedSeatIds().size).toBe(0);
    expect(fixture.nativeElement.querySelector('.selection-dock'))
      .withContext('rendered selection must not survive A -> B')
      .toBeNull();
    expect(seatComp().sessionWarning()).toBeNull();
    expect(isWarningRendered()).withContext('seeded warning banner must clear on A -> B').toBeFalse();
    expect((webSocketService.disconnect as jasmine.Spy).calls.count()).toBeGreaterThan(
      disconnectsBeforeB,
    );
    expect(clients[0].subscription.unsubscribe)
      .withContext('A topic subscription must be torn down')
      .toHaveBeenCalled();
    expect(webSocketService.connectForSession).toHaveBeenCalledWith(
      'session-2',
      jasmine.any(Function),
      jasmine.any(Function),
    );
    expect((webSocketService.connectForSession as jasmine.Spy).calls.count()).toBe(
      connectsBeforeB + 1,
    );

    await settleSession(1, 'session-2', availabilityB);

    expect(clients[1].subscribe.calls.mostRecent().args[0]).toBe(
      '/topic/sessions/session-2/seats',
    );
    // Rendered availability now reflects B ONLY (inverted vs A) — signal,
    // real seat-map inputs, AND rendered DOM nodes.
    expect(seatStatus('seat-1')).withContext('B payload should render seat-1 HELD').toBe('HELD');
    expect(seatStatus('seat-2')).withContext('B payload should render seat-2 AVAILABLE').toBe('AVAILABLE');
    expect(renderedSeatMapInputStatuses().get('seat-1')).toBe('HELD');
    expect(renderedSeatMapInputStatuses().get('seat-2')).toBe('AVAILABLE');
    expect(seatDomStatus('seat-1')).withContext('rendered seat-1 should show B status').toBe('HELD');
    expect(seatDomStatus('seat-2')).withContext('rendered seat-2 should show B status').toBe('AVAILABLE');
    expect(seatAriaLabel('seat-1'))
      .withContext('rendered seat-1 aria should expose held in B')
      .toContain('held');
    expect(seatAriaLabel('seat-2'))
      .withContext('rendered seat-2 aria should expose availability in B')
      .toContain('available');
    expect(seatComp().bookingState()).toBe('SESSION_READY');
    expect(fixture.nativeElement.querySelector('app-seat-map')).not.toBeNull();

    // Stale-message isolation: a late A update on B's channel is ignored.
    await deliverStompMessage(1, {
      eventSessionId: 'session-1',
      eventId: 'event-1',
      seatId: 'seat-2',
      status: 'HELD',
      timestamp: '2026-10-11T18:00:01Z',
    });
    expect(seatStatus('seat-2'))
      .withContext('stale A STOMP message must not mutate B state')
      .toBe('AVAILABLE');
    expect(webSocketService.lastSeatUpdate()?.eventSessionId).not.toBe('session-1');

    // Disconnected-A isolation: fire the RETAINED A client's own callback
    // (clients[0], torn down on A -> B — not the live B client) with an
    // otherwise valid A update. The service must ignore it via the
    // `this.client !== client` guard, leaving B state/DOM/subscription intact.
    expect(clients[0].messageCallback)
      .withContext('A client should retain its subscription callback after teardown')
      .not.toBeNull();
    await deliverStompMessage(0, {
      eventSessionId: 'session-1',
      eventId: 'event-1',
      seatId: 'seat-1',
      status: 'SOLD',
      timestamp: '2026-10-11T18:00:01Z',
    });
    expect(seatStatus('seat-1'))
      .withContext('late A-client callback must not mutate B seat-1')
      .toBe('HELD');
    expect(seatStatus('seat-2'))
      .withContext('late A-client callback must not mutate B seat-2')
      .toBe('AVAILABLE');
    expect(seatDomStatus('seat-1'))
      .withContext('rendered B seat-1 must survive the late A-client callback')
      .toBe('HELD');
    expect(seatComp().selectedSession()?.id)
      .withContext('late A-client callback must not move the selected session')
      .toBe('session-2');
    expect(webSocketService.lastSeatUpdate()?.eventSessionId).not.toBe('session-1');
    expect(clients[1].subscription.unsubscribe)
      .withContext('B subscription must survive the late A-client callback')
      .not.toHaveBeenCalled();

    // The live B channel still applies genuine B updates.
    await deliverStompMessage(1, {
      eventSessionId: 'session-2',
      eventId: 'event-1',
      seatId: 'seat-3',
      status: 'SOLD',
      timestamp: '2026-10-11T18:00:02Z',
    });
    expect(seatStatus('seat-3')).withContext('live B STOMP message should apply').toBe('SOLD');
    expect(seatDomStatus('seat-3')).withContext('rendered seat-3 should show the live B update').toBe('SOLD');

    // Build B-bound selection that must not survive the return to A.
    // seat-2 is AVAILABLE in B (but HELD in A) — selecting it proves both the
    // B baseline and the later carry-over clearance.
    const seatB2 = seatComp().seats().find((seat) => seat.id === 'seat-2')!;
    expect(seatB2.status).toBe('AVAILABLE');
    seatComp().toggleSeat(seatB2);
    seatComp().currentReservationId.set('hold-B');
    await stable();
    expect(seatComp().selectedSeatIds().has('seat-2')).toBeTrue();

    // Seed a B warning so B -> A must clear it too (signal + banner).
    seatComp().sessionWarning.set(STALE_WARNING);
    fixture.detectChanges();
    await stable();
    expect(isWarningRendered()).withContext('seeded warning should render before B -> A').toBeTrue();

    const connectsBeforeA = (webSocketService.connectForSession as jasmine.Spy).calls.count();
    const disconnectsBeforeA = (webSocketService.disconnect as jasmine.Spy).calls.count();

    // --- B -> A through rendered clicks ------------------------------------
    await openShowtimePicker();
    await clickSessionRadio(0);

    expect(router.url).withContext('actual URL should return to session A').toContain('sessionId=session-1');
    expect(location.path()).toContain('sessionId=session-1');
    expect(seatComp().selectedSession()?.id).toBe('session-1');
    expect(reservationApi.cancelReservation).toHaveBeenCalledWith('hold-B');
    expect(seatComp().currentReservationId()).toBeNull();
    expect(seatComp().selectedSeatIds().size).withContext('B selection must not survive B -> A').toBe(0);
    expect(fixture.nativeElement.querySelector('.selection-dock'))
      .withContext('rendered B selection must not survive B -> A')
      .toBeNull();
    expect((webSocketService.connectForSession as jasmine.Spy).calls.count()).toBe(
      connectsBeforeA + 1,
    );
    // Symmetric teardown: B's subscription/client must be torn down on B -> A
    // (mirrors the A-unsubscribe assertion on A -> B above).
    expect((webSocketService.disconnect as jasmine.Spy).calls.count()).toBeGreaterThan(
      disconnectsBeforeA,
    );
    expect(clients[1].subscription.unsubscribe)
      .withContext('B topic subscription must be torn down on B -> A')
      .toHaveBeenCalled();
    expect(clients[1].deactivate)
      .withContext('B STOMP client must be deactivated on B -> A')
      .toHaveBeenCalled();
    expect(seatComp().sessionWarning()).toBeNull();
    expect(isWarningRendered()).withContext('seeded warning banner must clear on B -> A').toBeFalse();

    await settleSession(2, 'session-1', availabilityA);

    // Canonical topic lifecycle A -> B -> A in order.
    const topics = clients.map(
      (client) => client.subscribe.calls.mostRecent()?.args[0] as string | undefined,
    );
    expect(topics).toEqual([
      '/topic/sessions/session-1/seats',
      '/topic/sessions/session-2/seats',
      '/topic/sessions/session-1/seats',
    ]);
    // Rendered availability is A's again: seat-1 AVAILABLE, seat-2 HELD —
    // no B state carried over (signal, real seat-map inputs, rendered DOM).
    expect(seatStatus('seat-1')).toBe('AVAILABLE');
    expect(seatStatus('seat-2')).withContext('B availability must not leak into A').toBe('HELD');
    expect(renderedSeatMapInputStatuses().get('seat-1')).toBe('AVAILABLE');
    expect(renderedSeatMapInputStatuses().get('seat-2')).toBe('HELD');
    expect(seatDomStatus('seat-1')).withContext('rendered availability must return to A').toBe('AVAILABLE');
    expect(seatDomStatus('seat-2')).withContext('B availability must not leak into rendered A').toBe('HELD');
    expect(seatAriaLabel('seat-1')).toContain('available');
    expect(seatAriaLabel('seat-2')).toContain('held');
    // Canonical topic lifecycle A -> B -> A in order.
    const connectOrder = (webSocketService.connectForSession as jasmine.Spy).calls
      .allArgs()
      .map((args) => args[0]);
    expect(connectOrder).toEqual(['session-1', 'session-2', 'session-1']);
    expect(seatComp().selectedSeatIds().size).toBe(0);
    expect(seatComp().currentReservationId()).toBeNull();
    expect(seatComp().sessionWarning()).toBeNull();
    expect(seatComp().bookingState()).toBe('SESSION_READY');
    expect(fixture.nativeElement.querySelector('app-seat-map')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.selection-dock')).toBeNull();
  });

  it('ignores a late session-A HTTP response after A -> B and keeps B reconnect pinned to B (TASK-P12-008 REV-008)', async () => {
    // --- Deep link to A and connect it, but keep HTTP responses delayed ----
    await router.navigateByUrl('/events/event-1/seats?sessionId=session-1');
    await stable();

    expect(seatComp().selectedSession()?.id).toBe('session-1');
    await fireOnConnect(0);
    expect(clients[0].subscribe.calls.mostRecent().args[0]).toBe(
      '/topic/sessions/session-1/seats',
    );
    // Deferred flush: capture A's availability requests (component baseline +
    // WS reconcile) while they are still in flight. SeatStateService never
    // cancels a superseded request (its request-id guard ignores the late
    // response instead), so these handles stay flushable after the switch.
    const pendingA = httpMock.match('/api/event-sessions/session-1/seats/availability');
    expect(pendingA.length).withContext('A baseline requests should be pending').toBeGreaterThan(0);

    // --- A -> B while A's HTTP response is still delayed -------------------
    await openShowtimePicker();
    await clickSessionRadio(1);

    expect(router.url).withContext('actual URL should carry session B').toContain('sessionId=session-2');
    expect(seatComp().selectedSession()?.id).toBe('session-2');
    expect(clients[0].subscription.unsubscribe)
      .withContext('A topic subscription must be torn down')
      .toHaveBeenCalled();
    expect(clients.length).toBe(2);

    // Settle B: component baseline, then the WS-side reconcile, mirroring
    // production timing (see settleSession).
    await flushAvailability('session-2', availabilityB);
    await fireOnConnect(1);
    await flushAvailability('session-2', availabilityB);

    expect(seatStatus('seat-1')).withContext('B payload should render seat-1 HELD').toBe('HELD');
    expect(seatStatus('seat-2')).withContext('B payload should render seat-2 AVAILABLE').toBe('AVAILABLE');
    expect(seatDomStatus('seat-1')).withContext('rendered seat-1 should show B status').toBe('HELD');

    // --- Late A HTTP response arrives AFTER the switch ----------------------
    // availabilityA deliberately disagrees with B on seat-1 (AVAILABLE vs
    // HELD): if the stale response mutated B state, seat-1 would flip.
    pendingA.forEach((request) => {
      expect(request.request.method).toBe('GET');
      request.flush(availabilityA);
    });
    await stable();

    expect(seatComp().selectedSession()?.id)
      .withContext('late A HTTP must not move the selected session')
      .toBe('session-2');
    expect(seatStatus('seat-1')).withContext('late A HTTP must not mutate B seat-1').toBe('HELD');
    expect(seatStatus('seat-2')).withContext('late A HTTP must not mutate B seat-2').toBe('AVAILABLE');
    expect(seatDomStatus('seat-1'))
      .withContext('rendered B seat-1 must survive the late A HTTP')
      .toBe('HELD');
    expect(seatDomStatus('seat-2'))
      .withContext('rendered B seat-2 must survive the late A HTTP')
      .toBe('AVAILABLE');

    // --- B reconnect resubscribes B only + REST refresh reconciles ------------
    const subscribesBefore = clients[1].subscribe.calls.count();
    await fireOnConnect(1);
    expect(clients[1].subscribe.calls.count()).toBe(subscribesBefore + 1);
    expect(clients[1].subscribe.calls.mostRecent().args[0]).toBe(
      '/topic/sessions/session-2/seats',
    );

    await flushAvailability('session-2', availabilityB);
    expect(seatStatus('seat-1')).toBe('HELD');
    expect(seatStatus('seat-2')).toBe('AVAILABLE');
    expect(seatDomStatus('seat-1')).toBe('HELD');

    // No NEW session-1 traffic after the switch: the reconnect refreshed B
    // only (match() returns pending requests; the stale A ones are flushed).
    expect(httpMock.match('/api/event-sessions/session-1/seats/availability').length)
      .withContext('reconnect must not re-request session-1 availability')
      .toBe(0);
  });
});
