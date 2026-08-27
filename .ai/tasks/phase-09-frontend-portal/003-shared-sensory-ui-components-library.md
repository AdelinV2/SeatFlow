# TASK-P09-003: Shared Sensory UI Components, Directives & Custom Pipes Library

## 1. Task Metadata
- **Task ID:** `TASK-P09-003`
- **Git Branch:** `feat/p09-003-shared-ui-components`
- **Target Module:** `frontend/src/app/shared/`
- **Phase:** `Phase 09 - Frontend Portal`
- **Related Specs:** `.ai/architecture/07-frontend-specification.md`, `frontend/AGENTS.md`, `.ai/SeatFlow-Architecture-and-Implementation-Spec.md` (Section 17)
- **Related ADRs:** `None`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Create the shared library of reusable, sensory UI components, tactile directives, and formatting pipes. These components embody the luxurious design system (Midnight Slate & Warm Alabaster), providing tactile spring damping on button presses, continuous sheen sweep gradients on primary CTAs, SVG circular countdown rings for seat holds, fluid skeleton shimmer loaders, accessible QR display modals, and status badges.

### Critical Invariants to Enforce:
- [ ] **100% Standalone & OnPush:** Every shared component, pipe, and directive must be standalone and use `ChangeDetectionStrategy.OnPush`.
- [ ] **Signals-First API:** Use `input.required<T>()`, `input<T>()`, and `output<T>()` for component I/O.
- [ ] **Physical Spring Press Damping:** `TactileButtonComponent` and `SpringPressDirective` must apply spring transform damping (`active:scale-[0.97] transition-all duration-150 ease-out`).
- [ ] **SVG Circular Hold Progress Ring:** `HoldCountdownComponent` must render a dynamic SVG circle progress ring computing remaining percentage, flashing vigorously in amber/rose under 120 seconds and emitting `(expired)` at `00:00`.
- [ ] **Accessible High-Density QR Dialog:** `QrModalComponent` must render scannable high-contrast QR codes with 1-click clipboard copy of alphanumeric ticket codes.

---

## 3. Exact File Inventory
- `[NEW]` `frontend/src/app/shared/components/tactile-button/tactile-button.component.ts`
- `[NEW]` `frontend/src/app/shared/components/tactile-button/tactile-button.component.html`
- `[NEW]` `frontend/src/app/shared/components/tactile-button/tactile-button.component.scss`
- `[NEW]` `frontend/src/app/shared/components/glass-card/glass-card.component.ts`
- `[NEW]` `frontend/src/app/shared/components/glass-card/glass-card.component.html`
- `[NEW]` `frontend/src/app/shared/components/glass-card/glass-card.component.scss`
- `[NEW]` `frontend/src/app/shared/components/hold-countdown/hold-countdown.component.ts`
- `[NEW]` `frontend/src/app/shared/components/hold-countdown/hold-countdown.component.html`
- `[NEW]` `frontend/src/app/shared/components/hold-countdown/hold-countdown.component.scss`
- `[NEW]` `frontend/src/app/shared/components/qr-modal/qr-modal.component.ts`
- `[NEW]` `frontend/src/app/shared/components/qr-modal/qr-modal.component.html`
- `[NEW]` `frontend/src/app/shared/components/qr-modal/qr-modal.component.scss`
- `[NEW]` `frontend/src/app/shared/components/skeleton-loader/skeleton-loader.component.ts`
- `[NEW]` `frontend/src/app/shared/components/skeleton-loader/skeleton-loader.component.html`
- `[NEW]` `frontend/src/app/shared/components/skeleton-loader/skeleton-loader.component.scss`
- `[NEW]` `frontend/src/app/shared/components/status-badge/status-badge.component.ts`
- `[NEW]` `frontend/src/app/shared/directives/spring-press.directive.ts`
- `[NEW]` `frontend/src/app/shared/pipes/currency-format.pipe.ts`
- `[NEW]` `frontend/src/app/shared/pipes/date-format.pipe.ts`
- `[NEW]` `frontend/src/app/shared/components/tactile-button/tactile-button.component.spec.ts`
- `[NEW]` `frontend/src/app/shared/components/hold-countdown/hold-countdown.component.spec.ts`
- `[NEW]` `frontend/src/app/shared/components/status-badge/status-badge.component.spec.ts`
- `[NEW]` `frontend/src/app/shared/pipes/pipes.spec.ts`

---

## 4. Technical Specifications & Contracts

### 4.1 Tactile Button Component (`src/app/shared/components/tactile-button/`)

