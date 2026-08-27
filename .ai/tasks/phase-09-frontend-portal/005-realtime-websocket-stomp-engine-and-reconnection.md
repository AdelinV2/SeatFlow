# TASK-P09-005: Real-Time WebSocket STOMP Engine & Authoritative State Reconciliation

## 1. Task Metadata
- **Task ID:** `TASK-P09-005`
- **Git Branch:** `feat/p09-005-websocket-realtime`
- **Target Module:** `frontend/src/app/services/`, `frontend/src/app/models/`
- **Phase:** `Phase 09 - Frontend Portal`
- **Related Specs:** `.ai/architecture/06-api-contracts.md` (Section 2.7), `.ai/architecture/07-frontend-specification.md` (Section 4.6), `frontend/AGENTS.md` (Section 4.2)
- **Related ADRs:** `None`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Build the real-time WebSocket communication engine using `@stomp/stompjs` and `sockjs-client`. The service connects to `/ws` (routed via API Gateway to `realtime-service`), subscribes to event seat topics (`/topic/events/{eventId}/seats`), propagates real-time `SeatStatusUpdate` messages into reactive Signal stores, manages exponential reconnection backoff, and performs **authoritative state reconciliation** by querying `GET /api/reservations/events/{eventId}/availability` upon every initial connection or reconnection.

### Critical Invariants to Enforce:
- [ ] **Authoritative State Reconciliation on Reconnect:** Whenever the STOMP connection is established or reconnected (`onConnect`), the client MUST re-fetch the full authoritative seat availability from `GET /api/reservations/events/{eventId}/availability` to prevent stale seat states due to dropped packets or network blips.
- [ ] **Reactive Signal State:** Connection status and live updates must be exposed as Signals (`connectionStatus`, `isConnected`, `lastSeatUpdate`).
- [ ] **Clean Topic Lifecycle:** Unsubscribing from `/topic/events/{eventId}/seats` when leaving the seat selection screen to prevent memory leaks and zombie network traffic.
- [ ] **Graceful SockJS Fallback:** Maintain compatibility across environments where native WebSockets are blocked by proxies using SockJS fallback.
- [ ] **JWT Header Injection:** STOMP connect headers must attach the current JWT Bearer token if user is authenticated.

---

## 3. Exact File Inventory
- `[NEW]` `frontend/src/app/models/seat.model.ts`
- `[NEW]` `frontend/src/app/services/seat-state.service.ts`
- `[NEW]` `frontend/src/app/services/websocket.service.ts`
- `[NEW]` `frontend/src/app/services/seat-state.service.spec.ts`
- `[NEW]` `frontend/src/app/services/websocket.service.spec.ts`

---

## 4. Technical Specifications & Contracts

### 4.1 Models (`src/app/models/seat.model.ts`)

```typescript
export type SeatStatus = 'AVAILABLE' | 'HELD' | 'SOLD' | 'RESERVED' | 'DISABLED';

export interface Seat {
  id: string;
  sectionId: string;
  sectionName?: string;
  rowLabel: string;
  seatNumber: number;
  gridX: number;
  gridY: number;
  price: number;
  status: SeatStatus;
  isActive: boolean;
}

export interface SeatStatusUpdate {
  eventId: string;
  seatId: string;
  status: SeatStatus;
  expiresAt?: string;
  timestamp: string;
}

export interface SeatAvailabilityResponse {
  eventId: string;
  seats: {
    seatId: string;
    status: SeatStatus;
  }[];
}
```

### 4.2 Seat State Store Service (`src/app/services/seat-state.service.ts`)

```typescript
import { Injectable, inject, signal, computed } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Seat, SeatStatus, SeatAvailabilityResponse } from '../models/seat.model';
import { MatSnackBar } from '@angular/material/snack-bar';

@Injectable({ providedIn: 'root' })
export class SeatStateService {
  private readonly http = inject(HttpClient);
  private readonly snackBar = inject(MatSnackBar);

  readonly seats = signal<Seat[]>([]);
  readonly isLoading = signal<boolean>(false);
  readonly currentEventId = signal<string | null>(null);

  readonly availableSeats = computed(() => this.seats().filter((s) => s.status === 'AVAILABLE' && s.isActive));
  readonly heldSeats = computed(() => this.seats().filter((s) => s.status === 'HELD'));
  readonly soldSeats = computed(() => this.seats().filter((s) => s.status === 'SOLD' || s.status === 'RESERVED'));

  setSeats(seats: Seat[], eventId: string): void {
    this.currentEventId.set(eventId);
    this.seats.set(seats);
  }

  updateSeatStatus(seatId: string, status: SeatStatus): void {
    this.seats.update((currentSeats) =>
      currentSeats.map((seat) => (seat.id === seatId ? { ...seat, status } : seat))
    );
  }

  // Authoritative reconciliation method
  reconcileAvailability(eventId: string, selectedSeatIds?: Set<string>, onConflict?: (conflictSeatId: string) => void): void {
    this.http.get<SeatAvailabilityResponse>(`/api/reservations/events/${eventId}/availability`).subscribe({
      next: (response) => {
        const availabilityMap = new Map(response.seats.map((s) => [s.seatId, s.status]));

        this.seats.update((currentSeats) =>
          currentSeats.map((seat) => {
            const serverStatus = availabilityMap.get(seat.id);
            if (serverStatus && serverStatus !== seat.status) {
              // Check if a locally selected seat was taken
              if (selectedSeatIds?.has(seat.id) && serverStatus !== 'AVAILABLE') {
                onConflict?.(seat.id);
                this.snackBar.open(
                  `Seat ${seat.rowLabel}-${seat.seatNumber} was just reserved by another user.`,
                  'Close',
                  { duration: 5000, panelClass: 'snack-warning' }
                );
              }
              return { ...seat, status: serverStatus };
            }
            return seat;
          })
        );
      },
      error: (err) => {
        console.error('Failed to reconcile seat availability:', err);
      },
    });
  }
}
```

