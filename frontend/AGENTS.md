# SeatFlow — Frontend Engineering & Architecture Instructions

This file contains repository-level coding and implementation instructions for developers and agents working on the **SeatFlow Frontend** (`frontend/`).

The authoritative UI/UX contracts live in `.ai/architecture/07-frontend-specification.md` and the master specification is `.ai/SeatFlow-Architecture-and-Implementation-Spec.md`.

---

## 1. Frontend Stack Reference

Always check `frontend/package.json` for exact dependency versions. If you are unsure of any Angular 22 or Tailwind v4 API, consult official documentation before writing code.

| Technology | Version | Official Documentation |
|---|---|---|
| **Angular** | 22.x | https://angular.dev/ |
| **Angular Material** | 22.x | https://material.angular.io/ |
| **TailwindCSS** | v4.x | https://tailwindcss.com/docs |
| **@stomp/stompjs** | latest | https://stomp-js.github.io/stomp-websocket/ |
| **TypeScript** | 5.x | https://www.typescriptlang.org/docs/ |
| **RxJS** | 7.x | https://rxjs.dev/ |

> **Rule:** Angular 22 is Signal-first and Standalone-first. Never generate `NgModule` structures or use legacy `@Input()`/`@Output()` decorators or `BehaviorSubject` for component-level state.

---

## 2. Core Angular 22 Architectural Standards

1. **Standalone Components:** Every component, directive, and pipe is standalone (`standalone: true` or Angular 22 default).
2. **OnPush Change Detection:** `changeDetection: ChangeDetectionStrategy.OnPush` is mandatory on **100% of components**.
3. **Signal State Architecture:**
   - Use `signal<T>()` for local and shared mutable state.
   - Use `computed()` for derived state (filtering, totals, limit checks).
   - Use `effect()` strictly for logging, analytics, or manual DOM interactions.
   - Use `input.required<T>()` and `input<T>()` for component inputs.
   - Use `output<T>()` for component outputs.
   - Use `model<T>()` for two-way bindings.
4. **Dependency Injection:** Use the `inject()` function directly in property declarations — do not use constructor parameter injection in components.
5. **Route Lazy Loading:** All feature routes must use `loadComponent` or `loadChildren`.
6. **Functional Interceptors and Guards:** Use functional HTTP interceptors (`HttpInterceptorFn`) and functional route guards (`CanActivateFn`, `CanDeactivateFn`).

---

## 3. Frontend Implementation Workflow

Follow this strict sequence for every frontend task without skipping steps:

```
0. Mandatory Branch Checkout:
   git checkout -b feat/<task-id>-<description> develop
1. Read assigned task file from .ai/tasks/phase-09-frontend-portal/ or specific phase.
2. Ensure local .env file exists in frontend/ (copy from .env.example).
3. Implement in this exact sequence:
   a. TypeScript Interfaces/Models (src/app/models/)
   b. Services & State Stores (src/app/services/)
   c. Shared UI Components/Pipes/Directives (src/app/shared/)
   d. Feature Components with Signals & OnPush (src/app/features/)
   e. Route definitions & guards (src/app/app.routes.ts)
   f. Component & Service Unit Tests (src/app/.../*.spec.ts)
4. Run verification command:
   npm test -- --watch=false --browsers=ChromeHeadless
```

---

## 4. Directory Layout

```
frontend/
├── package.json
├── tsconfig.json
├── angular.json
├── .env.example                       # Version-controlled template (API URL, Entra client ID)
├── .env                               # Local environment overrides (strictly .gitignored)
└── src/
    ├── app/
    │   ├── core/                  # Core singletons, auth interceptor, error handling
    │   │   ├── auth/              # AuthService, OIDC integration, token storage
    │   │   ├── interceptors/      # auth.interceptor.ts, error.interceptor.ts, logging.interceptor.ts
    │   │   └── guards/            # auth.guard.ts, admin.guard.ts, pending-changes.guard.ts
    │   ├── shared/                # Reusable UI widgets, pipes, directives
    │   │   ├── components/        # seat-badge, countdown-timer, confirmation-modal, loading-spinner
    │   │   ├── pipes/             # currency-format.pipe.ts, date-format.pipe.ts
    │   │   └── directives/        # click-outside.directive.ts, auto-focus.directive.ts
    │   ├── features/              # Feature domains (lazy-loaded routes)
    │   │   ├── auth/              # Login, register, profile
    │   │   ├── events/            # Event catalog, event details, search/filters
    │   │   ├── booking/           # Interactive seat map, hold timer, checkout flow
    │   │   ├── tickets/           # Ticket QR code viewer, order history
    │   │   └── admin/             # Event management, venue seat layout designer
    │   ├── models/                # TypeScript models (matching backend DTOs)
    │   │   ├── event.model.ts
    │   │   ├── reservation.model.ts
    │   │   ├── seat.model.ts
    │   │   ├── ticket.model.ts
    │   │   └── api-error.model.ts
    │   ├── services/              # Cross-cutting domain API & WebSocket services
    │   │   ├── event-api.service.ts
    │   │   ├── reservation-api.service.ts
    │   │   ├── websocket.service.ts
    │   │   └── toast-notification.service.ts
    │   ├── app.config.ts          # Application providers (Router, HttpClient, STOMP, Animations)
    │   ├── app.routes.ts          # Root routing configuration
    │   └── app.component.ts       # Root shell component
    ├── assets/                    # Static assets, logos, icons
    └── styles.scss                # Global styles, Tailwind v4 import, theme overrides
```

