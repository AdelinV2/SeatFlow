# TASK-P09-012: View Transitions API, Accessibility Audit & Full Test Suite

## 1. Task Metadata
- **Task ID:** `TASK-P09-012`
- **Git Branch:** `feat/p09-012-polish-and-testing`
- **Target Module:** `frontend/`
- **Phase:** `Phase 09 - Frontend Portal`
- **Related Specs:** `.ai/architecture/07-frontend-specification.md`, `frontend/AGENTS.md`, `.ai/SeatFlow-Architecture-and-Implementation-Spec.md` (Section 17)
- **Related ADRs:** `None`
- **Status:** `COMPLETED`

---

## 2. Objective & Invariants
Polish the SeatFlow frontend with modern browser capabilities, accessibility (a11y) compliance, performance optimizations, and complete test suite verification. Configure the **View Transitions API** (`withViewTransitions()`) for seamless cross-fading and banner morphing across route transitions, conduct a **WCAG 2.1 AA accessibility audit** (complete keyboard navigation for the SVG seat map, ARIA attributes, color contrast verification), and verify the complete Jasmine/Karma unit and slice test suite.

### Critical Invariants to Enforce:
- [x] **Seamless View Transitions:** Enable Angular's `withViewTransitions()` with custom morph animations for event hero banners and seat map transitions.
- [x] **WCAG 2.1 AA Compliant Seat Map Navigation:** The interactive SVG seat map must support full keyboard navigation (Arrow keys to navigate cells, `Enter`/`Space` to toggle selection, `aria-live` announcements for seat selection and price recalculation).
- [x] **Modal Focus Trapping:** All modal dialogs (QR modal, hold expiration modal, cancellation prompts) must trap keyboard focus using `@angular/cdk/a11y`.
- [x] **Color Contrast Verification:** All text elements in both Midnight Slate (Dark) and Warm Alabaster (Light) themes must exceed the WCAG AA minimum contrast ratio of $4.5:1$ for normal text and $3:1$ for large text.
- [x] **Full Test Suite Clean Pass:** All unit and slice tests across all services, interceptors, guards, and components must pass with 0 failures under `ChromeHeadless`.

---

## 3. Exact File Inventory
- `[NEW]` `frontend/src/app/core/a11y/keyboard-seat-nav.directive.ts`
- `[NEW]` `frontend/src/app/core/a11y/keyboard-seat-nav.directive.spec.ts`
- `[MODIFY]` `frontend/src/styles.scss`
- `[MODIFY]` `frontend/src/app/app.config.ts`
- `[MODIFY]` `frontend/src/app/features/booking/seat-map/seat-map.component.html`
- `[MODIFY]` `frontend/src/app/features/booking/seat-map/seat-map.component.ts`
- `[MODIFY]` `frontend/src/app/features/booking/seat-map/seat-map.component.spec.ts`
- `[NEW]` `frontend/src/app/app.integration.spec.ts`

---

## 4. Technical Specifications & Contracts

### 4.1 View Transitions Configuration (`src/styles.scss`)

```scss
/* View Transitions API Styling */
@keyframes vt-fade-in {
  from { opacity: 0; }
  to { opacity: 1; }
}

@keyframes vt-fade-out {
  from { opacity: 1; }
  to { opacity: 0; }
}

@keyframes vt-slide-from-right {
  from { transform: translateX(2rem); opacity: 0; }
  to { transform: translateX(0); opacity: 1; }
}

::view-transition-old(root) {
  animation: 200ms cubic-bezier(0.4, 0, 1, 1) both vt-fade-out;
}

::view-transition-new(root) {
  animation: 250ms cubic-bezier(0, 0, 0.2, 1) both vt-fade-in;
}

.view-transition-hero {
  view-transition-name: hero-banner;
}

::view-transition-old(hero-banner),
::view-transition-new(hero-banner) {
  animation-duration: 350ms;
  animation-timing-function: cubic-bezier(0.34, 1.56, 0.64, 1);
}
```

### 4.2 Keyboard Seat Navigation Directive (`src/app/core/a11y/keyboard-seat-nav.directive.ts`)

