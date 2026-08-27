# TASK-P09-011: Admin Venue Layout Designer, 2D Seat Grid Matrix & User Audit

## 1. Task Metadata
- **Task ID:** `TASK-P09-011`
- **Git Branch:** `feat/p09-011-admin-venue-designer`
- **Target Module:** `frontend/src/app/features/admin/venues/`, `frontend/src/app/features/admin/users/`
- **Phase:** `Phase 09 - Frontend Portal`
- **Related Specs:** `.ai/architecture/06-api-contracts.md` (Section 2.1, 2.2), `.ai/architecture/07-frontend-specification.md` (Section 3, 4.1), `frontend/AGENTS.md`
- **Related ADRs:** `None`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the Venue Designer and Seat Matrix layout builder at `/admin/venues/:id/designer`, the Venue creation flow with free **OpenStreetMap Nominatim** address geocoding, and the Admin User Audit table at `/admin/users`, all guarded by `admin.guard.ts`. The venue designer allows administrators to define sections, auto-generate 2D seat grids (Row labels A, B, C... and seat numbers 1, 2, 3...), toggle individual seat active/inactive statuses for aisles and walkways, and view real-time venue capacity.

### Critical Invariants to Enforce:
- [ ] **100% Free Geocoding (Zero Google Maps API):** Address lookup in the venue creator must utilize the free OpenStreetMap Nominatim Geocoding API (`https://nominatim.openstreetmap.org/search`) with draggable pin placement on a Leaflet map.
- [ ] **2D Seat Grid Generator:** Row labels must compute accurately using alphabetical sequences (Row 1 -> `A`, Row 26 -> `Z`, Row 27 -> `AA`, etc.), with seat numbers 1 to $N$.
- [ ] **Interactive Aisle & Walkway Toggle:** Clicking any seat in the designer matrix toggles its `isActive` status (`PATCH /api/admin/venues/{venueId}/sections/{sectionId}/seats/{seatId}`), with disabled seats visually styled as empty walkway space.
- [ ] **Dynamic Venue Capacity Counter:** Designer automatically calculates total active seats across all sections.
- [ ] **Admin User Audit Table:** `/admin/users` displays paginated user registrations with role assignments and contact details.

---

## 3. Exact File Inventory
- `[NEW]` `frontend/src/app/models/venue.model.ts`
- `[NEW]` `frontend/src/app/services/admin-venue-api.service.ts`
- `[NEW]` `frontend/src/app/services/nominatim-geocoding.service.ts`
- `[NEW]` `frontend/src/app/features/admin/venues/admin-venue-list/admin-venue-list.component.ts`
- `[NEW]` `frontend/src/app/features/admin/venues/admin-venue-list/admin-venue-list.component.html`
- `[NEW]` `frontend/src/app/features/admin/venues/admin-venue-list/admin-venue-list.component.scss`
- `[NEW]` `frontend/src/app/features/admin/venues/admin-venue-editor/admin-venue-editor.component.ts`
- `[NEW]` `frontend/src/app/features/admin/venues/admin-venue-editor/admin-venue-editor.component.html`
- `[NEW]` `frontend/src/app/features/admin/venues/admin-venue-editor/admin-venue-editor.component.scss`
- `[NEW]` `frontend/src/app/features/admin/venues/venue-grid-designer/venue-grid-designer.component.ts`
- `[NEW]` `frontend/src/app/features/admin/venues/venue-grid-designer/venue-grid-designer.component.html`
- `[NEW]` `frontend/src/app/features/admin/venues/venue-grid-designer/venue-grid-designer.component.scss`
- `[NEW]` `frontend/src/app/features/admin/users/admin-user-list/admin-user-list.component.ts`
- `[NEW]` `frontend/src/app/features/admin/users/admin-user-list/admin-user-list.component.html`
- `[NEW]` `frontend/src/app/features/admin/users/admin-user-list/admin-user-list.component.scss`
- `[NEW]` `frontend/src/app/services/admin-venue-api.service.spec.ts`
- `[NEW]` `frontend/src/app/features/admin/venues/venue-grid-designer/venue-grid-designer.component.spec.ts`
- `[MODIFY]` `frontend/src/app/app.routes.ts`

---

## 4. Technical Specifications & Contracts

### 4.1 Models & Admin Venue API Service (`src/app/services/admin-venue-api.service.ts`)