### 4.3 WebSocket Service (`src/app/services/websocket.service.ts`)

```typescript
import { Injectable, inject, signal } from '@angular/core';
import { Client, StompSubscription } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { SeatStatusUpdate } from '../models/seat.model';
import { SeatStateService } from './seat-state.service';
import { AuthService } from '../core/auth/auth.service';

export type ConnectionStatus = 'DISCONNECTED' | 'CONNECTING' | 'CONNECTED' | 'RECONNECTING';

@Injectable({ providedIn: 'root' })
export class WebSocketService {
  private readonly seatStateService = inject(SeatStateService);
  private readonly authService = inject(AuthService);

  private client: Client | null = null;
  private currentSubscription: StompSubscription | null = null;
  private activeEventId: string | null = null;

  readonly connectionStatus = signal<ConnectionStatus>('DISCONNECTED');
  readonly isConnected = signal<boolean>(false);
  readonly lastSeatUpdate = signal<SeatStatusUpdate | null>(null);

  connectForEvent(
    eventId: string,
    onSeatConflict?: (seatId: string) => void,
    selectedSeatsRef?: () => Set<string>
  ): void {
    if (this.activeEventId === eventId && this.client?.active) {
      return;
    }

    this.disconnect();
    this.activeEventId = eventId;
    this.connectionStatus.set('CONNECTING');

    const token = this.authService.getToken();
    const headers: Record<string, string> = {};
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }

    this.client = new Client({
      webSocketFactory: () => new SockJS('/ws'),
      connectHeaders: headers,
      reconnectDelay: 5000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
      onConnect: () => {
        this.connectionStatus.set('CONNECTED');
        this.isConnected.set(true);

        // 1. Authoritative State Reconciliation on connect / reconnect
        this.seatStateService.reconcileAvailability(
          eventId,
          selectedSeatsRef ? selectedSeatsRef() : undefined,
          onSeatConflict
        );

        // 2. Subscribe to event-specific seat topic
        this.currentSubscription = this.client?.subscribe(
          `/topic/events/${eventId}/seats`,
          (message) => {
            if (message.body) {
              const update: SeatStatusUpdate = JSON.parse(message.body);
              this.lastSeatUpdate.set(update);
              this.seatStateService.updateSeatStatus(update.seatId, update.status);

              // If seat was locally selected and transitioned away from AVAILABLE, notify conflict
              if (selectedSeatsRef?.().has(update.seatId) && update.status !== 'AVAILABLE') {
                onSeatConflict?.(update.seatId);
              }
            }
          }
        ) ?? null;
      },
      onDisconnect: () => {
        this.connectionStatus.set('DISCONNECTED');
        this.isConnected.set(false);
      },
      onStompError: (frame) => {
        console.error('STOMP protocol error:', frame.headers['message'], frame.body);
        this.connectionStatus.set('RECONNECTING');
      },
      onWebSocketClose: () => {
        if (this.connectionStatus() === 'CONNECTED') {
          this.connectionStatus.set('RECONNECTING');
        }
        this.isConnected.set(false);
      },
    });

    this.client.activate();
  }

  disconnect(): void {
    if (this.currentSubscription) {
      this.currentSubscription.unsubscribe();
      this.currentSubscription = null;
    }
    if (this.client) {
      this.client.deactivate();
      this.client = null;
    }
    this.activeEventId = null;
    this.isConnected.set(false);
    this.connectionStatus.set('DISCONNECTED');
  }
}
```

---

## 5. Step-by-Step Implementation Sequence
1. **Define Seat Models:**
   - Create `src/app/models/seat.model.ts` with `Seat`, `SeatStatus`, `SeatStatusUpdate`, and `SeatAvailabilityResponse`.
2. **Implement SeatStateService:**
   - Create `SeatStateService` managing `seats` Signal array, status update methods, and `reconcileAvailability(eventId)`.
3. **Implement WebSocketService:**
   - Configure `@stomp/stompjs` `Client` with SockJS factory, JWT connect headers, 4s heartbeat, and 5s reconnect delay.
   - Implement `connectForEvent(eventId, onConflict, selectedSeatsRef)` with automatic subscription to `/topic/events/${eventId}/seats`.
   - Wire authoritative state reconciliation on `onConnect`.
   - Implement `disconnect()` for complete teardown.
4. **Develop Comprehensive Unit Tests:**
   - Mock STOMP Client and SockJS to test connection lifecycle states (`DISCONNECTED` -> `CONNECTING` -> `CONNECTED`).
   - Test incoming message parsing and status update propagation.
   - Test reconciliation call to `/api/reservations/events/{eventId}/availability` on connection.

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
```
- [ ] `WebSocketService` connects to `/ws` and subscribes to `/topic/events/{eventId}/seats`.
- [ ] Authoritative state reconciliation queries `/api/reservations/events/{eventId}/availability` upon connection.
- [ ] Deselection and conflict notification are triggered when a selected seat is held by a peer.
- [ ] `disconnect()` cleans up subscriptions and deactivates the client.
- [ ] All unit tests pass cleanly.
- [ ] Task file is moved to `.ai/tasks/completed/phase-09-frontend-portal/005-realtime-websocket-stomp-engine-and-reconnection.md`.
