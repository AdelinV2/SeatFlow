# TASK-P09-001: Project Scaffolding, Nuanced Theme System & Sensory Design Tokens

## 1. Task Metadata
- **Task ID:** `TASK-P09-001`
- **Git Branch:** `feat/p09-001-scaffolding-and-theme`
- **Target Module:** `frontend/`
- **Phase:** `Phase 09 - Frontend Portal`
- **Related Specs:** `.ai/architecture/07-frontend-specification.md`, `frontend/AGENTS.md`, `.ai/SeatFlow-Architecture-and-Implementation-Spec.md` (Section 17)
- **Related ADRs:** `None`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Establish the foundational frontend application repository for **SeatFlow** using **Angular 22**, **TailwindCSS v4**, and **Angular Material 22**. Implement the core application shell, responsive layout scaffold, configuration files, and the nuanced sensory design system with full Dark/Light theme switching powered by Angular Signals and CSS custom properties.

### Critical Invariants to Enforce:
- [ ] **Zero Flat Pure #000000 or #FFFFFF:** Dark theme must use "Obsidian & Midnight Slate" (Canvas `#0B0F19`, Card Surface `#111827`, Elevated `#1E293B`, Borders `rgba(255, 255, 255, 0.08)`). Light theme must use "Warm Alabaster & Pearl Slate" (Canvas `#F8FAFC` to `#F1F5F9`, Card Surface `#FFFFFF`, Borders `#E2E8F0`).
- [ ] **Angular 22 Reactivity Standards:** 100% Standalone components (`standalone: true`), `ChangeDetectionStrategy.OnPush` on all components, Signals-first (`signal()`, `computed()`), and field-level `inject()`. Zero `NgModule` or `BehaviorSubject` for component state.
- [ ] **TailwindCSS v4 CSS-First Architecture:** Configure styling via `@import "tailwindcss";` in `src/styles.scss`. Material components must not have conflicting Tailwind utility overrides applied directly to internal DOM classes.
- [ ] **Sensory Motion & Tactile Physics:** Global CSS tokens and animation utilities for button spring press (`active:scale-[0.97] transition-all duration-150 ease-out`), sheen sweep gradients (`@keyframes sheen`), and seat spring bounce.
- [ ] **Environment Template (.env.example):** Version-controlled `.env.example` defining API Gateway base URL, WebSocket URL, and Microsoft Entra client/tenant ID.

---

## 3. Exact File Inventory
- `[NEW]` `frontend/package.json`
- `[NEW]` `frontend/tsconfig.json`
- `[NEW]` `frontend/tsconfig.app.json`
- `[NEW]` `frontend/tsconfig.spec.json`
- `[NEW]` `frontend/angular.json`
- `[NEW]` `frontend/.env.example`
- `[NEW]` `frontend/.gitignore`
- `[NEW]` `frontend/src/index.html`
- `[NEW]` `frontend/src/main.ts`
- `[NEW]` `frontend/src/styles.scss`
- `[NEW]` `frontend/src/app/app.config.ts`
- `[NEW]` `frontend/src/app/app.routes.ts`
- `[NEW]` `frontend/src/app/app.component.ts`
- `[NEW]` `frontend/src/app/app.component.html`
- `[NEW]` `frontend/src/app/app.component.scss`
- `[NEW]` `frontend/src/app/core/theme/theme.service.ts`
- `[NEW]` `frontend/src/app/core/theme/theme.model.ts`
- `[NEW]` `frontend/src/app/core/theme/theme.service.spec.ts`
- `[NEW]` `frontend/src/app/app.component.spec.ts`

---

## 4. Technical Specifications & Contracts

### 4.1 Theme Service & Model (`src/app/core/theme/`)

```typescript
// theme.model.ts
export type ThemeMode = 'dark' | 'light' | 'system';
export type ActiveTheme = 'dark' | 'light';

export interface ThemeConfig {
  mode: ThemeMode;
  active: ActiveTheme;
}
```

