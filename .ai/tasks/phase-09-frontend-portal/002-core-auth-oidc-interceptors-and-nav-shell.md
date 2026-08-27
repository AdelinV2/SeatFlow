# TASK-P09-002: Core Auth (OIDC/Entra ID), HTTP Interceptors, Guards & Navigation Shell

## 1. Task Metadata
- **Task ID:** `TASK-P09-002`
- **Git Branch:** `feat/p09-002-core-auth-and-shell`
- **Target Module:** `frontend/src/app/core/`, `frontend/src/app/shared/layout/`
- **Phase:** `Phase 09 - Frontend Portal`
- **Related Specs:** `.ai/architecture/04-authentication-security.md`, `.ai/architecture/06-api-contracts.md`, `.ai/architecture/07-frontend-specification.md`, `frontend/AGENTS.md`
- **Related ADRs:** `ADR-001` (Hybrid Guest Checkout), `ADR-005` (Staff Scanner Authorization)
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the complete authentication and core HTTP infrastructure for the SeatFlow frontend. This includes OIDC / Microsoft Entra External ID token integration (`AuthService`), reactive user context (`UserContextService`), functional HTTP interceptors (`authInterceptor`, `correlationInterceptor`, `errorInterceptor`), functional route authorization guards (`authGuard`, `adminGuard`, `staffGuard`), and the responsive glassmorphic navigation header, mobile slide-out drawer, and the rich multi-column footer shell.

### Critical Invariants to Enforce:
- [ ] **Reactive User Context with Signals:** `UserContextService` must expose user identity and roles via Angular Signals (`currentUser`, `roles`, `isAuthenticated`, `isAdmin`, `isStaff`). No `BehaviorSubject` in component/service public state.
- [ ] **Distributed Tracing & Correlation Header:** Every outgoing HTTP request must attach a unique `X-Correlation-Id` UUID header if not already present.
- [ ] **ApiErrorResponse Standard Mapping:** The `errorInterceptor` must parse backend `ApiErrorResponse` envelopes (`errorCode`, `message`, `validationErrors`) and display contextual feedback via `MatSnackBar` without crashing the application state.
- [ ] **Role-Based Functional Route Guards:** Enforce `authGuard` (any authenticated user), `adminGuard` (requires `ROLE_ADMIN`), and `staffGuard` (requires `ROLE_STAFF` or `ROLE_ADMIN` per ADR-005).
- [ ] **Responsive Glassmorphic Navigation Shell:** Navigation header must feature responsive glassmorphism (`backdrop-blur-md bg-opacity-80 border-b border-[var(--color-border)]`), live theme switcher toggle, mobile hamburger drawer menu with safe-area spacing, and role-conditioned route links (Catalog, My Tickets, Staff Scanner, Admin Portal).
- [ ] **Rich Multi-Column Footer:** Footer must include 4 structured columns: Brand & Mission, Quick Explore Links, Support & Guest Ticket Lookup, Legal & Compliance (Terms & Conditions, Privacy Policy, Stripe Tax/VAT disclosure, Refund Policy), Live Platform Status indicator (`🟢 All Systems Operational`), and dynamic copyright year.

---

