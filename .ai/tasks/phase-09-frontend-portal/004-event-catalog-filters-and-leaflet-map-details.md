# TASK-P09-004: Public Event Catalog, Interactive Calendar View & Leaflet.js Details Map

## 1. Task Metadata
- **Task ID:** `TASK-P09-004`
- **Git Branch:** `feat/p09-004-event-catalog`
- **Target Module:** `frontend/src/app/features/events/`, `frontend/src/app/services/`
- **Phase:** `Phase 09 - Frontend Portal`
- **Related Specs:** `.ai/architecture/06-api-contracts.md` (Section 2.3), `.ai/architecture/07-frontend-specification.md` (Section 3, 4.1), `frontend/AGENTS.md`
- **Related ADRs:** `ADR-003` (Automated Event Completion & Lifecycle Reconciliation)
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the public event discovery experience. This includes the public catalog page (`EventListComponent` on `/` and `/events`) featuring a hero carousel, an **Interactive Monthly Event Calendar View (`EventCalendarComponent`)** supporting both a **full desktop month grid with event chips** and a **compact mobile dot matrix view** (with `<` `>` month navigation and 1-click day filtering), category pills, debounced search, scroll-down reveal animations, and the event details page (`EventDetailComponent` on `/events/:id`) featuring 16:9 hero media, pricing tier breakdowns, a sticky "Select Seats" CTA, and an interactive **Leaflet.js** map with theme-adaptive CartoDB tiles and external navigation links.

### Critical Invariants to Enforce:
- [ ] **Interactive Monthly Calendar Component (`EventCalendarComponent`):**
  - **Desktop View ($\ge 768\text{px}$):** Full 7-column month grid (LUN, MAR, MIE, J, VIN, S, D) displaying event title chips on scheduled days.
  - **Mobile View ($< 768\text{px}$):** Compact monthly calendar with blue event indicator dots under dates that contain active events.
  - **Month Navigation & Day Selection:** `<` and `>` controls for browsing months, clicking a day filters the event list to that specific date.
- [ ] **100% Free Maps (Zero Paid Google Maps API):** Venue map must use Leaflet.js with theme-adaptive tiles (`CartoDB Dark Matter` in dark mode and `CartoDB Positron` in light mode) and dynamic tile-switching when the user toggles themes.
- [ ] **External Navigation Links:** Venue map popup must provide deep links to open the venue coordinates in Google Maps, Apple Maps, and Waze.
- [ ] **Only Published & Future Events (ADR-003):** Public catalog queries `GET /api/events` which returns only published events whose `eventDate > now()`. Expired or draft events display a friendly 404 / unavailable message.
- [ ] **Scroll-Down Reveal Animations:** Event cards and calendar sections implement smooth entrance animations on scroll (`animate-fade-in-up`, `hover:-translate-y-1.5 hover:shadow-xl transition-all duration-300`).

---

## 3. Exact File Inventory
- `[NEW]` `frontend/src/app/models/event.model.ts`
- `[NEW]` `frontend/src/app/services/event-api.service.ts`
- `[NEW]` `frontend/src/app/features/events/event-calendar/event-calendar.component.ts`
- `[NEW]` `frontend/src/app/features/events/event-calendar/event-calendar.component.html`
- `[NEW]` `frontend/src/app/features/events/event-calendar/event-calendar.component.scss`
- `[NEW]` `frontend/src/app/features/events/event-card/event-card.component.ts`
- `[NEW]` `frontend/src/app/features/events/event-card/event-card.component.html`
- `[NEW]` `frontend/src/app/features/events/event-card/event-card.component.scss`
- `[NEW]` `frontend/src/app/features/events/venue-map-view/venue-map-view.component.ts`
- `[NEW]` `frontend/src/app/features/events/venue-map-view/venue-map-view.component.html`
- `[NEW]` `frontend/src/app/features/events/venue-map-view/venue-map-view.component.scss`
- `[NEW]` `frontend/src/app/features/events/event-list/event-list.component.ts`
- `[NEW]` `frontend/src/app/features/events/event-list/event-list.component.html`
- `[NEW]` `frontend/src/app/features/events/event-list/event-list.component.scss`
- `[NEW]` `frontend/src/app/features/events/event-detail/event-detail.component.ts`
- `[NEW]` `frontend/src/app/features/events/event-detail/event-detail.component.html`
- `[NEW]` `frontend/src/app/features/events/event-detail/event-detail.component.scss`
- `[NEW]` `frontend/src/app/features/events/event-calendar/event-calendar.component.spec.ts`
- `[NEW]` `frontend/src/app/services/event-api.service.spec.ts`
- `[NEW]` `frontend/src/app/features/events/event-list/event-list.component.spec.ts`
- `[NEW]` `frontend/src/app/features/events/event-detail/event-detail.component.spec.ts`
- `[MODIFY]` `frontend/src/app/app.routes.ts`

---

## 4. Technical Specifications & Contracts

### 4.1 Models (`src/app/models/event.model.ts`)

