# TASK-P09-002B: Frontend Authentication Suite (Login, Register, Forgot Password, Reset Password & OAuth Callback)

## 1. Task Metadata
- **Task ID:** `TASK-P09-002B`
- **Git Branch:** `feat/p09-002b-auth-pages`
- **Target Module:** `frontend/src/app/features/auth/`, `frontend/src/app/core/auth/`
- **Phase:** `Phase 09 - Frontend Portal`
- **Related Specs:** `.ai/architecture/04-authentication-security.md`, `.ai/architecture/07-frontend-specification.md` (Section 3), `frontend/AGENTS.md`
- **Related ADRs:** `ADR-006` (Supabase Auth Provider)
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the complete visual authentication experience for SeatFlow at `/auth/login`, `/auth/register`, `/auth/forgot-password`, `/auth/reset-password`, and `/auth/callback`. Connect directly to the Supabase-powered `AuthService` and `UserContextService`, rendering a responsive split-screen design on desktop with safe-area single-column layout on mobile devices.

### Critical Invariants to Enforce:
- [ ] **100% Standalone & OnPush:** All auth components must be standalone (`standalone: true`) with `ChangeDetectionStrategy.OnPush`.
- [ ] **Signals & Reactive Forms:** Use Angular Signals for loading state (`isLoading`, `isGoogleLoading`), error feedback (`errorMessage`), password visibility toggles (`showPassword`), and success status (`isSuccess`).
- [ ] **Responsive Split-Screen (Desktop) to Single Column (Mobile):** On screen widths $\ge 1024\text{px}$, render the left immersive hero stage banner with brand typography, glowing ambient blobs, and glassmorphic overlay. On $< 1024\text{px}$, hide the left visual pane (`hidden lg:flex`) and optimize the form container for touch interaction ($44\times 44\text{px}$ minimum touch targets).
- [ ] **Google 1-Click Social OAuth:** Prominent Google CTA button triggering `authService.signInWithOAuth('google')`.
- [ ] **Password Strength & Cross-Field Matching:** Minimum 8 characters; registration requires matching password confirmation (`confirmPassword`) and Terms & Privacy acceptance before submitting.
- [ ] **ReturnUrl Query Parameter Preservation:** Upon successful login, if a `returnUrl` query parameter exists (e.g. redirected from `authGuard`), redirect to `returnUrl`; otherwise default to `/events`.
- [ ] **Password Recovery via Supabase Mailer:** Triggers `supabase.auth.resetPasswordForEmail()` navigating users to `/auth/reset-password` upon email link activation.

---

## 3. Exact File Inventory
- `[MODIFY]` `frontend/src/app/core/auth/auth.service.ts`
- `[NEW]` `frontend/src/app/features/auth/login/login.component.ts`
- `[NEW]` `frontend/src/app/features/auth/login/login.component.html`
- `[NEW]` `frontend/src/app/features/auth/login/login.component.scss`
- `[NEW]` `frontend/src/app/features/auth/register/register.component.ts`
- `[NEW]` `frontend/src/app/features/auth/register/register.component.html`
- `[NEW]` `frontend/src/app/features/auth/register/register.component.scss`
- `[NEW]` `frontend/src/app/features/auth/forgot-password/forgot-password.component.ts`
- `[NEW]` `frontend/src/app/features/auth/forgot-password/forgot-password.component.html`
- `[NEW]` `frontend/src/app/features/auth/forgot-password/forgot-password.component.scss`
- `[NEW]` `frontend/src/app/features/auth/reset-password/reset-password.component.ts`
- `[NEW]` `frontend/src/app/features/auth/reset-password/reset-password.component.html`
- `[NEW]` `frontend/src/app/features/auth/reset-password/reset-password.component.scss`
- `[NEW]` `frontend/src/app/features/auth/callback/auth-callback.component.ts`
- `[MODIFY]` `frontend/src/app/app.routes.ts`
- `[NEW]` `frontend/src/app/features/auth/login/login.component.spec.ts`
- `[NEW]` `frontend/src/app/features/auth/register/register.component.spec.ts`
- `[NEW]` `frontend/src/app/features/auth/forgot-password/forgot-password.component.spec.ts`

---

## 4. Technical Specifications & Contracts

### 4.1 Route Table Mapping (`frontend/src/app/app.routes.ts`)
```typescript
{
  path: 'auth/login',
  loadComponent: () =>
    import('./features/auth/login/login.component').then((m) => m.LoginComponent),
},
{
  path: 'auth/register',
  loadComponent: () =>
    import('./features/auth/register/register.component').then((m) => m.RegisterComponent),
},
{
  path: 'auth/forgot-password',
  loadComponent: () =>
    import('./features/auth/forgot-password/forgot-password.component').then(
      (m) => m.ForgotPasswordComponent,
    ),
},
{
  path: 'auth/reset-password',
  loadComponent: () =>
    import('./features/auth/reset-password/reset-password.component').then(
      (m) => m.ResetPasswordComponent,
    ),
},
{
  path: 'auth/callback',
  loadComponent: () =>
    import('./features/auth/callback/auth-callback.component').then(
      (m) => m.AuthCallbackComponent,
    ),
},
```

