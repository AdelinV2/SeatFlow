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
Implement the complete authentication and core HTTP infrastructure for the SeatFlow frontend. This includes OIDC / Microsoft Entra External ID token integration (`AuthService`), reactive user context (`UserContextService`), functional HTTP interceptors (`authInterceptor`, `correlationInterceptor`, `errorInterceptor`), functional route authorization guards (`authGuard`, `adminGuard`, `staffGuard`), and the responsive glassmorphic navigation header, mobile drawer, and footer shell.

### Critical Invariants to Enforce:
- [ ] **Reactive User Context with Signals:** `UserContextService` must expose user identity and roles via Angular Signals (`currentUser`, `roles`, `isAuthenticated`, `isAdmin`, `isStaff`). No `BehaviorSubject` in component/service public state.
- [ ] **Distributed Tracing & Correlation Header:** Every outgoing HTTP request must attach a unique `X-Correlation-Id` UUID header if not already present.
- [ ] **ApiErrorResponse Standard Mapping:** The `errorInterceptor` must parse backend `ApiErrorResponse` envelopes (`errorCode`, `message`, `validationErrors`) and display contextual feedback via `MatSnackBar` without crashing the application state.
- [ ] **Role-Based Functional Route Guards:** Enforce `authGuard` (any authenticated user), `adminGuard` (requires `ROLE_ADMIN`), and `staffGuard` (requires `ROLE_STAFF` or `ROLE_ADMIN` per ADR-005).
- [ ] **Glassmorphic Navigation Shell:** Navigation header must feature responsive glassmorphism (`backdrop-blur-md bg-opacity-80 border-b border-[var(--color-border)]`), live theme switcher toggle, responsive mobile drawer, and role-conditioned route links (Catalog, My Tickets, Staff Scanner, Admin Portal).

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
        // Redirect to login on unauthenticated protected request
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

### 4.4 Functional Route Guards (`src/app/core/guards/`)

```typescript
// staff.guard.ts (ADR-005)
import { CanActivateFn, Router } from '@angular/router';
import { inject } from '@angular/core';
import { UserContextService } from '../auth/user-context.service';

export const staffGuard: CanActivateFn = (route, state) => {
  const userContext = inject(UserContextService);
  const router = inject(Router);

  if (userContext.isStaff()) {
    return true;
  }

  return router.createUrlTree(['/auth/login'], { queryParams: { returnUrl: state.url } });
};
```

```typescript
// admin.guard.ts
import { CanActivateFn, Router } from '@angular/router';
import { inject } from '@angular/core';
import { UserContextService } from '../auth/user-context.service';

export const adminGuard: CanActivateFn = (route, state) => {
  const userContext = inject(UserContextService);
  const router = inject(Router);

  if (userContext.isAdmin()) {
    return true;
  }

  return router.createUrlTree(['/']);
};
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
   - Register them in `src/app/app.config.ts` via `provideHttpClient(withInterceptors([...]))`.
4. **Implement Functional Route Guards:**
   - Create `authGuard`, `adminGuard`, `staffGuard`, and `pendingChangesGuard`.
5. **Implement Glassmorphic Navigation Shell & Footer:**
   - Create `src/app/shared/layout/header/` standalone component with theme toggle button, role-aware desktop navigation links, and mobile slide-out drawer.
   - Create `src/app/shared/layout/footer/` standalone component with branding, links, and system status indicator.
   - Embed `<app-header />` and `<app-footer />` into `src/app/app.component.html`.
6. **Develop Comprehensive Unit Tests:**
   - Test `UserContextService` role computations.
   - Test interceptors with `HttpClientTestingModule` / `HttpTestingController`.
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
- [ ] All unit tests pass cleanly.
- [ ] Task file is moved to `.ai/tasks/completed/phase-09-frontend-portal/002-core-auth-oidc-interceptors-and-nav-shell.md`.