```typescript
export type EventCategory = 'CONCERT' | 'THEATRE' | 'SPORTS' | 'FESTIVAL' | 'COMEDY' | 'SYMPHONY' | 'OTHER';
export type EventStatus = 'DRAFT' | 'PUBLISHED' | 'CANCELLED' | 'COMPLETED';

export interface EventPricingTier {
  sectionId: string;
  sectionName: string;
  price: number;
  currency: string;
}

export interface EventSummary {
  id: string;
  title: string;
  description: string;
  category: EventCategory;
  bannerUrl: string;
  eventDate: string;
  venueId: string;
  venueName: string;
  status: EventStatus;
  minPrice: number;
  maxPrice: number;
}

export interface EventDetail extends EventSummary {
  venueAddress: string;
  latitude?: number;
  longitude?: number;
  pricingTiers: EventPricingTier[];
  totalCapacity?: number;
  availableSeatsCount?: number;
}

export interface PagedResult<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  isFirst: boolean;
  isLast: boolean;
}

export interface CalendarDay {
  date: Date;
  dayNumber: number;
  isCurrentMonth: boolean;
  isToday: boolean;
  isSelected: boolean;
  events: EventSummary[];
}
```

### 4.2 Interactive Calendar Component (`src/app/features/events/event-calendar/`)

```typescript
import { Component, ChangeDetectionStrategy, input, output, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { EventSummary, CalendarDay } from '../../../models/event.model';

@Component({
  selector: 'app-event-calendar',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './event-calendar.component.html',
  styleUrl: './event-calendar.component.scss',
})
export class EventCalendarComponent {
  readonly events = input<EventSummary[]>([]);
  readonly selectedDate = input<Date | null>(null);
  readonly dateSelected = output<Date | null>();

  readonly currentMonth = signal<Date>(new Date());
  readonly weekDayLabels = ['LUN', 'MAR', 'MIE', 'J', 'VIN', 'S', 'D'];

  readonly formattedMonthHeader = computed(() => {
    const d = this.currentMonth();
    return d.toLocaleDateString('ro-RO', { month: 'long', year: 'numeric' });
  });

  readonly calendarDays = computed<CalendarDay[]>(() => {
    const month = this.currentMonth();
    const year = month.getFullYear();
    const monthIndex = month.getMonth();

    const firstDayOfMonth = new Date(year, monthIndex, 1);
    const lastDayOfMonth = new Date(year, monthIndex + 1, 0);

    // Monday-based week index (0 = Monday, 6 = Sunday)
    let startDay = firstDayOfMonth.getDay() - 1;
    if (startDay === -1) startDay = 6;

    const days: CalendarDay[] = [];
    const today = new Date();
    const eventList = this.events();
    const selected = this.selectedDate();

    // Previous month padding
    const prevMonthLastDay = new Date(year, monthIndex, 0).getDate();
    for (let i = startDay - 1; i >= 0; i--) {
      const date = new Date(year, monthIndex - 1, prevMonthLastDay - i);
      days.push(this.createCalendarDay(date, false, today, selected, eventList));
    }

    // Current month days
    for (let i = 1; i <= lastDayOfMonth.getDate(); i++) {
      const date = new Date(year, monthIndex, i);
      days.push(this.createCalendarDay(date, true, today, selected, eventList));
    }

    // Next month padding to fill 35 or 42 grid cells
    const remaining = (7 - (days.length % 7)) % 7;
    for (let i = 1; i <= remaining; i++) {
      const date = new Date(year, monthIndex + 1, i);
      days.push(this.createCalendarDay(date, false, today, selected, eventList));
    }

    return days;
  });

  private createCalendarDay(date: Date, isCurrentMonth: boolean, today: Date, selected: Date | null, events: EventSummary[]): CalendarDay {
    const isToday = date.toDateString() === today.toDateString();
    const isSelected = selected ? date.toDateString() === selected.toDateString() : false;
    const dayEvents = events.filter((e) => new Date(e.eventDate).toDateString() === date.toDateString());

    return {
      date,
      dayNumber: date.getDate(),
      isCurrentMonth,
      isToday,
      isSelected,
      events: dayEvents,
    };
  }

  prevMonth(): void {
    this.currentMonth.update((d) => new Date(d.getFullYear(), d.getMonth() - 1, 1));
  }

  nextMonth(): void {
    this.currentMonth.update((d) => new Date(d.getFullYear(), d.getMonth() + 1, 1));
  }

  selectDay(day: CalendarDay): void {
    if (day.isSelected) {
      this.dateSelected.emit(null); // Deselect on second click
    } else {
      this.dateSelected.emit(day.date);
    }
  }
}
```

