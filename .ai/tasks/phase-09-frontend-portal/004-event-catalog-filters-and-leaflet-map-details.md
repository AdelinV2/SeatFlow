# TASK-P09-004: Public Event Catalog, Category Filters & Leaflet.js Interactive Details Map

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
Implement the public event discovery experience. This includes the public catalog page (`EventListComponent` on `/` and `/events`) featuring a hero carousel, category pills, debounced search, responsive event cards with hover lift, and the event details page (`EventDetailComponent` on `/events/:id`) featuring 16:9 hero media, pricing tier breakdowns, a sticky "Select Seats" CTA, and an interactive **Leaflet.js** map with theme-adaptive CartoDB tiles and external navigation links.

### Critical Invariants to Enforce:
- [ ] **100% Free Maps (Zero Paid Google Maps API):** Venue map must use Leaflet.js with theme-adaptive tiles (`CartoDB Dark Matter` in dark mode and `CartoDB Positron` in light mode) and dynamic tile-switching when the user toggles themes.
- [ ] **External Navigation Links:** Venue map popup must provide deep links to open the venue coordinates in Google Maps, Apple Maps, and Waze.
- [ ] **Only Published & Future Events (ADR-003):** Public catalog queries `GET /api/events` which returns only published events whose `eventDate > now()`. Expired or draft events display a friendly 404 / unavailable message.
- [ ] **Debounced Reactive Search:** Event catalog search input must debounce emissions by 300ms before triggering API queries via Angular Signals / RxJS.
- [ ] **Sensory UI Hover Physics:** Event cards must implement subtle hover lift (`hover:-translate-y-1.5 hover:shadow-xl transition-all duration-300`).

---

## 3. Exact File Inventory
- `[NEW]` `frontend/src/app/models/event.model.ts`
- `[NEW]` `frontend/src/app/services/event-api.service.ts`
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
```

### 4.2 Event API Service (`src/app/services/event-api.service.ts`)

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { EventSummary, EventDetail, PagedResult, EventCategory } from '../models/event.model';

@Injectable({ providedIn: 'root' })
export class EventApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/events';

  getEvents(params: {
    category?: EventCategory | '';
    search?: string;
    page?: number;
    size?: number;
    sort?: string;
  }): Observable<PagedResult<EventSummary>> {
    let httpParams = new HttpParams();
    if (params.category) httpParams = httpParams.set('category', params.category);
    if (params.search) httpParams = httpParams.set('search', params.search.trim());
    if (params.page !== undefined) httpParams = httpParams.set('page', params.page.toString());
    if (params.size !== undefined) httpParams = httpParams.set('size', params.size.toString());
    if (params.sort) httpParams = httpParams.set('sort', params.sort);

    return this.http.get<PagedResult<EventSummary>>(this.baseUrl, { params: httpParams });
  }

  getEventById(id: string): Observable<EventDetail> {
    return this.http.get<EventDetail>(`${this.baseUrl}/${id}`);
  }
}
```

### 4.3 Leaflet Map Component (`src/app/features/events/venue-map-view/`)

```typescript
import { Component, ChangeDetectionStrategy, input, effect, ElementRef, viewChild, OnDestroy, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import * as L from 'leaflet';
import { ThemeService } from '../../../core/theme/theme.service';

@Component({
  selector: 'app-venue-map-view',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="relative w-full h-72 md:h-80 rounded-2xl overflow-hidden border border-[var(--color-border)] shadow-md">
      <div #mapContainer class="w-full h-full z-0"></div>
      <div class="absolute bottom-3 right-3 z-[400] flex gap-2">
        <a [href]="googleMapsUrl()" target="_blank" rel="noopener noreferrer" class="px-2.5 py-1 text-xs font-semibold bg-slate-900/80 text-white hover:bg-slate-900 backdrop-blur-md rounded-lg shadow transition-all">
          Google Maps
        </a>
        <a [href]="appleMapsUrl()" target="_blank" rel="noopener noreferrer" class="px-2.5 py-1 text-xs font-semibold bg-slate-900/80 text-white hover:bg-slate-900 backdrop-blur-md rounded-lg shadow transition-all">
          Apple Maps
        </a>
        <a [href]="wazeUrl()" target="_blank" rel="noopener noreferrer" class="px-2.5 py-1 text-xs font-semibold bg-slate-900/80 text-white hover:bg-slate-900 backdrop-blur-md rounded-lg shadow transition-all">
          Waze
        </a>
      </div>
    </div>
  `,
  styles: [`
    :host { display: block; }
  `]
})
export class VenueMapViewComponent implements OnDestroy {
  private readonly themeService = inject(ThemeService);
  readonly mapContainer = viewChild.required<ElementRef<HTMLDivElement>>('mapContainer');

  readonly latitude = input.required<number>();
  readonly longitude = input.required<number>();
  readonly venueName = input.required<string>();
  readonly venueAddress = input.required<string>();

