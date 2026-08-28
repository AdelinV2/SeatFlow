# TASK-P09-011: Admin Venue Layout Designer, 2D Seat Grid Matrix & User Audit

## 1. Task Metadata
- **Task ID:** `TASK-P09-011`
- **Git Branch:** `feat/p09-011-admin-venue-designer`
- **Target Module:** `frontend/src/app/features/admin/venues/`, `frontend/src/app/features/admin/users/`, `frontend/src/app/features/admin/admin-portal/`
- **Phase:** `Phase 09 - Frontend Portal`
- **Related Specs:** `.ai/architecture/06-api-contracts.md` (Section 2.1, 2.2), `.ai/architecture/07-frontend-specification.md` (Section 3, 4.1), `frontend/AGENTS.md`
- **Related ADRs:** `None`
- **Status:** `COMPLETED`

---

## 2. Objective & Invariants
Implement the Venue Designer and Seat Matrix layout builder at `/admin/venues/:id/designer`, the Venue creation flow with free **OpenStreetMap Nominatim** address geocoding, and the Admin User Audit table at `/admin/users`, along with the executive **Admin Portal Dashboard** at `/admin`, all guarded by `admin.guard.ts`. The venue designer allows administrators to define sections, auto-generate 2D seat grids (Row labels A, B, C... and seat numbers 1, 2, 3...), toggle individual seat active/inactive statuses for aisles and walkways, and view real-time venue capacity.

### Critical Invariants to Enforce:
- [x] **100% Free Geocoding (Zero Google Maps API):** Address lookup in the venue creator utilizes the free OpenStreetMap Nominatim Geocoding API (`https://nominatim.openstreetmap.org/search`) with draggable pin placement on a Leaflet map.
- [x] **2D Seat Grid Generator:** Row labels compute accurately using alphabetical sequences (Row 1 -> `A`, Row 26 -> `Z`, Row 27 -> `AA`, etc.), with seat numbers 1 to $N$.
- [x] **Interactive Aisle & Walkway Toggle:** Clicking any seat in the designer matrix toggles its `isActive` status (`PATCH /api/admin/venues/{venueId}/sections/{sectionId}/seats/{seatId}`), with disabled seats visually styled as empty walkway space.
- [x] **Dynamic Venue Capacity Counter:** Designer automatically calculates total active seats across all sections.
- [x] **Admin User Audit Table:** `/admin/users` displays paginated user registrations with role assignments and contact details.
- [x] **Admin Portal Hub:** `/admin` provides high-end sensory dashboard with key metrics and quick navigation.

---

## 3. Exact File Inventory
- `[NEW]` `frontend/src/app/models/venue.model.ts`
- `[NEW]` `frontend/src/app/services/admin-venue-api.service.ts`
- `[NEW]` `frontend/src/app/services/nominatim-geocoding.service.ts`
- `[NEW]` `frontend/src/app/services/admin-user-api.service.ts`
- `[NEW]` `frontend/src/app/features/admin/admin-portal/admin-portal.component.ts`
- `[NEW]` `frontend/src/app/features/admin/admin-portal/admin-portal.component.html`
- `[NEW]` `frontend/src/app/features/admin/admin-portal/admin-portal.component.scss`
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
- `[NEW]` `frontend/src/app/services/nominatim-geocoding.service.spec.ts`
- `[NEW]` `frontend/src/app/services/admin-user-api.service.spec.ts`
- `[NEW]` `frontend/src/app/features/admin/admin-portal/admin-portal.component.spec.ts`
- `[NEW]` `frontend/src/app/features/admin/venues/admin-venue-list/admin-venue-list.component.spec.ts`
- `[NEW]` `frontend/src/app/features/admin/venues/admin-venue-editor/admin-venue-editor.component.spec.ts`
- `[NEW]` `frontend/src/app/features/admin/venues/venue-grid-designer/venue-grid-designer.component.spec.ts`
- `[NEW]` `frontend/src/app/features/admin/users/admin-user-list/admin-user-list.component.spec.ts`
- `[MODIFY]` `frontend/src/app/app.routes.ts`
- `[MODIFY]` `frontend/src/app/shared/layout/header/header.component.html`

---

## 4. Definition of Done & Verification Command
To verify this task, run:
```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
```
- [x] Admin venue designer generates accurate alphabetical row labels and numerical seat grids.
- [x] Address search geocodes coordinates via free OpenStreetMap Nominatim.
- [x] Seat active/inactive status toggles seamlessly in the 2D grid editor.
- [x] Admin user audit table lists users with pagination.
- [x] All unit tests pass cleanly (181/181 passing).
- [x] Task file is moved to `.ai/tasks/completed/phase-09-frontend-portal/011-admin-venue-layout-designer-and-grid-matrix.md`.