## 3. Exact File Inventory
- `[NEW]` `frontend/src/app/models/user.model.ts`
- `[NEW]` `frontend/src/app/models/api-error.model.ts`
- `[NEW]` `frontend/src/app/core/auth/auth.service.ts`
- `[NEW]` `frontend/src/app/core/auth/user-context.service.ts`
- `[NEW]` `frontend/src/app/core/auth/auth.service.spec.ts`
- `[NEW]` `frontend/src/app/core/auth/user-context.service.spec.ts`
- `[NEW]` `frontend/src/app/core/interceptors/auth.interceptor.ts`
- `[NEW]` `frontend/src/app/core/interceptors/correlation.interceptor.ts`
- `[NEW]` `frontend/src/app/core/interceptors/error.interceptor.ts`
- `[NEW]` `frontend/src/app/core/interceptors/interceptors.spec.ts`
- `[NEW]` `frontend/src/app/core/guards/auth.guard.ts`
- `[NEW]` `frontend/src/app/core/guards/admin.guard.ts`
- `[NEW]` `frontend/src/app/core/guards/staff.guard.ts`
- `[NEW]` `frontend/src/app/core/guards/pending-changes.guard.ts`
- `[NEW]` `frontend/src/app/core/guards/guards.spec.ts`
- `[NEW]` `frontend/src/app/shared/layout/header/header.component.ts`
- `[NEW]` `frontend/src/app/shared/layout/header/header.component.html`
- `[NEW]` `frontend/src/app/shared/layout/header/header.component.scss`
- `[NEW]` `frontend/src/app/shared/layout/footer/footer.component.ts`
- `[NEW]` `frontend/src/app/shared/layout/footer/footer.component.html`
- `[NEW]` `frontend/src/app/shared/layout/footer/footer.component.scss`
- `[NEW]` `frontend/src/app/shared/layout/header/header.component.spec.ts`
- `[MODIFY]` `frontend/src/app/app.component.html`
- `[MODIFY]` `frontend/src/app/app.component.ts`

---

## 4. Technical Specifications & Contracts

### 4.1 Models (`src/app/models/`)

```typescript
// user.model.ts
export interface UserProfile {
  id: string;
  email: string;
  name?: string;
  roles: string[];
  phone?: string;
  createdAt?: string;
}

export interface JwtClaims {
  sub: string;
  email?: string;
  name?: string;
  roles?: string[];
  exp?: number;
  iat?: number;
}
```

```typescript
// api-error.model.ts
export interface ValidationErrorDetail {
  field: string;
  message: string;
  rejectedValue?: unknown;
}

export interface ApiErrorResponse {
  status: number;
  error: string;
  errorCode: string;
  message: string;
  path: string;
  timestamp: string;
  correlationId?: string;
  validationErrors?: ValidationErrorDetail[];
}
```

### 4.2 User Context Service (`src/app/core/auth/user-context.service.ts`)

```typescript
import { Injectable, signal, computed } from '@angular/core';
import { UserProfile } from '../../models/user.model';

@Injectable({ providedIn: 'root' })
export class UserContextService {
  readonly currentUser = signal<UserProfile | null>(null);

  readonly isAuthenticated = computed(() => this.currentUser() !== null);
  readonly roles = computed(() => this.currentUser()?.roles ?? []);
  readonly userEmail = computed(() => this.currentUser()?.email ?? '');
  readonly userName = computed(() => this.currentUser()?.name ?? this.currentUser()?.email ?? 'User');

  readonly isCustomer = computed(() => this.roles().includes('ROLE_CUSTOMER'));
  readonly isStaff = computed(() => this.roles().includes('ROLE_STAFF') || this.roles().includes('ROLE_ADMIN'));
  readonly isAdmin = computed(() => this.roles().includes('ROLE_ADMIN'));

  setUser(user: UserProfile | null): void {
    this.currentUser.set(user);
  }

  clearUser(): void {
    this.currentUser.set(null);
  }
}
```

### 4.3 Functional Interceptors (`src/app/core/interceptors/`)

```typescript
// correlation.interceptor.ts
import { HttpInterceptorFn } from '@angular/common/http';

export const correlationInterceptor: HttpInterceptorFn = (req, next) => {
  const correlationId = crypto.randomUUID();
  const modifiedReq = req.clone({
    setHeaders: { 'X-Correlation-Id': correlationId },
  });
  return next(modifiedReq);
};
```

```typescript
// auth.interceptor.ts
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

```typescript
// error.interceptor.ts
import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { catchError, throwError } from 'rxjs';
import { ApiErrorResponse } from '../../models/api-error.model';