  private map?: L.Map;
  private tileLayer?: L.TileLayer;
  private marker?: L.Marker;

  readonly googleMapsUrl = () => `https://www.google.com/maps/search/?api=1&query=${this.latitude()},${this.longitude()}`;
  readonly appleMapsUrl = () => `https://maps.apple.com/?q=${encodeURIComponent(this.venueName())}&ll=${this.latitude()},${this.longitude()}`;
  readonly wazeUrl = () => `https://waze.com/ul?ll=${this.latitude()},${this.longitude()}&navigate=yes`;

  constructor() {
    effect(() => {
      const lat = this.latitude();
      const lng = this.longitude();
      const isDark = this.themeService.isDark();

      if (this.mapContainer()?.nativeElement) {
        this.initOrUpdateMap(lat, lng, isDark);
      }
    });
  }

  private initOrUpdateMap(lat: number, lng: number, isDark: boolean): void {
    const tileUrl = isDark
      ? 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png'
      : 'https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png';

    if (!this.map) {
      this.map = L.map(this.mapContainer().nativeElement, {
        center: [lat, lng],
        zoom: 15,
        zoomControl: false,
        attributionControl: false,
      });

      L.control.zoom({ position: 'topright' }).addTo(this.map);

      this.tileLayer = L.tileLayer(tileUrl, { maxZoom: 19 }).addTo(this.map);

      // Custom pulsing pin icon
      const customIcon = L.divIcon({
        className: 'custom-venue-pin',
        html: `<div class="relative flex items-center justify-center w-8 h-8">
                 <span class="absolute w-8 h-8 rounded-full bg-indigo-500/40 animate-ping"></span>
                 <span class="relative flex items-center justify-center w-6 h-6 rounded-full bg-indigo-600 text-white shadow-lg border-2 border-white">📍</span>
               </div>`,
        iconSize: [32, 32],
        iconAnchor: [16, 16],
      });

      this.marker = L.marker([lat, lng], { icon: customIcon }).addTo(this.map);
      this.marker.bindPopup(`<b>${this.venueName()}</b><br>${this.venueAddress()}`).openPopup();
    } else {
      this.map.setView([lat, lng], 15);
      if (this.tileLayer) {
        this.tileLayer.setUrl(tileUrl);
      }
      if (this.marker) {
        this.marker.setLatLng([lat, lng]);
      }
    }
  }

  ngOnDestroy(): void {
    if (this.map) {
      this.map.remove();
      this.map = undefined;
    }
  }
}
```

---

## 5. Step-by-Step Implementation Sequence
1. **Define Event Models & API Service:**
   - Create `src/app/models/event.model.ts` matching backend `EventSummaryResponse` and `EventDetailResponse`.
   - Implement `src/app/services/event-api.service.ts` with `getEvents` and `getEventById`.
2. **Build VenueMapViewComponent with Leaflet:**
   - Implement `VenueMapViewComponent` using Leaflet `L.map` and CartoDB Dark Matter / Positron tiles reacting to `ThemeService.isDark()`.
   - Add deep links for Google Maps, Apple Maps, and Waze.
3. **Build EventCardComponent:**
   - Create `EventCardComponent` with 16:9 banner preview, status pill, date badge, price range (`$35.00 - $150.00`), and tactile hover physics.
4. **Implement EventListComponent (Public Catalog):**
   - Create hero carousel showcasing featured events.
   - Create category pill bar (`ALL`, `CONCERT`, `THEATRE`, `SPORTS`, `FESTIVAL`, `COMEDY`) with instant Signal filtering.
   - Implement search bar with debounced reactive querying and skeleton shimmer while loading.
5. **Implement EventDetailComponent:**
   - Create event hero banner with glassmorphism backdrop, metadata badges, pricing tiers grid, and `<app-venue-map-view />`.
   - Add sticky bottom bar with "Select Seats" button navigating to `/events/:id/seats`.
6. **Register Routes & Write Unit Tests:**
   - Update `app.routes.ts` mapping `''` -> `EventListComponent`, `'events'` -> `EventListComponent`, `'events/:id'` -> `EventDetailComponent`.
   - Write unit tests for `EventApiService`, `EventListComponent`, and `EventDetailComponent`.

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
```
- [ ] Public event catalog lists events with search, category filtering, and pagination.
- [ ] Leaflet map initializes accurately without Google Maps API keys and dynamically switches tiles on theme changes.
- [ ] External navigation buttons link to Google Maps, Apple Maps, and Waze coordinates.
- [ ] Sticky CTA navigates to `/events/:id/seats`.
- [ ] All unit tests pass cleanly.
- [ ] Task file is moved to `.ai/tasks/completed/phase-09-frontend-portal/004-event-catalog-filters-and-leaflet-map-details.md`.