---

## 5. Layer-by-Layer Standards & Patterns

### 5.1 Interactive Seat Map Component Pattern

```typescript
import { Component, ChangeDetectionStrategy, inject, signal, computed, input, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { SeatService } from '../../services/seat.service';
import { Seat } from '../../models/seat.model';

@Component({
  selector: 'app-seat-map',
  standalone: true,
  imports: [CommonModule, MatButtonModule, MatTooltipModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './seat-map.component.html',
  styleUrl: './seat-map.component.scss',
})
export class SeatMapComponent {
  private readonly seatService = inject(SeatService);

  // Inputs & Outputs with Angular Signals
  readonly eventId = input.required<string>();
  readonly maxSeats = input<number>(10);
  readonly seatSelected = output<Seat[]>();

  // Local Signal State
  readonly selectedSeatIds = signal<Set<string>>(new Set());

  // Reactive State from Domain Service
  readonly seats = this.seatService.seats;
  readonly isLoading = this.seatService.isLoading;

  // Computed State
  readonly selectedCount = computed(() => this.selectedSeatIds().size);
  readonly isMaxLimitReached = computed(() => this.selectedCount() >= this.maxSeats());
  readonly totalPrice = computed(() => {
    const selected = this.selectedSeatIds();
    return this.seats()
      .filter((s) => selected.has(s.id))
      .reduce((sum, s) => sum + s.price, 0);
  });

  toggleSeat(seat: Seat): void {
    if (seat.status !== 'AVAILABLE' && !this.selectedSeatIds().has(seat.id)) {
      return;
    }

    this.selectedSeatIds.update((current) => {
      const updated = new Set(current);
      if (updated.has(seat.id)) {
        updated.delete(seat.id);
      } else if (!this.isMaxLimitReached()) {
        updated.add(seat.id);
      }
      return updated;
    });

    const selectedSeats = this.seats().filter((s) => this.selectedSeatIds().has(s.id));
    this.seatSelected.emit(selectedSeats);
  }
}
```

### 4.2 Real-Time STOMP WebSocket Service

Handles real-time seat availability updates broadcast from the backend:

```typescript
import { Injectable, inject, signal } from '@angular/core';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { SeatStatusUpdate } from '../models/seat.model';
import { SeatService } from './seat.service';

@Injectable({ providedIn: 'root' })
export class WebSocketService {
  private readonly seatService = inject(SeatService);
  private client: Client | null = null;
  readonly isConnected = signal<boolean>(false);
  readonly seatUpdates = signal<SeatStatusUpdate | null>(null);

  connect(token: string, eventId: string): void {
    if (this.client?.active) {
      return;
    }

    this.client = new Client({
      webSocketFactory: () => new SockJS('/ws'),
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 5000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
      onConnect: () => {
        this.isConnected.set(true);
        // Authoritative reconciliation on initial connect or reconnect
        this.seatService.loadSeats(eventId);

        this.client?.subscribe(`/topic/events/${eventId}/seats`, (message) => {
          if (message.body) {
            const update: SeatStatusUpdate = JSON.parse(message.body);
            this.seatUpdates.set(update);
            this.seatService.updateSeatStatus(update.seatId, update.status);
          }
        });
      },
      onDisconnect: () => {
        this.isConnected.set(false);
      },
    });

    this.client.activate();
  }

  disconnect(): void {
    if (this.client) {
      this.client.deactivate();
      this.client = null;
      this.isConnected.set(false);
    }
  }
}
```

### 4.3 15-Minute Hold Countdown Timer Component

```typescript
import { Component, ChangeDetectionStrategy, input, output, signal, effect, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-hold-countdown',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="flex items-center gap-2 px-3 py-1.5 rounded-lg font-mono text-sm font-semibold"
         [ngClass]="remainingSeconds() < 120 ? 'bg-red-100 text-red-700 animate-pulse' : 'bg-amber-100 text-amber-800'">
      <span>Hold expires in:</span>
      <span>{{ formattedTime() }}</span>
    </div>
  `,
})
export class HoldCountdownComponent implements OnDestroy {
  readonly expiresAt = input.required<Date>();
  readonly expired = output<void>();

  readonly remainingSeconds = signal<number>(0);
  private timerId?: ReturnType<typeof setInterval>;

  readonly formattedTime = computed(() => {
    const s = this.remainingSeconds();
    const min = Math.floor(s / 60);
    const sec = s % 60;
    return `${min.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}`;
  });