export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const router = inject(Router);
  const snackBar = inject(MatSnackBar);

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      const apiError = error.error as ApiErrorResponse;
      const message = apiError?.message || error.statusText || 'An unexpected error occurred';

      if (error.status === 401) {
        router.navigate(['/auth/login'], { queryParams: { returnUrl: router.url } });
      } else if (error.status === 403) {
        snackBar.open(`Access Denied: You do not have permission for this action.`, 'Close', {
          duration: 4000,
          panelClass: 'snack-error',
        });
      } else if (error.status === 409) {
        snackBar.open(`Conflict: ${message}`, 'Close', {
          duration: 5000,
          panelClass: 'snack-warning',
        });
      } else if (error.status >= 500) {
        snackBar.open(`Server Error [${apiError?.errorCode || 'UNKNOWN'}]: ${message}`, 'Close', {
          duration: 6000,
          panelClass: 'snack-error',
        });
      }

      return throwError(() => error);
    })
  );
};
```

### 4.4 Rich Multi-Column Footer Component (`src/app/shared/layout/footer/`)

```typescript
import { Component, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-footer',
  standalone: true,
  imports: [CommonModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './footer.component.html',
  styleUrl: './footer.component.scss',
})
export class FooterComponent {
  readonly currentYear = new Date().getFullYear();
}
```

```html
<footer class="mt-20 border-t border-[var(--color-border)] bg-[var(--color-canvas-subtle)] text-[var(--color-text-secondary)]">
  <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-12 md:py-16">
    <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-8 lg:gap-12">
      <!-- Column 1: Brand & Mission -->
      <div class="space-y-4">
        <div class="flex items-center gap-2.5">
          <div class="w-8 h-8 rounded-xl bg-gradient-to-tr from-indigo-600 to-violet-500 flex items-center justify-center text-white font-black text-base shadow-md">
            S
          </div>
          <span class="text-xl font-bold tracking-tight text-[var(--color-text-primary)]">SeatFlow</span>
        </div>
        <p class="text-xs text-muted leading-relaxed">
          Premium live event ticketing and real-time interactive seat booking. Zero double-booking guarantee with instant cryptographic ticket delivery.
        </p>
        <div class="flex items-center gap-2 text-xs text-emerald-400 font-semibold">
          <span class="w-2 h-2 rounded-full bg-emerald-500 animate-ping"></span>
          <span>All Systems Operational</span>
        </div>
      </div>

      <!-- Column 2: Explore Events -->
      <div>
        <h4 class="text-xs font-bold uppercase tracking-wider text-[var(--color-text-primary)] mb-3">Explore Events</h4>
        <ul class="space-y-2 text-xs text-muted">
          <li><a routerLink="/events" class="hover:text-indigo-400 transition-colors">All Events Catalog</a></li>
          <li><a routerLink="/events" [queryParams]="{ category: 'CONCERT' }" class="hover:text-indigo-400 transition-colors">Concerts & Live Music</a></li>
          <li><a routerLink="/events" [queryParams]="{ category: 'THEATRE' }" class="hover:text-indigo-400 transition-colors">Theatre & Opera</a></li>
          <li><a routerLink="/events" [queryParams]="{ category: 'SPORTS' }" class="hover:text-indigo-400 transition-colors">Sports Arenas</a></li>
          <li><a routerLink="/events" [queryParams]="{ category: 'FESTIVAL' }" class="hover:text-indigo-400 transition-colors">Festivals & Electronic</a></li>
        </ul>
      </div>

      <!-- Column 3: Customer Support -->
      <div>
        <h4 class="text-xs font-bold uppercase tracking-wider text-[var(--color-text-primary)] mb-3">Support & Tools</h4>
        <ul class="space-y-2 text-xs text-muted">
          <li><a routerLink="/profile/tickets" class="hover:text-indigo-400 transition-colors">My Digital Tickets</a></li>
          <li><a routerLink="/auth/login" class="hover:text-indigo-400 transition-colors">Guest Ticket Lookup</a></li>
          <li><a routerLink="/scanner" class="hover:text-indigo-400 transition-colors">Staff Gate Scanner</a></li>
          <li><span class="hover:text-indigo-400 transition-colors cursor-pointer">Help Center & FAQ</span></li>
          <li><span class="hover:text-indigo-400 transition-colors cursor-pointer">Contact Event Organizers</span></li>
        </ul>
      </div>

      <!-- Column 4: Legal & Compliance -->
      <div>
        <h4 class="text-xs font-bold uppercase tracking-wider text-[var(--color-text-primary)] mb-3">Legal & Fiscal</h4>
        <ul class="space-y-2 text-xs text-muted">
          <li><span class="hover:text-indigo-400 transition-colors cursor-pointer">Terms & Conditions</span></li>
          <li><span class="hover:text-indigo-400 transition-colors cursor-pointer">Privacy Policy (GDPR)</span></li>
          <li><span class="hover:text-indigo-400 transition-colors cursor-pointer">Stripe Tax & VAT Policy</span></li>
          <li><span class="hover:text-indigo-400 transition-colors cursor-pointer">Refund & Cancellation Rules</span></li>
          <li><span class="hover:text-indigo-400 transition-colors cursor-pointer">Cookie Preferences</span></li>
        </ul>
      </div>
    </div>

    <!-- Bottom Bar -->
    <div class="mt-12 pt-6 border-t border-[var(--color-border)] flex flex-col sm:flex-row items-center justify-between gap-4 text-xs text-muted">
      <p>© {{ currentYear }} SeatFlow Inc. All rights reserved. Powered by Angular 22 & Spring Boot.</p>
      <div class="flex items-center gap-4">
        <span class="hover:text-[var(--color-text-primary)] cursor-pointer">Security</span>
        <span class="hover:text-[var(--color-text-primary)] cursor-pointer">Status</span>
        <span class="hover:text-[var(--color-text-primary)] cursor-pointer">API Docs</span>
      </div>
    </div>
  </div>
