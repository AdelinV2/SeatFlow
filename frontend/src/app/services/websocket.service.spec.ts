import { TestBed } from '@angular/core/testing';
import { Client, IMessage, StompConfig } from '@stomp/stompjs';
import { AuthService } from '../core/auth/auth.service';
import { SeatStateService } from './seat-state.service';
import {
  SOCKJS_FACTORY,
  STOMP_CLIENT_FACTORY,
  WebSocketService,
} from './websocket.service';

describe('WebSocketService', () => {
  let service: WebSocketService;
  let authServiceSpy: jasmine.SpyObj<AuthService>;
  let seatStateServiceSpy: jasmine.SpyObj<SeatStateService>;
  let mockClient: jasmine.SpyObj<Client>;
  let clientConfig: StompConfig | undefined;
  let messageHandler: ((message: IMessage) => void) | undefined;

  beforeEach(() => {
    authServiceSpy = jasmine.createSpyObj<AuthService>('AuthService', ['getToken']);
    authServiceSpy.getToken.and.returnValue('mock-token');

    seatStateServiceSpy = jasmine.createSpyObj<SeatStateService>('SeatStateService', [
      'updateSeatStatus',
      'reconcileAvailability',
    ]);

    mockClient = jasmine.createSpyObj<Client>('Client', [
      'activate',
      'deactivate',
      'subscribe',
    ]);
    mockClient.subscribe.and.callFake((topic: string, callback: (msg: IMessage) => void) => {
      messageHandler = callback;
      return { id: 'sub-1', unsubscribe: jasmine.createSpy('unsubscribe') };
    });
    mockClient.deactivate.and.returnValue(Promise.resolve());

    TestBed.configureTestingModule({
      providers: [
        WebSocketService,
        { provide: AuthService, useValue: authServiceSpy },
        { provide: SeatStateService, useValue: seatStateServiceSpy },
        {
          provide: STOMP_CLIENT_FACTORY,
          useValue: (config: StompConfig) => {
            clientConfig = config;
            return mockClient;
          },
        },
        {
          provide: SOCKJS_FACTORY,
          useValue: () => ({} as any),
        },
      ],
    });

    service = TestBed.inject(WebSocketService);
  });

  afterEach(() => {
    service.disconnect();
  });

  it('should activate client and subscribe on connect', () => {
    const onConflictSpy = jasmine.createSpy('onConflict');
    const selectedSeatsRef = () => new Set(['s-1']);

    service.connectForEvent('ev-100', onConflictSpy, selectedSeatsRef);

    expect(mockClient.activate).toHaveBeenCalled();
    expect(service.connectionStatus()).toBe('CONNECTING');

    // Simulate onConnect callback
    clientConfig?.onConnect?.({} as any);

    expect(service.isConnected()).toBeTrue();
    expect(service.connectionStatus()).toBe('CONNECTED');
    expect(mockClient.subscribe).toHaveBeenCalledWith(
      '/topic/events/ev-100/seats',
      jasmine.any(Function),
    );
    expect(seatStateServiceSpy.reconcileAvailability).toHaveBeenCalled();
  });

  it('should process batch seat updates and trigger conflict for selected seats', () => {
    const onConflictSpy = jasmine.createSpy('onConflict');
    const selectedSeatsRef = () => new Set(['s-1', 's-2']);

    service.connectForEvent('ev-100', onConflictSpy, selectedSeatsRef);
    clientConfig?.onConnect?.({} as any);

    const batchMessage = {
      body: JSON.stringify({
        eventId: 'ev-100',
        seatIds: ['s-1', 's-3'],
        status: 'HELD',
        timestamp: '2026-08-28T12:00:00Z',
        holdExpiresAt: '2026-08-28T12:15:00Z',
      }),
      headers: {},
      ack: jasmine.createSpy('ack'),
      nack: jasmine.createSpy('nack'),
      command: 'MESSAGE',
      isBinaryBody: false,
      binaryBody: new Uint8Array(),
    } as IMessage;

    messageHandler?.(batchMessage);

    expect(seatStateServiceSpy.updateSeatStatus).toHaveBeenCalledWith('s-1', 'HELD');
    expect(seatStateServiceSpy.updateSeatStatus).toHaveBeenCalledWith('s-3', 'HELD');
    expect(onConflictSpy).toHaveBeenCalledWith('s-1');
    expect(onConflictSpy).not.toHaveBeenCalledWith('s-3');
  });

  it('should disconnect and deactivate client on disconnect', () => {
    service.connectForEvent('ev-100');
    clientConfig?.onConnect?.({} as any);

    service.disconnect();

    expect(mockClient.deactivate).toHaveBeenCalled();
    expect(service.isConnected()).toBeFalse();
    expect(service.connectionStatus()).toBe('DISCONNECTED');
  });
});
