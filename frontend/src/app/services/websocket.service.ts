import { inject, Injectable, InjectionToken, OnDestroy, signal } from '@angular/core';
import { Client, ReconnectionTimeMode, StompConfig, StompSubscription } from '@stomp/stompjs';
// @ts-expect-error sockjs-client does not publish declarations for its browser bundle.
import SockJS from 'sockjs-client/dist/sockjs.js';
import { AuthService } from '../core/auth/auth.service';
import { SeatStatusUpdate, SeatStatusUpdateMessage } from '../models/seat.model';
import { SeatStateService } from './seat-state.service';

export type ConnectionStatus = 'DISCONNECTED' | 'CONNECTING' | 'CONNECTED' | 'RECONNECTING';

export type StompClientFactory = (config: StompConfig) => Client;
export type SockJsFactory = (
  url: string,
) => ReturnType<NonNullable<StompConfig['webSocketFactory']>>;

export const STOMP_CLIENT_FACTORY = new InjectionToken<StompClientFactory>('STOMP_CLIENT_FACTORY', {
  providedIn: 'root',
  factory: () => (config) => new Client(config),
});

export const SOCKJS_FACTORY = new InjectionToken<SockJsFactory>('SOCKJS_FACTORY', {
  providedIn: 'root',
  factory: () => (url) => new SockJS(url),
});

@Injectable({ providedIn: 'root' })
export class WebSocketService implements OnDestroy {
  private readonly seatStateService = inject(SeatStateService);
  private readonly authService = inject(AuthService);
  private readonly createClient = inject(STOMP_CLIENT_FACTORY);
  private readonly createSockJs = inject(SOCKJS_FACTORY);

  private client: Client | null = null;
  private currentSubscription: StompSubscription | null = null;
  private activeEventId: string | null = null;
  private pendingDeactivation: Promise<void> | null = null;
  private onSeatConflict?: ((seatId: string) => void) | null;
  private selectedSeatsRef?: (() => Set<string>) | null;

  readonly connectionStatus = signal<ConnectionStatus>('DISCONNECTED');
  readonly isConnected = signal(false);
  readonly lastSeatUpdate = signal<SeatStatusUpdate | null>(null);

  connectForEvent(
    eventId: string,
    onSeatConflict?: (seatId: string) => void,
    selectedSeatsRef?: () => Set<string>,
  ): void {
    this.onSeatConflict = onSeatConflict;
    this.selectedSeatsRef = selectedSeatsRef;

    if (this.activeEventId === eventId && this.client?.active) {
      return;
    }

    const priorDeactivation = this.teardownCurrentClient();
    this.activeEventId = eventId;
    this.connectionStatus.set('CONNECTING');

    let client: Client;
    client = this.createClient({
      webSocketFactory: () => this.createSockJs('/ws'),
      reconnectDelay: 5000,
      maxReconnectDelay: 60000,
      reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
      beforeConnect: (connectingClient) => {
        const token = this.authService.getToken();
        connectingClient.connectHeaders = token ? { Authorization: `Bearer ${token}` } : {};
      },
      onConnect: () => {
        if (this.client !== client || this.activeEventId !== eventId) {
          return;
        }

        this.connectionStatus.set('CONNECTED');
        this.isConnected.set(true);
        this.currentSubscription = client.subscribe(`/topic/events/${eventId}/seats`, (message) => {
          if (!message.body || this.client !== client) {
            return;
          }

          try {
            const messageUpdate = JSON.parse(message.body) as
              SeatStatusUpdate | SeatStatusUpdateMessage;
            const isBatchUpdate =
              'seatIds' in messageUpdate && Array.isArray(messageUpdate.seatIds);
            const seatIds: string[] = isBatchUpdate
              ? (messageUpdate.seatIds ?? []).filter(
                  (id): id is string => typeof id === 'string' && Boolean(id),
                )
              : typeof (messageUpdate as SeatStatusUpdate).seatId === 'string' &&
                  (messageUpdate as SeatStatusUpdate).seatId
                ? [(messageUpdate as SeatStatusUpdate).seatId]
                : [];

            if (seatIds.length === 0) {
              return;
            }

            const selectedSeatIds = this.selectedSeatsRef?.();

            for (const seatId of seatIds) {
              const expiresAt = isBatchUpdate
                ? (messageUpdate as SeatStatusUpdateMessage).holdExpiresAt
                : (messageUpdate as SeatStatusUpdate).expiresAt;
              const update: SeatStatusUpdate = {
                eventId: messageUpdate.eventId,
                seatId,
                status: messageUpdate.status,
                timestamp: messageUpdate.timestamp,
                ...(expiresAt !== undefined ? { expiresAt } : {}),
              };
              this.lastSeatUpdate.set(update);
              this.seatStateService.updateSeatStatus(seatId, update.status);

              if (selectedSeatIds?.has(seatId) && update.status !== 'AVAILABLE') {
                this.onSeatConflict?.(seatId);
              }
            }
          } catch (error: unknown) {
            console.error('Failed to process seat status update:', error);
          }
        });

        this.seatStateService.reconcileAvailability(
          eventId,
          this.selectedSeatsRef?.(),
          this.onSeatConflict ?? undefined,
        );
      },
      onDisconnect: () => {
        if (this.client === client) {
          this.setDisconnected();
        }
      },
      onStompError: (frame) => {
        console.error('STOMP protocol error:', frame.headers['message'], frame.body);
        if (this.client === client) {
          this.connectionStatus.set('RECONNECTING');
          this.isConnected.set(false);
        }
      },
      onWebSocketClose: () => {
        if (this.client === client) {
          this.currentSubscription = null;
          if (client.active) {
            this.connectionStatus.set('RECONNECTING');
            this.isConnected.set(false);
          }
        }
      },
    });

    this.client = client;
    if (priorDeactivation) {
      void priorDeactivation.then(() => {
        if (this.client === client && this.activeEventId === eventId) {
          client.activate();
        }
      });
    } else {
      client.activate();
    }
  }

  disconnect(): void {
    this.onSeatConflict = undefined;
    this.selectedSeatsRef = undefined;
    this.teardownCurrentClient();
    this.setDisconnected();
  }

  ngOnDestroy(): void {
    this.disconnect();
  }

  private teardownCurrentClient(): Promise<void> | null {
    try {
      this.currentSubscription?.unsubscribe();
    } catch (error: unknown) {
      console.warn('Failed to unsubscribe from the seat topic during disconnect:', error);
    }
    this.currentSubscription = null;

    const client = this.client;
    this.client = null;
    this.activeEventId = null;
    if (client) {
      let deactivation: Promise<void>;
      try {
        deactivation = this.pendingDeactivation
          ? this.pendingDeactivation.then(() => client.deactivate())
          : client.deactivate();
      } catch (error: unknown) {
        deactivation = Promise.reject(error);
      }
      deactivation = deactivation.catch((error: unknown) => {
        console.error('Failed to deactivate the STOMP client:', error);
      });
      this.pendingDeactivation = deactivation;
      void deactivation.finally(() => {
        if (this.pendingDeactivation === deactivation) {
          this.pendingDeactivation = null;
        }
      });
      return deactivation;
    }

    return this.pendingDeactivation;
  }

  private setDisconnected(): void {
    this.isConnected.set(false);
    this.connectionStatus.set('DISCONNECTED');
  }
}
