import { TestBed } from '@angular/core/testing';
import {
  Client,
  IMessage,
  ReconnectionTimeMode,
  StompConfig,
  StompHeaders,
  StompSubscription,
} from '@stomp/stompjs';
import { AuthService } from '../core/auth/auth.service';
import { SeatStateService } from './seat-state.service';
import { SOCKJS_FACTORY, STOMP_CLIENT_FACTORY, WebSocketService } from './websocket.service';

class FakeStompClient {
  active = false;
  connectHeaders: StompHeaders = {};
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

describe('WebSocketService', () => {
  let service: WebSocketService;
  let authService: jasmine.SpyObj<AuthService>;
  let seatStateService: jasmine.SpyObj<SeatStateService>;
  let clients: FakeStompClient[];
  let configs: StompConfig[];
  let sockJsFactory: jasmine.Spy;

  beforeEach(() => {
    clients = [];
    configs = [];
    sockJsFactory = jasmine.createSpy('SockJsFactory').and.returnValue({});
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['getToken']);
    seatStateService = jasmine.createSpyObj<SeatStateService>('SeatStateService', [
      'reconcileAvailability',
      'updateSeatStatus',
    ]);

    TestBed.configureTestingModule({
      providers: [
        WebSocketService,
        { provide: AuthService, useValue: authService },
        { provide: SeatStateService, useValue: seatStateService },
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
      ],
    });

    service = TestBed.inject(WebSocketService);
  });

  it('should start disconnected and transition to connecting on activation', () => {
    expect(service.connectionStatus()).toBe('DISCONNECTED');
    expect(service.isConnected()).toBeFalse();

    service.connectForEvent('event-1');

    expect(service.connectionStatus()).toBe('CONNECTING');
    expect(clients[0].activate).toHaveBeenCalledTimes(1);
  });

  it('should configure SockJS, heartbeats, and capped exponential reconnection', () => {
    service.connectForEvent('event-1');

    expect(configs[0].webSocketFactory).toEqual(jasmine.any(Function));
    configs[0].webSocketFactory?.();
    expect(sockJsFactory).toHaveBeenCalledOnceWith('/ws');
    expect(configs[0].reconnectDelay).toBe(5000);
    expect(configs[0].maxReconnectDelay).toBe(60000);
    expect(configs[0].reconnectTimeMode).toBe(ReconnectionTimeMode.EXPONENTIAL);
    expect(configs[0].heartbeatIncoming).toBe(4000);
    expect(configs[0].heartbeatOutgoing).toBe(4000);
  });

  it('should inject the current JWT before every connection attempt', async () => {
    authService.getToken.and.returnValues('first-token', 'refreshed-token');
    service.connectForEvent('event-1');

    await configs[0].beforeConnect?.(clients[0] as unknown as Client);
    expect(clients[0].connectHeaders).toEqual({ Authorization: 'Bearer first-token' });

    await configs[0].beforeConnect?.(clients[0] as unknown as Client);
    expect(clients[0].connectHeaders).toEqual({ Authorization: 'Bearer refreshed-token' });
  });

  it('should omit the authorization header for an unauthenticated connection', async () => {
    authService.getToken.and.returnValue(null);
    service.connectForEvent('event-1');

    await configs[0].beforeConnect?.(clients[0] as unknown as Client);

    expect(clients[0].connectHeaders).toEqual({});
  });

  it('should reconcile and subscribe on every successful connection', () => {
    const lifecycleCalls: string[] = [];
    const selectedSeats = new Set(['seat-1']);
    const selectedSeatsRef = jasmine.createSpy('selectedSeatsRef').and.returnValue(selectedSeats);
    const onConflict = jasmine.createSpy('onConflict');
    service.connectForEvent('event-1', onConflict, selectedSeatsRef);
    clients[0].subscribe.and.callFake(
      (_destination: string, callback: (message: IMessage) => void) => {
        lifecycleCalls.push('subscribe');
        clients[0].messageCallback = callback;
        return clients[0].subscription;
      },
    );
    seatStateService.reconcileAvailability.and.callFake(() => {
      lifecycleCalls.push('reconcile');
    });

    configs[0].onConnect?.({} as never);
    configs[0].onConnect?.({} as never);

    expect(service.connectionStatus()).toBe('CONNECTED');
    expect(service.isConnected()).toBeTrue();
    expect(seatStateService.reconcileAvailability).toHaveBeenCalledTimes(2);
    expect(seatStateService.reconcileAvailability).toHaveBeenCalledWith(
      'event-1',
      selectedSeats,
      onConflict,
    );
    expect(clients[0].subscribe).toHaveBeenCalledTimes(2);
    expect(clients[0].subscribe.calls.mostRecent().args[0]).toBe('/topic/events/event-1/seats');
    expect(lifecycleCalls).toEqual(['subscribe', 'reconcile', 'subscribe', 'reconcile']);
  });

  it('should propagate incoming seat updates and report selected-seat conflicts', () => {
    const onConflict = jasmine.createSpy('onConflict');
    service.connectForEvent('event-1', onConflict, () => new Set(['seat-1']));
    configs[0].onConnect?.({} as never);

    clients[0].messageCallback?.({
      body: JSON.stringify({
        eventId: 'event-1',
        seatId: 'seat-1',
        status: 'HELD',
        timestamp: '2026-08-28T10:00:00Z',
      }),
    } as IMessage);

    expect(service.lastSeatUpdate()).toEqual({
      eventId: 'event-1',
      seatId: 'seat-1',
      status: 'HELD',
      timestamp: '2026-08-28T10:00:00Z',
    });
    expect(seatStateService.updateSeatStatus).toHaveBeenCalledOnceWith('seat-1', 'HELD');
    expect(onConflict).toHaveBeenCalledOnceWith('seat-1');
  });

  it('should propagate every seat in a backend batched status update', () => {
    service.connectForEvent('event-1');
    configs[0].onConnect?.({} as never);

    clients[0].messageCallback?.({
      body: JSON.stringify({
        eventId: 'event-1',
        seatIds: ['seat-1', 'seat-2'],
        status: 'HELD',
        timestamp: '2026-08-28T10:00:00Z',
        holdExpiresAt: '2026-08-28T10:15:00Z',
      }),
    } as IMessage);

    expect(seatStateService.updateSeatStatus.calls.allArgs()).toEqual([
      ['seat-1', 'HELD'],
      ['seat-2', 'HELD'],
    ]);
    expect(service.lastSeatUpdate()).toEqual({
      eventId: 'event-1',
      seatId: 'seat-2',
      status: 'HELD',
      timestamp: '2026-08-28T10:00:00Z',
      expiresAt: '2026-08-28T10:15:00Z',
    });
  });

  it('should ignore messages with an empty body', () => {
    service.connectForEvent('event-1');
    configs[0].onConnect?.({} as never);

    clients[0].messageCallback?.({ body: '' } as IMessage);

    expect(service.lastSeatUpdate()).toBeNull();
    expect(seatStateService.updateSeatStatus).not.toHaveBeenCalled();
  });

  it('should isolate malformed messages without changing seat state', () => {
    const consoleError = spyOn(console, 'error');
    service.connectForEvent('event-1');
    configs[0].onConnect?.({} as never);

    clients[0].messageCallback?.({ body: '{invalid-json' } as IMessage);

    expect(service.lastSeatUpdate()).toBeNull();
    expect(seatStateService.updateSeatStatus).not.toHaveBeenCalled();
    expect(consoleError).toHaveBeenCalled();
  });

  it('should expose reconnecting state after an unexpected WebSocket close', () => {
    service.connectForEvent('event-1');
    configs[0].onConnect?.({} as never);

    configs[0].onWebSocketClose?.({} as CloseEvent);

    expect(service.connectionStatus()).toBe('RECONNECTING');
    expect(service.isConnected()).toBeFalse();
  });

  it('should not unsubscribe a stale topic after the socket has already closed', () => {
    service.connectForEvent('event-1');
    configs[0].onConnect?.({} as never);
    configs[0].onWebSocketClose?.({} as CloseEvent);

    service.disconnect();

    expect(clients[0].subscription.unsubscribe).not.toHaveBeenCalled();
    expect(clients[0].deactivate).toHaveBeenCalledTimes(1);
  });

  it('should expose reconnecting state after a STOMP protocol error', () => {
    const consoleError = spyOn(console, 'error');
    service.connectForEvent('event-1');

    configs[0].onStompError?.({ headers: { message: 'Broker error' }, body: 'Failure' } as never);

    expect(service.connectionStatus()).toBe('RECONNECTING');
    expect(service.isConnected()).toBeFalse();
    expect(consoleError).toHaveBeenCalled();
  });

  it('should not create a duplicate active connection for the same event', () => {
    service.connectForEvent('event-1');

    service.connectForEvent('event-1');

    expect(clients.length).toBe(1);
    expect(clients[0].activate).toHaveBeenCalledTimes(1);
  });

  it('should unsubscribe, deactivate, and reset state on disconnect', () => {
    service.connectForEvent('event-1');
    configs[0].onConnect?.({} as never);

    service.disconnect();

    expect(clients[0].subscription.unsubscribe).toHaveBeenCalledTimes(1);
    expect(clients[0].deactivate).toHaveBeenCalledTimes(1);
    expect(service.connectionStatus()).toBe('DISCONNECTED');
    expect(service.isConnected()).toBeFalse();
  });

  it('should tear down the prior event before connecting to another event', async () => {
    service.connectForEvent('event-1');
    configs[0].onConnect?.({} as never);

    service.connectForEvent('event-2');
    await Promise.resolve();
    await Promise.resolve();

    expect(clients[0].subscription.unsubscribe).toHaveBeenCalledTimes(1);
    expect(clients[0].deactivate).toHaveBeenCalledTimes(1);
    expect(clients.length).toBe(2);
    expect(clients[1].activate).toHaveBeenCalledTimes(1);
  });

  it('should update conflict callbacks and selection ref if re-called with the same event while active', () => {
    const initialConflict = jasmine.createSpy('initialConflict');
    const updatedConflict = jasmine.createSpy('updatedConflict');
    const initialSeats = () => new Set(['seat-1']);
    const updatedSeats = () => new Set(['seat-2']);

    service.connectForEvent('event-1', initialConflict, initialSeats);
    configs[0].onConnect?.({} as never);

    // Call again with updated callbacks
    service.connectForEvent('event-1', updatedConflict, updatedSeats);

    // Should not re-activate or duplicate client
    expect(clients.length).toBe(1);

    // Simulate incoming update for seat-2
    clients[0].messageCallback?.({
      body: JSON.stringify({
        eventId: 'event-1',
        seatId: 'seat-2',
        status: 'HELD',
        timestamp: '2026-08-28T10:00:00Z',
      }),
    } as IMessage);

    expect(initialConflict).not.toHaveBeenCalled();
    expect(updatedConflict).toHaveBeenCalledOnceWith('seat-2');
  });

  it('should ignore messages with missing or non-string seat identifiers', () => {
    service.connectForEvent('event-1');
    configs[0].onConnect?.({} as never);

    clients[0].messageCallback?.({
      body: JSON.stringify({
        eventId: 'event-1',
        status: 'HELD',
        timestamp: '2026-08-28T10:00:00Z',
      }),
    } as IMessage);

    expect(seatStateService.updateSeatStatus).not.toHaveBeenCalled();
  });

  it('should disconnect and cleanup when ngOnDestroy is called', () => {
    service.connectForEvent('event-1');
    configs[0].onConnect?.({} as never);

    service.ngOnDestroy();

    expect(clients[0].subscription.unsubscribe).toHaveBeenCalledTimes(1);
    expect(clients[0].deactivate).toHaveBeenCalledTimes(1);
    expect(service.connectionStatus()).toBe('DISCONNECTED');
    expect(service.isConnected()).toBeFalse();
  });
});