  constructor() {
    effect((onCleanup) => {
      const target = this.expiresAt().getTime();
      this.clearTimer();

      const update = () => {
        const diff = Math.max(0, Math.floor((target - Date.now()) / 1000));
        this.remainingSeconds.set(diff);
        if (diff === 0) {
          this.clearTimer();
          this.expired.emit();
        }
      };

      update();
      this.timerId = setInterval(update, 1000);

      onCleanup(() => this.clearTimer());
    });
  }

  private clearTimer(): void {
    if (this.timerId) {
      clearInterval(this.timerId);
      this.timerId = undefined;
    }
  }

  ngOnDestroy(): void {
    this.clearTimer();
  }
}
```

---

## 5. Styling System: TailwindCSS v4 + Angular Material 22

- **TailwindCSS v4 (CSS-first):** Used for application layout, flexbox/grid containers, spacing, responsive typography, and custom color utilities. Configured directly in `styles.scss`:
  ```scss
  @import "tailwindcss";
  ```
- **Angular Material 22:** Used strictly for complex interactive components:
  - `MatTable` (Sortable, paginated data grids)
  - `MatDialog` (Accessible modal windows)
  - `MatSnackBar` (Toast alerts for conflicts and errors)
  - `MatDatepicker` (Accessible date picker)
  - `MatFormField` & `MatSelect` (Complex form inputs)
- **Style Isolation Rule:** Never apply Tailwind utility overrides directly on internal Angular Material classes (`.mat-mdc-*`). Wrap Material components in Tailwind-styled container elements.

---

## 6. HTTP & Security Standards

### 6.1 Application Providers (`app.config.ts`)
```typescript
import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { provideRouter, withComponentInputBinding, withViewTransitions } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { routes } from './app.routes';
import { authInterceptor } from './core/interceptors/auth.interceptor';
import { errorInterceptor } from './core/interceptors/error.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes, withComponentInputBinding(), withViewTransitions()),
    provideHttpClient(withInterceptors([authInterceptor, errorInterceptor])),
    provideAnimationsAsync(),
  ],
};
```

### 6.2 Auth Interceptor (`auth.interceptor.ts`)
```typescript
import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { AuthService } from '../auth/auth.service';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const token = authService.getToken();

  if (token && req.url.startsWith('/api')) {
    const authReq = req.clone({
      setHeaders: { Authorization: `Bearer ${token}` },
    });
    return next(authReq);
  }

  return next(req);
};
```

### 6.3 Global Error Interceptor (`error.interceptor.ts`)
```typescript
import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { catchError, throwError } from 'rxjs';
import { ApiErrorResponse } from '../models/api-error.model';

export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const router = inject(Router);
  const snackBar = inject(MatSnackBar);

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      const apiError = error.error as ApiErrorResponse;
      const message = apiError?.message || error.message || 'An unexpected error occurred';

      if (error.status === 401) {
        router.navigate(['/auth/login']);
      } else if (error.status === 409) {
        snackBar.open(`Conflict: ${message}`, 'Close', { duration: 5000, panelClass: 'bg-amber-600' });
      } else if (error.status >= 500) {
        snackBar.open(`Server Error: ${message}`, 'Close', { duration: 5000, panelClass: 'bg-red-600' });
      }

      return throwError(() => error);
    })
  );
};
```

---

## 7. Frontend Testing Requirements

- **Unit tests for components:** Use `ComponentFixture` and verify Signal state transitions.
- **Mocking HTTP calls:** Use `HttpTestingController` from `@angular/common/http/testing`.
- **Test reactive signals:** Verify `computed()` recalculates accurately when signals update.

```typescript
describe('SeatMapComponent', () => {
  let component: SeatMapComponent;
  let fixture: ComponentFixture<SeatMapComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SeatMapComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(SeatMapComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('eventId', 'event-123');
    fixture.detectChanges();
  });

  it('should enforce maxSeats selection limit', () => {
    fixture.componentRef.setInput('maxSeats', 2);
    component.toggleSeat({ id: 's1', status: 'AVAILABLE', row: 'A', number: 1, price: 50 });
    component.toggleSeat({ id: 's2', status: 'AVAILABLE', row: 'A', number: 2, price: 50 });
    component.toggleSeat({ id: 's3', status: 'AVAILABLE', row: 'A', number: 3, price: 50 });

    expect(component.selectedCount()).toBe(2);
    expect(component.isMaxLimitReached()).toBeTrue();
  });
});
```

---

## 8. Frontend Completion Checklist

A frontend task is complete only when all items are verified:

- [ ] All components are standalone (`standalone: true`).
- [ ] `ChangeDetectionStrategy.OnPush` is applied to all components.
- [ ] Signals (`signal()`, `computed()`, `input()`, `output()`) used for reactivity — NO `BehaviorSubject` in components.
- [ ] Dependency injection uses `inject()`.
- [ ] Feature routes are lazy-loaded.
- [ ] TailwindCSS v4 used for layouts/spacing; Material used for complex interactive controls.
- [ ] Error handling matches backend `ApiErrorResponse` schema.
- [ ] Unit tests pass with `npm test` or `ng test`.
