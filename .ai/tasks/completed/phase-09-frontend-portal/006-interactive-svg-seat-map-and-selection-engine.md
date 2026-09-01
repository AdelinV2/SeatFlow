# TASK-P09-006: Interactive SVG Seat Map & Real-Time Selection Engine

## 1. Task Metadata
- **Task ID:** `TASK-P09-006`
- **Git Branch:** `feat/p09-006-seat-map-engine`
- **Target Module:** `frontend/src/app/features/booking/`
- **Phase:** `Phase 09 - Frontend Portal`
- **Related Specs:** `.ai/architecture/07-frontend-specification.md` (Section 4.5), `.ai/architecture/06-api-contracts.md` (Section 2.3, 2.4), `frontend/AGENTS.md` (Section 4.1)
- **Related ADRs:** `ADR-001` (Hybrid Guest Checkout)
- **Status:** `COMPLETED`

---

## 2. Objective & Invariants
Implement the interactive SVG seat map and selection engine on route `/events/:id/seats`. This includes pan, pinch-to-zoom, section isolation, SVG seat rendering with status-dependent visual encoding, elastic spring bounce selection animations, strict client-side **max 10 seats** selection limit, real-time STOMP synchronization with conflict deselection alerts, and a floating bottom action dock for instant hold creation.

### Critical Invariants to Enforce:
- [x] **Maximum 10 Seats Selection Invariant:** Client-side selection must strictly cap selected seats at 10. Attempting to select an 11th seat must be blocked and display a warning toast (`"Maximum 10 seats allowed per reservation."`).
- [x] **Elastic Spring Selection Physics:** Selecting/deselecting a seat must trigger an elastic spring bounce keyframe animation (`scale-125` -> `scale-100`) and a glowing Indigo focus halo.
- [x] **Real-Time Conflict Eviction:** If an active locally-selected seat is held/purchased by another peer via WebSocket `SeatStatusUpdated`, immediately deselect the seat, decrement totals, and display an alert toast.
- [x] **Authoritative Hold Dispatch:** The "Hold Seats & Proceed" CTA invokes `POST /api/reservations` with `seatIds` and an idempotency key, transitioning the user directly to `/checkout/:reservationId`.
- [x] **Pan & Zoom Viewport Controls:** Support fluid panning (mouse drag / touch drag), zoom slider (+ / - buttons and mouse wheel), and a "Reset View" button.

---

## 3. Exact File Inventory
- `[NEW]` `frontend/src/app/features/booking/seat-map/seat-map.component.ts`
- `[NEW]` `frontend/src/app/features/booking/seat-map/seat-map.component.html`
- `[NEW]` `frontend/src/app/features/booking/seat-map/seat-map.component.scss`
- `[NEW]` `frontend/src/app/features/booking/seat-selection/seat-selection.component.ts`
- `[NEW]` `frontend/src/app/features/booking/seat-selection/seat-selection.component.html`
- `[NEW]` `frontend/src/app/features/booking/seat-selection/seat-selection.component.scss`
- `[NEW]` `frontend/src/app/features/booking/selection-dock/selection-dock.component.ts`
- `[NEW]` `frontend/src/app/features/booking/selection-dock/selection-dock.component.html`
- `[NEW]` `frontend/src/app/features/booking/selection-dock/selection-dock.component.scss`
- `[NEW]` `frontend/src/app/services/reservation-api.service.ts`
- `[NEW]` `frontend/src/app/features/booking/seat-map/seat-map.component.spec.ts`
- `[NEW]` `frontend/src/app/features/booking/seat-selection/seat-selection.component.spec.ts`
- `[MODIFY]` `frontend/src/app/app.routes.ts`

---

## 4. Technical Specifications & Contracts

### 4.1 Reservation API Service (`src/app/services/reservation-api.service.ts`)

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface CreateReservationRequest {
  eventId: string;
  customerEmail?: string;
  seatIds: string[];
  seatPrices: number[];
  idempotencyKey: string;
}

export interface ReservationSeatDetail {
  seatId: string;
  rowNumber: string;
  seatNumber: number;
  price: number;
}

export interface ReservationResponse {
  id: string;
  eventId: string;
  customerEmail?: string;
  customerName?: string;
  status: 'PENDING' | 'CONFIRMED' | 'CANCELLED' | 'EXPIRED';
  expiresAt: string;
  totalAmount: number;
  seats: ReservationSeatDetail[];
}