```typescript
import { Component, ChangeDetectionStrategy, input, output } from '@angular/core';
import { CommonModule } from '@angular/common';

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'success';
export type ButtonSize = 'sm' | 'md' | 'lg';

@Component({
  selector: 'app-tactile-button',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './tactile-button.component.html',
  styleUrl: './tactile-button.component.scss',
})
export class TactileButtonComponent {
  readonly variant = input<ButtonVariant>('primary');
  readonly size = input<ButtonSize>('md');
  readonly loading = input<boolean>(false);
  readonly disabled = input<boolean>(false);
  readonly type = input<'button' | 'submit' | 'reset'>('button');
  readonly icon = input<string>(''); // Optional icon name
  readonly clicked = output<MouseEvent>();

  handleClick(event: MouseEvent): void {
    if (!this.disabled() && !this.loading()) {
      this.clicked.emit(event);
    }
  }
}
```

```html
<button
  [type]="type()"
  [disabled]="disabled() || loading()"
  (click)="handleClick($event)"
  class="btn-spring relative inline-flex items-center justify-center font-medium rounded-xl select-none transition-all duration-150 ease-out focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-indigo-500 disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
  [ngClass]="[
    variant() === 'primary' ? 'bg-indigo-600 hover:bg-indigo-500 text-white shadow-lg shadow-indigo-500/25 animate-sheen' : '',
    variant() === 'secondary' ? 'bg-[var(--color-surface-elevated)] hover:bg-slate-700/20 text-[var(--color-text-primary)] border border-[var(--color-border)]' : '',
    variant() === 'ghost' ? 'hover:bg-slate-800/10 text-[var(--color-text-secondary)]' : '',
    variant() === 'danger' ? 'bg-rose-600 hover:bg-rose-500 text-white shadow-lg shadow-rose-500/25' : '',
    variant() === 'success' ? 'bg-emerald-600 hover:bg-emerald-500 text-white shadow-lg shadow-emerald-500/25' : '',
    size() === 'sm' ? 'px-3 py-1.5 text-xs gap-1.5' : '',
    size() === 'md' ? 'px-4 py-2.5 text-sm gap-2' : '',
    size() === 'lg' ? 'px-6 py-3.5 text-base gap-2.5' : ''
  ]"
>
  @if (loading()) {
    <svg class="animate-spin -ml-1 mr-2 h-4 w-4 text-current" fill="none" viewBox="0 0 24 24">
      <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
      <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z"></path>
    </svg>
  }
  <ng-content />
</button>
```

### 4.2 SVG Circular Hold Countdown Component (`src/app/shared/components/hold-countdown/`)

```typescript
import { Component, ChangeDetectionStrategy, input, output, signal, computed, effect, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-hold-countdown',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './hold-countdown.component.html',
  styleUrl: './hold-countdown.component.scss',
})
export class HoldCountdownComponent implements OnDestroy {
  readonly expiresAt = input.required<string | Date>();
  readonly totalDurationSeconds = input<number>(900); // 15 minutes default
  readonly expired = output<void>();

  readonly remainingSeconds = signal<number>(0);
  private timerId?: ReturnType<typeof setInterval>;

  readonly formattedTime = computed(() => {
    const totalSec = this.remainingSeconds();
    const minutes = Math.floor(totalSec / 60);
    const seconds = totalSec % 60;
    return `${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}`;
  });

  readonly isUrgent = computed(() => this.remainingSeconds() < 120);

  // SVG Progress calculation (Circumference = 2 * PI * r = 2 * PI * 18 ≈ 113.1)
  readonly circleCircumference = 113.1;
  readonly strokeDashoffset = computed(() => {
    const fraction = Math.min(1, Math.max(0, this.remainingSeconds() / this.totalDurationSeconds()));
    return this.circleCircumference * (1 - fraction);
  });

  constructor() {
    effect((onCleanup) => {
      const expiry = new Date(this.expiresAt()).getTime();
      this.clearTimer();

      const tick = () => {
        const diff = Math.max(0, Math.floor((expiry - Date.now()) / 1000));
        this.remainingSeconds.set(diff);
        if (diff === 0) {
          this.clearTimer();
          this.expired.emit();
        }
      };

      tick();
      this.timerId = setInterval(tick, 1000);
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

```html
<div
  class="flex items-center gap-3 px-3.5 py-2 rounded-xl border backdrop-blur-md transition-all duration-300 font-mono"
  [ngClass]="isUrgent() ? 'bg-rose-500/10 border-rose-500/30 text-rose-400 animate-pulse' : 'bg-amber-500/10 border-amber-500/20 text-amber-300'"