```typescript
// theme.service.ts
import { Injectable, signal, computed, effect, inject, PLATFORM_ID } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { ThemeMode, ActiveTheme } from './theme.model';

@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly platformId = inject(PLATFORM_ID);
  private readonly storageKey = 'seatflow_theme_mode';

  // Writable Signal for current mode selection
  readonly mode = signal<ThemeMode>(this.getInitialMode());

  // System preference listener signal
  private readonly systemPrefersDark = signal<boolean>(this.getSystemDarkPreference());

  // Computed active theme (resolves 'system' -> 'dark' | 'light')
  readonly activeTheme = computed<ActiveTheme>(() => {
    const currentMode = this.mode();
    if (currentMode === 'system') {
      return this.systemPrefersDark() ? 'dark' : 'light';
    }
    return currentMode;
  });

  readonly isDark = computed<boolean>(() => this.activeTheme() === 'dark');

  constructor() {
    if (isPlatformBrowser(this.platformId)) {
      // Sync with system media query changes
      const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');
      mediaQuery.addEventListener('change', (e) => {
        this.systemPrefersDark.set(e.matches);
      });

      // Effect to apply/remove .dark class on <html> document root and persist mode
      effect(() => {
        const active = this.activeTheme();
        const root = document.documentElement;
        if (active === 'dark') {
          root.classList.add('dark');
          root.classList.remove('light');
        } else {
          root.classList.add('light');
          root.classList.remove('dark');
        }
        localStorage.setItem(this.storageKey, this.mode());
      });
    }
  }

  setMode(mode: ThemeMode): void {
    this.mode.set(mode);
  }

  toggleTheme(): void {
    this.mode.update((current) => {
      if (current === 'dark') return 'light';
      if (current === 'light') return 'dark';
      return this.systemPrefersDark() ? 'light' : 'dark';
    });
  }

  private getInitialMode(): ThemeMode {
    if (!isPlatformBrowser(this.platformId)) return 'dark';
    const stored = localStorage.getItem(this.storageKey) as ThemeMode;
    return stored === 'dark' || stored === 'light' || stored === 'system' ? stored : 'system';
  }

  private getSystemDarkPreference(): boolean {
    if (!isPlatformBrowser(this.platformId)) return true;
    return window.matchMedia('(prefers-color-scheme: dark)').matches;
  }
}
```

### 4.2 Sensory CSS Theme Tokens & Keyframes (`src/styles.scss`)

```scss
@import "tailwindcss";

@layer base {
  :root {
    --color-canvas: #F8FAFC;
    --color-canvas-subtle: #F1F5F9;
    --color-surface: #FFFFFF;
    --color-surface-elevated: #FFFFFF;
    --color-border: #E2E8F0;
    --color-border-subtle: #F1F5F9;
    --color-text-primary: #0F172A;
    --color-text-secondary: #334155;
    --color-text-muted: #64748B;
    --color-primary: #4F46E5;
    --color-primary-hover: #4338CA;
    --color-primary-glow: rgba(79, 70, 229, 0.15);
    --color-accent-emerald: #059669;
    --color-accent-amber: #D97706;
    --color-accent-rose: #E11D48;
    --shadow-diffuse: 0 10px 25px -5px rgba(15, 23, 42, 0.05), 0 8px 10px -6px rgba(15, 23, 42, 0.03);
  }

  .dark {
    --color-canvas: #0B0F19;
    --color-canvas-subtle: #0F1629;
    --color-surface: #111827;
    --color-surface-elevated: #1E293B;
    --color-border: rgba(255, 255, 255, 0.08);
    --color-border-subtle: rgba(255, 255, 255, 0.04);
    --color-text-primary: #F8FAFC;
    --color-text-secondary: #94A3B8;
    --color-text-muted: #64748B;
    --color-primary: #6366F1;
    --color-primary-hover: #818CF8;
    --color-primary-glow: rgba(99, 102, 241, 0.20);
    --color-accent-emerald: #10B981;
    --color-accent-amber: #F59E0B;
    --color-accent-rose: #F43F5E;
    --shadow-diffuse: 0 10px 25px -5px rgba(0, 0, 0, 0.5), 0 8px 10px -6px rgba(0, 0, 0, 0.4);
  }

  body {
    background-color: var(--color-canvas);
    color: var(--color-text-primary);
    font-family: 'Inter', system-ui, -apple-system, sans-serif;
    transition: background-color 0.25s cubic-bezier(0.4, 0, 0.2, 1), color 0.25s cubic-bezier(0.4, 0, 0.2, 1);
    min-height: 100vh;
    overflow-x: hidden;
  }
}

/* Sensory Micro-Interactions & Animation Utilities */
@keyframes sheen-sweep {
  0% { transform: translateX(-100%) rotate(25deg); }
  100% { transform: translateX(250%) rotate(25deg); }
}

@keyframes seat-bounce {
  0% { transform: scale(1); }
  40% { transform: scale(1.25); }
  70% { transform: scale(0.92); }
  100% { transform: scale(1); }
}

@keyframes pulse-ring {
  0%, 100% { transform: scale(1); opacity: 1; }
  50% { transform: scale(1.08); opacity: 0.75; }
}

.animate-sheen {
  position: relative;
  overflow: hidden;
}
.animate-sheen::after {
  content: '';
  position: absolute;
  top: -50%;
  left: -50%;
  width: 50%;
  height: 200%;
  background: linear-gradient(to right, transparent, rgba(255, 255, 255, 0.25), transparent);
  transform: rotate(25deg);
  opacity: 0;
  transition: opacity 0.2s;
}
.animate-sheen:hover::after {
  opacity: 1;
  animation: sheen-sweep 1.2s infinite ease-in-out;
}

.seat-spring-bounce {
  animation: seat-bounce 0.35s cubic-bezier(0.34, 1.56, 0.64, 1);
}

.btn-spring {
  transition: all 0.15s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.btn-spring:active {
  transform: scale(0.97);
}
```

