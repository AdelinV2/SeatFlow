# TASK-P09-010: Admin Event Management, Banner Preset Gallery & Section Pricing Matrix

## 1. Task Metadata
- **Task ID:** `TASK-P09-010`
- **Git Branch:** `feat/p09-010-admin-events-pricing`
- **Target Module:** `frontend/src/app/features/admin/events/`, `frontend/src/app/services/`
- **Phase:** `Phase 09 - Frontend Portal`
- **Related Specs:** `.ai/architecture/06-api-contracts.md` (Section 2.3), `.ai/architecture/07-frontend-specification.md` (Section 3, 4.2), `frontend/AGENTS.md`
- **Related ADRs:** `ADR-003` (Automated Event Completion and Lifecycle Reconciliation)
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the administrative event management interface guarded by `admin.guard.ts`. This includes the paginated/sortable event management table (`/admin/events`), the Event Editor form (`/admin/events/new` and `/admin/events/:id/edit`) featuring a **16:9 Live Banner Preview with Curated Preset High-Res Gallery (Option 1)** and custom HTTPS URL input, event lifecycle state transitions (Draft -> Published -> Cancelled), and the Section Pricing Matrix manager (`/admin/events/:id/pricing`).

### Critical Invariants to Enforce:
- [ ] **Admin Route Authorization Guard:** All routes under `/admin/**` are strictly guarded by `admin.guard.ts` (requiring `ROLE_ADMIN`).
- [ ] **Banner Live Preview & Preset Gallery (Option 1):** The event editor must provide a live 16:9 / 21:9 preview box with glass borders, a 1-click curated preset gallery (Concert, Theatre, Electronic, Sports, Festival, Comedy), and an HTTPS custom image URL input.
- [ ] **Event Lifecycle State Transitions:** Enforce allowable transitions in UI actions (Draft -> Published, Published -> Cancelled; Completed events are locked per ADR-003).
- [ ] **Section Pricing Validation:** Every active section in the venue must have a positive gross price ($\ge 0.00$) configured before publishing.
- [ ] **Material Table Standards:** Event list utilizes `MatTable`, `MatPaginator`, `MatSort` wrapped in responsive Tailwind glassmorphic containers.

---

## 3. Exact File Inventory
- `[NEW]` `frontend/src/app/models/admin-event.model.ts`
- `[NEW]` `frontend/src/app/services/admin-event-api.service.ts`
- `[NEW]` `frontend/src/app/features/admin/events/banner-gallery-picker/banner-gallery-picker.component.ts`
- `[NEW]` `frontend/src/app/features/admin/events/banner-gallery-picker/banner-gallery-picker.component.html`
- `[NEW]` `frontend/src/app/features/admin/events/banner-gallery-picker/banner-gallery-picker.component.scss`
- `[NEW]` `frontend/src/app/features/admin/events/admin-event-list/admin-event-list.component.ts`
- `[NEW]` `frontend/src/app/features/admin/events/admin-event-list/admin-event-list.component.html`
- `[NEW]` `frontend/src/app/features/admin/events/admin-event-list/admin-event-list.component.scss`
- `[NEW]` `frontend/src/app/features/admin/events/admin-event-editor/admin-event-editor.component.ts`
- `[NEW]` `frontend/src/app/features/admin/events/admin-event-editor/admin-event-editor.component.html`
- `[NEW]` `frontend/src/app/features/admin/events/admin-event-editor/admin-event-editor.component.scss`
- `[NEW]` `frontend/src/app/features/admin/events/admin-pricing-manager/admin-pricing-manager.component.ts`
- `[NEW]` `frontend/src/app/features/admin/events/admin-pricing-manager/admin-pricing-manager.component.html`
- `[NEW]` `frontend/src/app/features/admin/events/admin-pricing-manager/admin-pricing-manager.component.scss`
- `[NEW]` `frontend/src/app/features/admin/events/banner-gallery-picker/banner-gallery-picker.component.spec.ts`
- `[NEW]` `frontend/src/app/features/admin/events/admin-event-editor/admin-event-editor.component.spec.ts`
- `[MODIFY]` `frontend/src/app/app.routes.ts`

---

## 4. Technical Specifications & Contracts

### 4.1 Models & Admin API Service (`src/app/services/admin-event-api.service.ts`)