```typescript
import { Directive, ElementRef, HostListener, inject, input, output } from '@angular/core';

@Directive({
  selector: '[appKeyboardSeatNav]',
  standalone: true,
})
export class KeyboardSeatNavDirective {
  private readonly el = inject(ElementRef);

  readonly currentRow = input<number>(0);
  readonly currentCol = input<number>(0);
  readonly maxRows = input<number>(1);
  readonly maxCols = input<number>(1);

  readonly navigate = output<{ row: number; col: number }>();
  readonly activate = output<void>();

  @HostListener('keydown', ['$event'])
  handleKeyDown(event: KeyboardEvent): void {
    let nextRow = this.currentRow();
    let nextCol = this.currentCol();
    let handled = false;

    switch (event.key) {
      case 'ArrowUp':
        nextRow = Math.max(0, nextRow - 1);
        handled = true;
        break;
      case 'ArrowDown':
        nextRow = Math.min(this.maxRows() - 1, nextRow + 1);
        handled = true;
        break;
      case 'ArrowLeft':
        nextCol = Math.max(0, nextCol - 1);
        handled = true;
        break;
      case 'ArrowRight':
        nextCol = Math.min(this.maxCols() - 1, nextCol + 1);
        handled = true;
        break;
      case 'Enter':
      case ' ':
        event.preventDefault();
        this.activate.emit();
        return;
    }

    if (handled) {
      event.preventDefault();
      this.navigate.emit({ row: nextRow, col: nextCol });
    }
  }
}
```

### 4.3 ARIA Roles & Accessibility Attributes for Seat Map

```html
<!-- Accessibility Attributes on Seat Node -->
<g
  role="gridcell"
  [attr.aria-label]="'Row ' + seat.rowLabel + ', Seat ' + seat.seatNumber + ', Price ' + (seat.price | sfCurrency) + ', Status ' + seat.status"
  [attr.aria-selected]="selectedSeatIds().has(seat.id)"
  [attr.aria-disabled]="!seat.isActive || seat.status === 'SOLD' || seat.status === 'RESERVED'"
  tabindex="0"
  class="focus:outline-none focus:ring-2 focus:ring-indigo-400 rounded-full"
>
  <!-- SVG Seat Elements -->
</g>
```

---

## 5. Step-by-Step Implementation Sequence
1. **Configure View Transitions:**
   - Ensure `withViewTransitions()` is active in `app.config.ts`.
   - Add view transition CSS rules in `src/styles.scss` for page cross-fades and hero banner morphing.
2. **Implement KeyboardSeatNavDirective & ARIA Roles:**
   - Create `src/app/core/a11y/keyboard-seat-nav.directive.ts`.
   - Apply `role="grid"`, `role="row"`, and `role="gridcell"` to SVG seat map container and nodes.
   - Add `aria-live="polite"` container announcing selection updates for screen readers.
3. **Audit Color Contrast & Focus Rings:**
   - Check all muted text and badge colors against dark canvas `#0B0F19` and light canvas `#F8FAFC` to ensure $> 4.5:1$ contrast.
   - Ensure clear keyboard focus outlines on all interactive elements.
4. **Write End-to-End Navigation Integration Test:**
   - Create `src/app/app.integration.spec.ts` testing catalog -> details -> seat map -> checkout route navigation flow.
5. **Run Full Test Suite:**
   - Execute test verification command and confirm 100% passing tests.

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```bash
cd frontend && npm.cmd test -- --watch=false --browsers=ChromeHeadless
```
- [x] View Transitions API operates smoothly during route transitions.
- [x] SVG Seat map supports full keyboard arrow navigation and screen reader ARIA labels.
- [x] Contrast ratios meet WCAG 2.1 AA standards across Dark and Light themes.
- [x] Complete frontend unit and integration test suite passes with zero errors (352/352 passing tests).
- [x] Task file is moved to `.ai/tasks/completed/phase-09-frontend-portal/012-performance-view-transitions-a11y-and-test-suite.md`.