### 4.2 AuthService Enhancements (`src/app/core/auth/auth.service.ts`)
```typescript
async resetPasswordForEmail(email: string, redirectTo?: string): Promise<void> {
  await this.initialization;
  this.lastError.set(null);
  const redirectUrl = redirectTo ?? `${window.location.origin}/auth/reset-password`;
  const { error } = await this.supabase.auth.resetPasswordForEmail(email, { redirectTo: redirectUrl });
  if (error) {
    this.lastError.set(error.message);
    throw error;
  }
}

async updatePassword(newPassword: string): Promise<void> {
  await this.initialization;
  this.lastError.set(null);
  const { data, error } = await this.supabase.auth.updateUser({ password: newPassword });
  if (error) {
    this.lastError.set(error.message);
    throw error;
  }
  if (data.user) {
    const { data: sessionData } = await this.supabase.auth.getSession();
    if (sessionData.session) {
      this.syncSession(sessionData.session);
    }
  }
}
```

### 4.3 UI Layout Specification
- **Split-Screen Container:**
  - Outer Wrapper: `min-h-[calc(100vh-8rem)] flex items-center justify-center px-4 py-8 sm:px-6 lg:px-8`
  - Card Shell: `grid w-full max-w-5xl overflow-hidden rounded-3xl border border-[var(--color-border)] bg-[var(--color-surface)] shadow-2xl backdrop-blur-xl lg:grid-cols-2`
- **Left Column (Branding Pane - Desktop Only):**
  - Visibility: `hidden lg:flex flex-col justify-between p-10 bg-gradient-to-br from-indigo-950 via-slate-900 to-violet-950 text-white relative overflow-hidden`
  - Elements: SeatFlow Logo, Tagline badge, Stage visual quote ("Experiențe de neuitat. Locuri rezervate în timp real."), and feature checklist.
- **Right Column (Interactive Form Pane):**
  - Container: `flex flex-col justify-center p-6 sm:p-10 md:p-12`
  - Responsive Mobile Logo Header: Displayed on small screens (`lg:hidden`).
  - Google Social CTA: Tactile button with official Google SVG logo.
  - Form Fields: High-contrast inputs styled with `var(--color-canvas)`, subtle borders, active focus glow, error feedback, and toggleable password eye icons.

---

## 5. Step-by-Step Implementation Sequence
1. **Update Core Auth Service:**
   - Add `resetPasswordForEmail(email, redirectTo)` and `updatePassword(newPassword)` in `src/app/core/auth/auth.service.ts`.
2. **Implement LoginComponent (`/auth/login`):**
   - Create `login.component.ts`, `html`, `scss`.
   - Setup reactive form (`email`, `password`).
   - Implement `onSubmit()` redirecting to `returnUrl` or `/events`.
   - Implement `signInWithGoogle()` calling `authService.signInWithOAuth('google')`.
3. **Implement RegisterComponent (`/auth/register`):**
   - Create `register.component.ts`, `html`, `scss`.
   - Setup form controls (`name`, `email`, `password`, `confirmPassword`, `agreeTerms`).
   - Implement cross-field `passwordMatchValidator`.
   - Display celebratory success state upon completion.
4. **Implement Password Recovery Flow:**
   - Create `ForgotPasswordComponent` (`/auth/forgot-password`) calling `resetPasswordForEmail()`.
   - Create `ResetPasswordComponent` (`/auth/reset-password`) calling `updatePassword()`.
5. **Implement AuthCallbackComponent (`/auth/callback`):**
   - Create `auth-callback.component.ts` rendering a minimal spinner while Supabase completes token verification, then routing to `/events`.
6. **Register App Routes:**
   - Add lazy routes in `src/app/app.routes.ts`.
7. **Write Unit Tests:**
   - Test `LoginComponent` (form submission, Google click, error rendering).
   - Test `RegisterComponent` (validation, password mismatch detection).
   - Test `ForgotPasswordComponent` (email dispatch).
8. **Run Verification:**
   - Run `npm test -- --watch=false --browsers=ChromeHeadless` to verify that all suites pass.

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
```
- [ ] Code strictly satisfies the task specification without extra unrequested features.
- [ ] Compiles cleanly with zero compiler warnings or lint errors.
- [ ] All unit and slice tests pass locally.
- [ ] Task file is moved to `.ai/tasks/completed/phase-09-frontend-portal/002b-auth-pages-login-register-and-password-reset.md`.