</footer>
```

---

## 5. Step-by-Step Implementation Sequence
1. **Define Core TypeScript Models:**
   - Create `src/app/models/user.model.ts` and `src/app/models/api-error.model.ts`.
2. **Implement AuthService and UserContextService:**
   - Create `src/app/core/auth/user-context.service.ts` using Angular Signals.
   - Create `src/app/core/auth/auth.service.ts` managing token extraction, MSAL/OIDC login/logout flow, local JWT claim decoding, and syncing with `UserContextService`.
3. **Implement Functional HTTP Interceptors:**
   - Implement `correlationInterceptor`, `authInterceptor`, and `errorInterceptor`.
4. **Implement Functional Route Guards:**
   - Create `authGuard`, `adminGuard`, `staffGuard`, and `pendingChangesGuard`.
5. **Implement Glassmorphic Navigation Shell & Rich Footer:**
   - Create `src/app/shared/layout/header/` standalone component with theme toggle button, role-aware desktop navigation links, and mobile slide-out drawer with backdrop blur.
   - Create `src/app/shared/layout/footer/` standalone component with the 4 structured columns (Brand, Explore, Support, Legal & Terms) and system status badge.
   - Embed `<app-header />` and `<app-footer />` into `src/app/app.component.html`.
6. **Develop Comprehensive Unit Tests:**
   - Test `UserContextService` role computations.
   - Test interceptors with `HttpTestingController`.
   - Test `staffGuard` and `adminGuard` redirection rules.

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
```
- [ ] `AuthService` and `UserContextService` accurately parse and expose JWT roles via reactive Signals.
- [ ] `correlationInterceptor` injects `X-Correlation-Id` on all outgoing requests.
- [ ] `errorInterceptor` captures backend error envelopes and renders `MatSnackBar` alerts.
- [ ] `staffGuard` permits `ROLE_STAFF` & `ROLE_ADMIN` and blocks unauthorized users.
- [ ] Responsive navigation shell renders cleanly with live theme toggle and mobile navigation drawer.
- [ ] Rich multi-column footer renders Terms, Privacy, Support, and live operational status.
- [ ] All unit tests pass cleanly.
- [ ] Task file is moved to `.ai/tasks/completed/phase-09-frontend-portal/002-core-auth-oidc-interceptors-and-nav-shell.md`.