@Injectable({ providedIn: 'root' })
export class ReservationApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/reservations';

  createReservation(request: CreateReservationRequest): Observable<ReservationResponse> {
    return this.http.post<ReservationResponse>(this.baseUrl, request);
  }

  getReservation(reservationId: string): Observable<ReservationResponse> {
    return this.http.get<ReservationResponse>(`${this.baseUrl}/${reservationId}`);
  }

  cancelReservation(reservationId: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/${reservationId}/cancel`, {});
  }
}
```

### 4.2 Seat Map Component (`src/app/features/booking/seat-map/`)

```typescript
import { Component, ChangeDetectionStrategy, input, output, signal, computed, inject, ElementRef, viewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Seat } from '../../../models/seat.model';

@Component({
  selector: 'app-seat-map',
  standalone: true,
  imports: [CommonModule, MatTooltipModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './seat-map.component.html',
  styleUrl: './seat-map.component.scss',
})
export class SeatMapComponent {
  private readonly snackBar = inject(MatSnackBar);

  readonly seats = input.required<Seat[]>();
  readonly maxSeats = input<number>(10);
  readonly selectedSeatIds = input<Set<string>>(new Set());
  readonly seatToggled = output<Seat>();

  // Pan & Zoom state
  readonly zoomLevel = signal<number>(1);
  readonly panX = signal<number>(0);
  readonly panY = signal<number>(0);
  readonly isDragging = signal<boolean>(false);

  private dragStartX = 0;
  private dragStartY = 0;

  readonly transformMatrix = computed(() =>
    `translate(${this.panX()}px, ${this.panY()}px) scale(${this.zoomLevel()})`
  );

  handleSeatClick(seat: Seat): void {
    if (!seat.isActive || seat.status === 'SOLD' || seat.status === 'RESERVED' || seat.status === 'DISABLED') {
      return;
    }
    if (seat.status === 'HELD' && !this.selectedSeatIds().has(seat.id)) {
      this.snackBar.open('This seat is currently held by another customer.', 'Close', { duration: 3000 });
      return;
    }

    const isCurrentlySelected = this.selectedSeatIds().has(seat.id);
    if (!isCurrentlySelected && this.selectedSeatIds().size >= this.maxSeats()) {
      this.snackBar.open(`Maximum ${this.maxSeats()} seats allowed per reservation.`, 'Close', {
        duration: 3500,
        panelClass: 'snack-warning',
      });
      return;
    }

    this.seatToggled.emit(seat);
  }

  zoomIn(): void {
    this.zoomLevel.update((z) => Math.min(2.5, z + 0.2));
  }

  zoomOut(): void {
    this.zoomLevel.update((z) => Math.max(0.5, z - 0.2));
  }

  resetView(): void {
    this.zoomLevel.set(1);
    this.panX.set(0);
    this.panY.set(0);
  }

  startDrag(event: MouseEvent): void {
    if ((event.target as Element).closest('.seat-node, circle, rect')) return;
    this.isDragging.set(true);
    this.dragStartX = event.clientX - this.panX();
    this.dragStartY = event.clientY - this.panY();
  }

  onDrag(event: MouseEvent): void {
    if (!this.isDragging()) return;
    this.panX.set(event.clientX - this.dragStartX);
    this.panY.set(event.clientY - this.dragStartY);
  }

  stopDrag(): void {
    this.isDragging.set(false);
  }
}
```

### 4.3 Floating Selection Dock Component (`src/app/features/booking/selection-dock/`)

```typescript
import { Component, ChangeDetectionStrategy, input, output, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TactileButtonComponent } from '../../../shared/components/tactile-button/tactile-button.component';
import { CurrencyFormatPipe } from '../../../shared/pipes/currency-format.pipe';
import { Seat } from '../../../models/seat.model';

@Component({
  selector: 'app-selection-dock',
  standalone: true,
  imports: [CommonModule, TactileButtonComponent, CurrencyFormatPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './selection-dock.component.html',
  styleUrl: './selection-dock.component.scss',
})
export class SelectionDockComponent {
  readonly selectedSeats = input.required<Seat[]>();
  readonly maxSeats = input<number>(10);
  readonly isCreatingHold = input<boolean>(false);
  readonly seatRemoved = output<Seat>();
  readonly checkoutTriggered = output<void>();

  readonly count = computed(() => this.selectedSeats().length);
  readonly totalPrice = computed(() =>
    this.selectedSeats().reduce((sum, seat) => sum + (seat.price || 0), 0)
  );
}
```

```html
@if (count() > 0) {
  <div class="fixed bottom-6 left-1/2 -translate-x-1/2 z-50 w-[92%] max-w-3xl p-4 bg-[var(--color-surface-elevated)]/90 backdrop-blur-xl border border-[var(--color-border)] rounded-2xl shadow-2xl transition-all duration-300 animate-in fade-in slide-in-from-bottom-6">
    <div class="flex flex-col sm:flex-row items-center justify-between gap-4">
      <div class="flex items-center gap-3 overflow-x-auto max-w-full pb-1 sm:pb-0">
        <span class="px-2.5 py-1 text-xs font-bold rounded-lg bg-indigo-500/10 text-indigo-400 border border-indigo-500/20 whitespace-nowrap">
          {{ count() }} / {{ maxSeats() }} Seats
        </span>
        <div class="flex items-center gap-1.5 flex-nowrap">
          @for (seat of selectedSeats(); track seat.id) {
            <span class="inline-flex items-center gap-1 px-2 py-1 text-xs font-mono font-medium rounded-md bg-slate-800 text-slate-200 border border-slate-700 whitespace-nowrap">
              {{ seat.rowLabel }}-{{ seat.seatNumber }}
              <button (click)="seatRemoved.emit(seat)" class="text-slate-400 hover:text-rose-400 font-bold ml-1 cursor-pointer">×</button>
            </span>
          }
        </div>
      </div>

      <div class="flex items-center gap-4 w-full sm:w-auto justify-between sm:justify-end">
        <div class="text-right">
          <span class="block text-[11px] text-muted uppercase tracking-wider">Total Amount</span>
          <span class="text-lg font-bold font-mono text-indigo-400">{{ totalPrice() | sfCurrency }}</span>
        </div>
        <app-tactile-button
          variant="primary"
          size="md"
          [loading]="isCreatingHold()"
          (clicked)="checkoutTriggered.emit()"
        >
          Hold Seats & Checkout →
        </app-tactile-button>
      </div>
    </div>
  </div>
}
```

---

## 5. Step-by-Step Implementation Sequence
1. **Implement Reservation API Service:**
   - Create `src/app/services/reservation-api.service.ts` exposing `createReservation`, `getReservation`, `cancelReservation`.
2. **Build SeatMapComponent (SVG Engine):**
   - Render SVG canvas containing stage banner, section groupings, and individual seat nodes (`<circle>` / `<rect>`).
   - Implement mouse drag pan and zoom transform matrix.
   - Implement seat selection toggle with elastic spring keyframe animation.
   - Apply tooltip on hover with price and location.
3. **Build SelectionDockComponent:**
   - Render floating dock displaying selected seat pills, total sum (`sfCurrency`), removal buttons, and "Hold Seats & Checkout" CTA.
4. **Implement SeatSelectionComponent (Container Page):**
   - Load event details and venue layout (`GET /api/events/:id/seat-map`), flattening nested `sections[].seats[]` into `Seat[]` with section tier pricing.
   - Connect to `WebSocketService` on init (`connectForEvent(eventId, onConflict, selectedSeatIds)`).
   - Maintain `selectedSeats` Signal set with strict $\le 10$ limit check.
   - On checkout trigger, generate unique idempotency key, call `POST /api/reservations` with `seatIds` and corresponding `seatPrices`, and navigate to `/checkout/${response.id}`.
5. **Develop Unit Tests:**
   - Verify selection rejects 11th seat.
   - Verify total price computation.
   - Verify STOMP conflict ejection.

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
```
- [x] SVG Seat map renders layout accurately with pan and zoom interactions.
- [x] Seat selection enforces 10-seat limit strictly on the client.
- [x] Real-time updates reflect seat state changes with conflict alert handling.
- [x] Hold creation successfully posts to `/api/reservations` and navigates to checkout.
- [x] Unit tests pass with 100% success.
- [x] Task file is moved to `.ai/tasks/completed/phase-09-frontend-portal/006-interactive-svg-seat-map-and-selection-engine.md`.