```typescript
export interface CreateEventRequest {
  title: string;
  description: string;
  category: string;
  bannerUrl: string;
  eventDate: string;
  venueId: string;
}

export interface UpdateEventRequest {
  title?: string;
  description?: string;
  category?: string;
  bannerUrl?: string;
  eventDate?: string;
  status?: 'DRAFT' | 'PUBLISHED' | 'CANCELLED';
}

export interface ConfigurePricingRequest {
  tiers: {
    sectionId: string;
    price: number;
  }[];
}
```

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { PagedResult, EventDetail } from '../models/event.model';
import { CreateEventRequest, UpdateEventRequest, ConfigurePricingRequest } from '../models/admin-event.model';

@Injectable({ providedIn: 'root' })
export class AdminEventApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/admin/events';

  getAdminEvents(params: { status?: string; search?: string; page?: number; size?: number }): Observable<PagedResult<EventDetail>> {
    let httpParams = new HttpParams();
    if (params.status) httpParams = httpParams.set('status', params.status);
    if (params.search) httpParams = httpParams.set('search', params.search);
    if (params.page !== undefined) httpParams = httpParams.set('page', params.page.toString());
    if (params.size !== undefined) httpParams = httpParams.set('size', params.size.toString());

    return this.http.get<PagedResult<EventDetail>>(this.baseUrl, { params: httpParams });
  }

  createEvent(req: CreateEventRequest): Observable<EventDetail> {
    return this.http.post<EventDetail>(this.baseUrl, req);
  }

  updateEvent(id: string, req: UpdateEventRequest): Observable<EventDetail> {
    return this.http.put<EventDetail>(`${this.baseUrl}/${id}`, req);
  }

  configurePricing(eventId: string, req: ConfigurePricingRequest): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/${eventId}/pricing`, req);
  }
}
```

### 4.2 Banner Gallery Picker Component (`src/app/features/admin/events/banner-gallery-picker/`)

```typescript
import { Component, ChangeDetectionStrategy, input, output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

export interface BannerPreset {
  id: string;
  title: string;
  category: string;
  url: string;
}

@Component({
  selector: 'app-banner-gallery-picker',
  standalone: true,
  imports: [CommonModule, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './banner-gallery-picker.component.html',
  styleUrl: './banner-gallery-picker.component.scss',
})
export class BannerGalleryPickerComponent {
  readonly currentBannerUrl = input<string>('');
  readonly bannerSelected = output<string>();

  readonly customUrl = signal<string>('');

  readonly presets: BannerPreset[] = [
    {
      id: 'concert-1',
      title: 'Neon Symphony Live',
      category: 'CONCERT',
      url: 'https://images.unsplash.com/photo-1514525253161-7a46d19cd819?auto=format&fit=crop&w=1600&q=80',
    },
    {
      id: 'theatre-1',
      title: 'Royal Opera House Stage',
      category: 'THEATRE',
      url: 'https://images.unsplash.com/photo-1507676184212-d03ab07a01bf?auto=format&fit=crop&w=1600&q=80',
    },
    {
      id: 'electronic-1',
      title: 'Festival Laser Arena',
      category: 'FESTIVAL',
      url: 'https://images.unsplash.com/photo-1470225620780-dba8ba36b745?auto=format&fit=crop&w=1600&q=80',
    },
    {
      id: 'sports-1',
      title: 'Basketball Championship Arena',
      category: 'SPORTS',
      url: 'https://images.unsplash.com/photo-1546519638-68e109498ffc?auto=format&fit=crop&w=1600&q=80',
    },
    {
      id: 'comedy-1',
      title: 'Vintage Spotlight Microphone',
      category: 'COMEDY',
      url: 'https://images.unsplash.com/photo-1585699324551-f6c309eedeca?auto=format&fit=crop&w=1600&q=80',
    },
    {
      id: 'symphony-1',
      title: 'Classical Philharmonic Orchestra',
      category: 'SYMPHONY',
      url: 'https://images.unsplash.com/photo-1465847899084-d164df4dedc6?auto=format&fit=crop&w=1600&q=80',
    },
  ];

  selectPreset(url: string): void {
    this.customUrl.set('');
    this.bannerSelected.emit(url);
  }

  applyCustomUrl(): void {
    if (this.customUrl().trim()) {
      this.bannerSelected.emit(this.customUrl().trim());
    }
  }
}
```