```typescript
export interface VenueSectionSeat {
  seatId: string;
  rowLabel: string;
  seatNumber: number;
  gridX: number;
  gridY: number;
  isActive: boolean;
}

export interface VenueSectionLayout {
  sectionId: string;
  name: string;
  rowCount: number;
  colCount: number;
  seats: VenueSectionSeat[];
}

export interface VenueLayout {
  venueId: string;
  name: string;
  address: string;
  latitude?: number;
  longitude?: number;
  capacity: number;
  sections: VenueSectionLayout[];
}

export interface CreateVenueRequest {
  name: string;
  address: string;
  latitude: number;
  longitude: number;
}

export interface CreateSectionRequest {
  name: string;
  rowCount: number;
  colCount: number;
}
```

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { VenueLayout, CreateVenueRequest, CreateSectionRequest } from '../models/venue.model';

@Injectable({ providedIn: 'root' })
export class AdminVenueApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/admin/venues';

  getVenues(): Observable<VenueLayout[]> {
    return this.http.get<VenueLayout[]>('/api/venues');
  }

  getVenueLayout(venueId: string): Observable<VenueLayout> {
    return this.http.get<VenueLayout>(`/api/venues/${venueId}/layout`);
  }

  createVenue(req: CreateVenueRequest): Observable<VenueLayout> {
    return this.http.post<VenueLayout>(this.baseUrl, req);
  }

  createSection(venueId: string, req: CreateSectionRequest): Observable<VenueLayout> {
    return this.http.post<VenueLayout>(`${this.baseUrl}/${venueId}/sections`, req);
  }

  toggleSeat(venueId: string, sectionId: string, seatId: string): Observable<void> {
    return this.http.patch<void>(`${this.baseUrl}/${venueId}/sections/${sectionId}/seats/${seatId}`, {});
  }
}
```

### 4.2 Free Nominatim Geocoding Service (`src/app/services/nominatim-geocoding.service.ts`)

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';

export interface GeocodingResult {
  placeId: number;
  displayName: string;
  lat: number;
  lon: number;
}

@Injectable({ providedIn: 'root' })
export class NominatimGeocodingService {
  private readonly http = inject(HttpClient);
  private readonly nominatimUrl = 'https://nominatim.openstreetmap.org/search';

  searchAddress(query: string): Observable<GeocodingResult[]> {
    return this.http
      .get<Array<{ place_id: number; display_name: string; lat: string; lon: string }>>(
        this.nominatimUrl,
        {
          params: {
            q: query,
            format: 'json',
            addressdetails: '1',
            limit: '5',
          },
        }
      )
      .pipe(
        map((results) =>
          results.map((r) => ({
            placeId: r.place_id,
            displayName: r.display_name,
            lat: parseFloat(r.lat),
            lon: parseFloat(r.lon),
          }))
        )
      );
  }
}
```

### 4.3 2D Grid Generator Utilities (`src/app/features/admin/venues/venue-grid-designer/`)

```typescript
export function getRowLabel(rowIndex: number): string {
  let label = '';
  let num = rowIndex;
  while (num >= 0) {
    label = String.fromCharCode((num % 26) + 65) + label;
    num = Math.floor(num / 26) - 1;
  }
  return label;
}
```

---

## 5. Step-by-Step Implementation Sequence
1. **Define Venue Models & API Services:**
   - Create `src/app/models/venue.model.ts` and `src/app/services/admin-venue-api.service.ts`.
   - Implement `NominatimGeocodingService` for address search.
2. **Build AdminVenueListComponent (`/admin/venues`):**
   - Display grid of venues with capacity badges, address, and action buttons ("Edit Details", "Open Seat Designer").
3. **Build AdminVenueEditorComponent (`/admin/venues/new`, `:id/edit`):**
   - Form with venue name, address search input with Nominatim dropdown suggestions, and interactive Leaflet map with draggable pin syncing coordinates.
4. **Build VenueGridDesignerComponent (`/admin/venues/:id/designer`):**
   - Section creator dialog (Name, Rows, Columns).
   - Interactive 2D Seat Matrix editor rendering seats in rows and columns.
   - Click to toggle seat `isActive` state with optimistic UI update and API dispatch.
   - Bulk action buttons (e.g. "Disable Selected Row", "Disable Column for Aisle").
5. **Build AdminUserListComponent (`/admin/users`):**
   - Paginated `MatTable` displaying registered users, roles, email, phone, and creation dates.
6. **Register Routes & Write Unit Tests:**
   - Add routes in `app.routes.ts` protected by `adminGuard`.
   - Unit tests for `getRowLabel` calculation, seat toggle dispatch, and geocoding response parsing.

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
```
- [ ] Admin venue designer generates accurate alphabetical row labels and numerical seat grids.
- [ ] Address search geocodes coordinates via free OpenStreetMap Nominatim.
- [ ] Seat active/inactive status toggles seamlessly in the 2D grid editor.
- [ ] Admin user audit table lists users with pagination.
- [ ] All unit tests pass cleanly.
- [ ] Task file is moved to `.ai/tasks/completed/phase-09-frontend-portal/011-admin-venue-layout-designer-and-grid-matrix.md`.