>
  <div class="relative w-8 h-8 flex items-center justify-center">
    <svg class="w-8 h-8 -rotate-90" viewBox="0 0 40 40">
      <circle cx="20" cy="20" r="18" fill="none" stroke="currentColor" stroke-opacity="0.2" stroke-width="3" />
      <circle
        cx="20"
        cy="20"
        r="18"
        fill="none"
        stroke="currentColor"
        stroke-width="3"
        stroke-linecap="round"
        [style.strokeDasharray]="circleCircumference"
        [style.strokeDashoffset]="strokeDashoffset()"
        class="transition-all duration-1000 linear"
      />
    </svg>
    <span class="absolute text-[10px] font-bold">⏱</span>
  </div>
  <div class="flex flex-col">
    <span class="text-[10px] uppercase tracking-wider text-muted opacity-80">Hold Expires In</span>
    <span class="text-sm font-bold tracking-tight">{{ formattedTime() }}</span>
  </div>
</div>
```

### 4.3 Custom Pipes (`src/app/shared/pipes/`)

```typescript
// currency-format.pipe.ts
import { Pipe, PipeTransform } from '@angular/core';

@Pipe({
  name: 'sfCurrency',
  standalone: true,
})
export class CurrencyFormatPipe implements PipeTransform {
  transform(value: number | null | undefined, currencyCode: string = 'USD'): string {
    if (value === null || value === undefined) return '—';
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: currencyCode,
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(value);
  }
}
```

```typescript
// date-format.pipe.ts
import { Pipe, PipeTransform } from '@angular/core';

@Pipe({
  name: 'sfDate',
  standalone: true,
})
export class DateFormatPipe implements PipeTransform {
  transform(value: string | Date | null | undefined, format: 'full' | 'short' | 'time' = 'full'): string {
    if (!value) return '—';
    const date = new Date(value);
    if (isNaN(date.getTime())) return '—';

    if (format === 'time') {
      return date.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' });
    }
    if (format === 'short') {
      return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
    }
    // Full format: "Sat, Sep 15, 2026 • 19:30"
    const dateStr = date.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric', year: 'numeric' });
    const timeStr = date.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', hour12: false });
    return `${dateStr} • ${timeStr}`;
  }
}
```

---

## 5. Step-by-Step Implementation Sequence
1. **Implement Tactile Button & Directives:**
   - Create `TactileButtonComponent` with size/variant props, loading spinner, and spring press feedback.
   - Create `SpringPressDirective` (`[appSpringPress]`) using `@HostListener('mousedown')` and CSS scale classes.
2. **Implement Glass Card Component:**
   - Create `GlassCardComponent` with backdrop blur, customizable elevation, subtle border, and rounded corners.
3. **Implement Hold Countdown Progress Ring:**
   - Create `HoldCountdownComponent` calculating remaining seconds, SVG stroke offset, formatting `MM:SS`, and emitting `(expired)` at 0 seconds.
4. **Implement Status Badge Component:**
   - Create `StatusBadgeComponent` mapping statuses (`AVAILABLE`, `HELD`, `SOLD`, `PUBLISHED`, `USED`, `CANCELLED`) to pill colors and labels.
5. **Implement Skeleton Loader Component:**
   - Create `SkeletonLoaderComponent` supporting text lines, rectangular card blocks, and circular avatar shapes with animated shimmer gradients.
6. **Implement QR Modal Component:**
   - Create `QrModalComponent` utilizing `@angular/material/dialog`, displaying large QR image, alphanumeric code, and copy-to-clipboard button.
7. **Implement Formatting Pipes:**
   - Write `CurrencyFormatPipe` and `DateFormatPipe`.
8. **Write Unit Tests:**
   - Test button click emission and disabled/loading suppression.
   - Test hold countdown timer ticks and `(expired)` output trigger.
   - Test currency and date pipe edge cases.

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
```
- [ ] All components, directives, and pipes compile cleanly as standalone units.
- [ ] `TactileButtonComponent` applies spring press damping and sheen sweep on hover.
- [ ] `HoldCountdownComponent` updates SVG circle progress and pulses on $< 120$ seconds.
- [ ] `CurrencyFormatPipe` and `DateFormatPipe` produce consistent localized formatting.
- [ ] Unit tests pass with 100% assertion success.
- [ ] Task file is moved to `.ai/tasks/completed/phase-09-frontend-portal/003-shared-sensory-ui-components-library.md`.