### 4.3 App Configuration (`src/app/app.config.ts`)

```typescript
import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { provideRouter, withComponentInputBinding, withViewTransitions } from '@angular/router';
import { provideHttpClient, withInterceptors, withFetch } from '@angular/common/http';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes, withComponentInputBinding(), withViewTransitions()),
    provideHttpClient(withFetch()),
    provideAnimationsAsync(),
  ],
};
```

---

## 5. Step-by-Step Implementation Sequence
1. **Initialize Frontend Repository Scaffold:**
   - Create `package.json` with dependencies (`@angular/core`, `@angular/common`, `@angular/router`, `@angular/material`, `@angular/cdk`, `tailwindcss`, `@stomp/stompjs`, `sockjs-client`, `leaflet`, `html5-qrcode`, `canvas-confetti`).
   - Create `tsconfig.json`, `tsconfig.app.json`, `tsconfig.spec.json`, and `angular.json` configured for Standalone architecture and Tailwind v4.
   - Create `.env.example` with `NG_APP_API_BASE_URL=http://localhost:8080`, `NG_APP_WS_URL=http://localhost:8080/ws`, `NG_APP_ENTRA_CLIENT_ID=00000000-0000-0000-0000-000000000000`.
2. **Implement Theme Tokens and Global Styles:**
   - Write `src/styles.scss` incorporating CSS custom properties for Obsidian/Midnight Slate (Dark) and Warm Alabaster (Light), spring physics utilities, and keyframe animations.
   - Write `src/index.html` referencing the Inter Google Font and pre-setting the dark class to prevent initial theme flash.
3. **Implement Theme Engine Service:**
   - Write `src/app/core/theme/theme.model.ts` and `src/app/core/theme/theme.service.ts` using Angular Signals.
   - Support `mode` signal (`'dark' | 'light' | 'system'`), `computed` active theme, `localStorage` persistence, and `prefers-color-scheme` listener.
4. **Implement Root Shell Component:**
   - Write `src/app/app.component.ts`, `app.component.html`, and `app.component.scss` rendering `<router-outlet />` with theme-aware container classes.
   - Configure `src/app/app.config.ts` and `src/app/app.routes.ts`.
5. **Develop Unit Tests:**
   - Create `src/app/core/theme/theme.service.spec.ts` testing mode switching, active theme computation, and `localStorage` persistence.
   - Create `src/app/app.component.spec.ts` testing root component rendering.

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
```
- [ ] Angular 22 standalone app config and build scripts compile cleanly.
- [ ] TailwindCSS v4 and Angular Material 22 base styling integrate with zero stylesheet compilation errors.
- [ ] `ThemeService` switches between dark, light, and system themes with signal reactivity and updates the root `<html>` class.
- [ ] All unit tests pass in `theme.service.spec.ts` and `app.component.spec.ts`.
- [ ] Task file is moved to `.ai/tasks/completed/phase-09-frontend-portal/001-project-scaffolding-theme-and-design-tokens.md`.