```html
<div class="bg-[var(--color-surface)] border border-[var(--color-border)] rounded-2xl p-4 md:p-6 shadow-sm">
  <!-- Month Header Controls -->
  <div class="flex items-center justify-between mb-6">
    <div class="flex items-center gap-2">
      <button (click)="prevMonth()" class="p-2 rounded-xl hover:bg-slate-800/10 dark:hover:bg-slate-800 text-[var(--color-text-secondary)] transition-all cursor-pointer">
        ‹
      </button>
      <h3 class="text-base md:text-lg font-bold capitalize text-[var(--color-text-primary)]">
        {{ formattedMonthHeader() }}
      </h3>
      <button (click)="nextMonth()" class="p-2 rounded-xl hover:bg-slate-800/10 dark:hover:bg-slate-800 text-[var(--color-text-secondary)] transition-all cursor-pointer">
        ›
      </button>
    </div>

    @if (selectedDate()) {
      <button (click)="dateSelected.emit(null)" class="text-xs text-indigo-400 font-semibold hover:underline cursor-pointer">
        Clear Date Filter
      </button>
    }
  </div>

  <!-- Weekday Columns Header -->
  <div class="grid grid-cols-7 gap-1 text-center text-xs font-bold text-muted uppercase tracking-wider mb-2">
    @for (label of weekDayLabels; track label) {
      <div class="py-1">{{ label }}</div>
    }
  </div>

  <!-- Desktop Full Grid View (Hidden on Mobile) -->
  <div class="hidden md:grid grid-cols-7 gap-1.5">
    @for (day of calendarDays(); track day.date.toISOString()) {
      <div
        (click)="selectDay(day)"
        class="min-h-[90px] p-2 rounded-xl border transition-all cursor-pointer select-none flex flex-col justify-between"
        [ngClass]="[
          !day.isCurrentMonth ? 'opacity-30 border-transparent bg-transparent' : 'border-[var(--color-border-subtle)] bg-[var(--color-canvas)]',
          day.isSelected ? 'ring-2 ring-indigo-500 bg-indigo-500/10 border-indigo-500/50' : 'hover:border-indigo-400/40',
          day.isToday ? 'font-bold' : ''
        ]"
      >
        <span class="text-xs" [ngClass]="day.isToday ? 'text-indigo-400' : 'text-[var(--color-text-primary)]'">
          {{ day.dayNumber }}
        </span>

        <div class="space-y-1 overflow-hidden">
          @for (event of day.events.slice(0, 2); track event.id) {
            <div class="px-1.5 py-0.5 text-[10px] font-semibold truncate rounded bg-indigo-500/20 text-indigo-300 border border-indigo-500/30">
              {{ event.title }}
            </div>
          }
          @if (day.events.length > 2) {
            <span class="text-[9px] text-muted font-bold block">+{{ day.events.length - 2 }} more</span>
          }
        </div>
      </div>
    }
  </div>

  <!-- Mobile Dot Matrix View (Shown on Mobile) -->
  <div class="grid md:hidden grid-cols-7 gap-1 text-center">
    @for (day of calendarDays(); track day.date.toISOString()) {
      <button
        (click)="selectDay(day)"
        class="py-2.5 rounded-xl flex flex-col items-center justify-center transition-all cursor-pointer"
        [ngClass]="[
          !day.isCurrentMonth ? 'opacity-30' : '',
          day.isSelected ? 'bg-indigo-600 text-white shadow-md' : 'hover:bg-slate-800/10'
        ]"
      >
        <span class="text-xs font-semibold">{{ day.dayNumber }}</span>
        @if (day.events.length > 0) {
          <span
            class="w-1.5 h-1.5 rounded-full mt-1"
            [ngClass]="day.isSelected ? 'bg-white' : 'bg-indigo-500 animate-pulse'"
          ></span>
        } @else {
          <span class="w-1.5 h-1.5 mt-1"></span>
        }
      </button>
    }
  </div>
</div>
```

---

## 5. Step-by-Step Implementation Sequence
1. **Implement Event Models & API Service:**
   - Create `src/app/models/event.model.ts` and `src/app/services/event-api.service.ts`.
2. **Build EventCalendarComponent:**
   - Implement Monday-based month grid calculation, navigation `<` `>`, and desktop event chips + mobile dot matrix views.
3. **Build Leaflet VenueMapViewComponent:**
   - Implement `VenueMapViewComponent` with theme-adaptive CartoDB Dark Matter / Positron tiles.
4. **Build EventCardComponent:**
   - Create `EventCardComponent` with 16:9 banner preview, price pill, and scroll reveal animations (`animate-fade-in-up`).
5. **Implement EventListComponent (Public Catalog):**
   - Hero Carousel at top.
   - `<app-event-calendar>` right below hero for date-based browsing.
   - Category pill bar and debounced search bar.
   - Responsive event grid with scroll-reveal entrance.
6. **Implement EventDetailComponent:**
   - Hero banner, pricing tiers grid, Leaflet venue map, sticky CTA bar.
7. **Write Unit Tests:**
   - Test calendar month transitions, date selection toggle, and Leaflet map tile-switching.

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
```
- [ ] Interactive event calendar renders desktop full grid with event pills and mobile dot matrix view.
- [ ] Clicking a calendar date filters event cards to that specific date.
- [ ] Leaflet map initializes accurately with theme-adaptive tiles and external navigation links.
- [ ] Scroll animations and responsive mobile layouts render smoothly at 60 FPS.
- [ ] All unit tests pass cleanly.
- [ ] Task file is moved to `.ai/tasks/completed/phase-09-frontend-portal/004-event-catalog-filters-and-leaflet-map-details.md`.