```html
<div class="space-y-4">
  <!-- Live 16:9 Preview Window -->
  <div class="relative w-full aspect-video rounded-2xl overflow-hidden border border-[var(--color-border)] bg-slate-900 shadow-xl flex items-center justify-center">
    @if (currentBannerUrl()) {
      <img [src]="currentBannerUrl()" alt="Banner Preview" class="w-full h-full object-cover transition-all duration-300" />
      <div class="absolute inset-0 bg-gradient-to-t from-black/80 via-transparent to-black/20 pointer-events-none"></div>
      <span class="absolute top-3 right-3 px-2.5 py-1 text-[11px] font-mono font-bold bg-black/60 backdrop-blur-md text-white rounded-lg border border-white/10">
        16:9 LIVE PREVIEW
      </span>
    } @else {
      <div class="text-center text-muted p-6">
        <span class="text-3xl block mb-2">🖼️</span>
        <p class="text-xs font-semibold">Select a preset banner below or paste an HTTPS URL</p>
      </div>
    }
  </div>

  <!-- Curated High-Res Preset Gallery -->
  <div>
    <label class="block text-xs font-bold uppercase tracking-wider text-muted mb-2">Curated High-Res Presets (1-Click Select)</label>
    <div class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-6 gap-2.5">
      @for (preset of presets; track preset.id) {
        <button
          type="button"
          (click)="selectPreset(preset.url)"
          class="group relative aspect-video rounded-xl overflow-hidden border transition-all cursor-pointer select-none"
          [ngClass]="currentBannerUrl() === preset.url ? 'border-indigo-500 ring-2 ring-indigo-500/50 scale-[1.02]' : 'border-[var(--color-border)] hover:border-indigo-400/50'"
        >
          <img [src]="preset.url" [alt]="preset.title" class="w-full h-full object-cover group-hover:scale-110 transition-all duration-300" />
          <div class="absolute inset-0 bg-black/40 group-hover:bg-black/20 transition-all"></div>
          <span class="absolute bottom-1 left-1.5 right-1.5 text-[9px] font-bold text-white truncate text-left drop-shadow">
            {{ preset.category }}
          </span>
        </button>
      }
    </div>
  </div>

  <!-- Custom HTTPS URL Input -->
  <div class="flex items-center gap-2">
    <input
      type="url"
      [ngModel]="customUrl()"
      (ngModelChange)="customUrl.set($event)"
      placeholder="Or enter custom HTTPS image URL (Cloudinary, S3, Unsplash)..."
      class="flex-1 px-3.5 py-2 text-xs bg-[var(--color-surface-elevated)] border border-[var(--color-border)] rounded-xl text-[var(--color-text-primary)] focus:outline-none focus:border-indigo-500"
    />
    <button
      type="button"
      (click)="applyCustomUrl()"
      class="px-4 py-2 text-xs font-semibold bg-[var(--color-surface-elevated)] hover:bg-slate-700/30 border border-[var(--color-border)] rounded-xl transition-all cursor-pointer"
    >
      Apply URL
    </button>
  </div>
</div>
```

---

## 5. Step-by-Step Implementation Sequence
1. **Define Admin Event Models and API Service:**
   - Create `src/app/models/admin-event.model.ts` and `src/app/services/admin-event-api.service.ts`.
2. **Build BannerGalleryPickerComponent:**
   - Implement 16:9 preview, preset tiles, and custom URL handler.
3. **Build AdminEventListComponent (`/admin/events`):**
   - Implement `MatTable` with columns: Banner, Title, Category, Date, Status, Actions.
   - Add status filtering tabs (`ALL`, `DRAFT`, `PUBLISHED`, `CANCELLED`).
   - Add lifecycle action triggers (Publish modal, Cancel confirmation).
4. **Build AdminEventEditorComponent (`/admin/events/new`, `:id/edit`):**
   - Form with Title, Category, Venue Selector dropdown, Event Date/Time, Description, and `<app-banner-gallery-picker>`.
5. **Build AdminPricingManagerComponent (`/admin/events/:id/pricing`):**
   - Load venue sections, render pricing input rows per section, validate gross prices, and dispatch `POST /api/admin/events/:id/pricing`.
6. **Register Admin Routes & Write Unit Tests:**
   - Wire routes in `app.routes.ts` protected by `adminGuard`.
   - Unit tests for preset banner selection and pricing tier form submission.

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
```
- [ ] Admin event routes are guarded by `adminGuard`.
- [ ] Banner gallery renders 16:9 preview and allows 1-click preset selection.
- [ ] Event publication and status transition flows update event state.
- [ ] Section pricing matrix saves tier prices for all sections.
- [ ] All unit tests pass cleanly.
- [ ] Task file is moved to `.ai/tasks/completed/phase-09-frontend-portal/010-admin-event-management-banner-gallery-pricing.md`.
