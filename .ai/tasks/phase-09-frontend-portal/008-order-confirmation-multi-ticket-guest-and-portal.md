# TASK-P09-008: Order Confirmation, Multi-Ticket Guest Viewer & Customer Ticket Portal

## 1. Task Metadata
- **Task ID:** `TASK-P09-008`
- **Git Branch:** `feat/p09-008-tickets-and-portal`
- **Target Module:** `frontend/src/app/features/tickets/`, `frontend/src/app/features/profile/`
- **Phase:** `Phase 09 - Frontend Portal`
- **Related Specs:** `.ai/architecture/06-api-contracts.md` (Section 2.1, 2.6), `.ai/architecture/07-frontend-specification.md` (Section 3, 4.3), `frontend/AGENTS.md`
- **Related ADRs:** `ADR-001` (Hybrid Guest Checkout and Guest Ticketing Flow)
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the post-purchase customer and guest experience. This includes the celebratory Order Confirmation page (`/order-confirmation/:paymentId`) with confetti animations, the **Multi-Ticket Guest Viewer** (`/tickets/guest/:ticketCode`) with tabbed switcher and account linking banner (ADR-001), the customer **My Tickets Portal** (`/profile/tickets`) featuring Apple Wallet-style pass cards and PDF downloads, and the **User Settings** page (`/profile/settings`) with phone updating and theme preferences.

### Critical Invariants to Enforce:
- [ ] **Multi-Ticket Guest Switcher (ADR-001):** When a guest reservation contains multiple seats, `/tickets/guest/:ticketCode` must render a tabbed multi-ticket switcher (*"Ticket 1 of N"*, *"Ticket 2 of N"*) allowing instant switching between individual QR codes and seats.
- [ ] **Account Linking Banner:** The guest ticket viewer must display an aesthetic account-linking prompt inviting the guest to sign up/sign in with their purchase email to automatically organize all their historical tickets.
- [ ] **Individual and Bundle PDF Downloads:** Provide 1-click download actions for individual ticket PDFs and full reservation PDF bundles (`GET /api/tickets/{ticketId}/pdf`).
- [ ] **Apple Wallet-Style Digital Passes:** `/profile/tickets` renders upcoming tickets as tactile pass cards displaying seat location, event banner, entrance gate details, and an interactive QR modal.
- [ ] **Confetti Celebration Animation:** Order confirmation page launches a burst of celebratory particles on initial render via `canvas-confetti`.

---

## 3. Exact File Inventory
- `[NEW]` `frontend/src/app/models/ticket.model.ts`
- `[NEW]` `frontend/src/app/services/ticket-api.service.ts`
- `[NEW]` `frontend/src/app/services/user-api.service.ts`
- `[NEW]` `frontend/src/app/features/tickets/order-confirmation/order-confirmation.component.ts`
- `[NEW]` `frontend/src/app/features/tickets/order-confirmation/order-confirmation.component.html`
- `[NEW]` `frontend/src/app/features/tickets/order-confirmation/order-confirmation.component.scss`
- `[NEW]` `frontend/src/app/features/tickets/guest-ticket/guest-ticket.component.ts`
- `[NEW]` `frontend/src/app/features/tickets/guest-ticket/guest-ticket.component.html`
- `[NEW]` `frontend/src/app/features/tickets/guest-ticket/guest-ticket.component.scss`
- `[NEW]` `frontend/src/app/features/profile/my-tickets/my-tickets.component.ts`
- `[NEW]` `frontend/src/app/features/profile/my-tickets/my-tickets.component.html`
- `[NEW]` `frontend/src/app/features/profile/my-tickets/my-tickets.component.scss`
- `[NEW]` `frontend/src/app/features/profile/user-settings/user-settings.component.ts`
- `[NEW]` `frontend/src/app/features/profile/user-settings/user-settings.component.html`
- `[NEW]` `frontend/src/app/features/profile/user-settings/user-settings.component.scss`
- `[NEW]` `frontend/src/app/services/ticket-api.service.spec.ts`
- `[NEW]` `frontend/src/app/features/tickets/guest-ticket/guest-ticket.component.spec.ts`
- `[NEW]` `frontend/src/app/features/profile/my-tickets/my-tickets.component.spec.ts`
- `[MODIFY]` `frontend/src/app/app.routes.ts`

---

## 4. Technical Specifications & Contracts

### 4.1 Models (`src/app/models/ticket.model.ts`)

```typescript
import { PagedResult } from './event.model';

export type TicketStatus = 'VALID' | 'USED' | 'CANCELLED';

export interface TicketItem {
  id: string;
  ticketCode: string;
  reservationId: string;
  paymentId?: string;
  userId?: string;
  eventId: string;
  seatId: string;
  eventTitle?: string;
  eventDate?: string;
  bannerUrl?: string;
  venueName?: string;
  venueAddress?: string;
  section?: string;
  rowNumber?: string;
  seatNumber?: number;
  price: number;
  taxAmount: number;
  netAmount: number;
  attendeeName?: string;
  customerEmail: string;
  status: TicketStatus;
  qrCodeData: string;
  createdAt: string;
}
```

### 4.2 Ticket API Service (`src/app/services/ticket-api.service.ts`)

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { TicketItem, TicketStatus } from '../models/ticket.model';
import { PagedResult } from '../models/event.model';

@Injectable({ providedIn: 'root' })
export class TicketApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/tickets';

  getMyTickets(page = 0, size = 10): Observable<PagedResult<TicketItem>> {
    const params = new HttpParams().set('page', page.toString()).set('size', size.toString());
    return this.http.get<PagedResult<TicketItem>>(`${this.baseUrl}/my-tickets`, { params });
  }

  getGuestTicket(ticketCode: string): Observable<TicketItem> {
    return this.http.get<TicketItem>(`${this.baseUrl}/guest/${ticketCode}`);
  }

  getTicketById(ticketId: string): Observable<TicketItem> {
    return this.http.get<TicketItem>(`${this.baseUrl}/${ticketId}`);
  }

  downloadTicketPdf(ticketId: string): Observable<Blob> {
    return this.http.get(`${this.baseUrl}/${ticketId}/pdf`, { responseType: 'blob' });
  }
}
```

### 4.3 Guest Ticket Component (`src/app/features/tickets/guest-ticket/`)

```typescript
import { Component, ChangeDetectionStrategy, inject, signal, computed, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { TicketApiService } from '../../../services/ticket-api.service';
import { GuestTicketBundleResponse, TicketItem } from '../../../models/ticket.model';
import { TactileButtonComponent } from '../../../shared/components/tactile-button/tactile-button.component';
import { GlassCardComponent } from '../../../shared/components/glass-card/glass-card.component';
import { StatusBadgeComponent } from '../../../shared/components/status-badge/status-badge.component';
import { DateFormatPipe } from '../../../shared/pipes/date-format.pipe';
import { CurrencyFormatPipe } from '../../../shared/pipes/currency-format.pipe';

@Component({
  selector: 'app-guest-ticket',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    TactileButtonComponent,
    GlassCardComponent,
    StatusBadgeComponent,
    DateFormatPipe,
    CurrencyFormatPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './guest-ticket.component.html',
  styleUrl: './guest-ticket.component.scss',
})
export class GuestTicketComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly ticketService = inject(TicketApiService);

  readonly ticket = signal<TicketItem | null>(null);
  readonly isLoading = signal<boolean>(true);

  ngOnInit(): void {
    const code = this.route.snapshot.paramMap.get('ticketCode');
    if (code) {
      this.ticketService.getGuestTicket(code).subscribe({
        next: (data) => {
          this.ticket.set(data);
          this.isLoading.set(false);
        },
        error: () => this.isLoading.set(false),
      });
    }
  }

  downloadCurrentPdf(): void {
    const ticket = this.ticket();
    if (!ticket) return;
    this.ticketService.downloadTicketPdf(ticket.id).subscribe((blob) => {
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `SeatFlow-Ticket-${ticket.ticketCode}.pdf`;
      a.click();
      window.URL.revokeObjectURL(url);
    });
  }
}
```

```html
@if (ticket(); as ticket) {
  <div class="max-w-4xl mx-auto px-4 py-8 space-y-6">
    <!-- Account Linking Banner -->
    <div class="p-4 bg-indigo-500/10 border border-indigo-500/30 rounded-2xl flex flex-col sm:flex-row items-center justify-between gap-4">
      <div class="flex items-center gap-3">
        <span class="text-2xl">🎟️</span>
        <div>
          <h4 class="text-sm font-bold text-indigo-400">Keep all your tickets organized</h4>
          <p class="text-xs text-muted">Create an account with <b>{{ ticket.customerEmail }}</b> to automatically save all your tickets to your digital wallet.</p>
        </div>
      </div>
      <a routerLink="/auth/login" class="px-4 py-2 text-xs font-semibold bg-indigo-600 hover:bg-indigo-500 text-white rounded-xl shadow-md transition-all whitespace-nowrap">
        Create Account / Sign In
      </a>
    </div>

    <!-- Active Ticket Pass Card -->
    <app-glass-card elevation="elevated" class="p-6 md:p-8 space-y-6">
      <div class="flex flex-col md:flex-row justify-between items-start md:items-center gap-4 pb-6 border-b border-[var(--color-border)]">
        <div>
          <span class="text-xs uppercase tracking-wider text-indigo-400 font-bold">{{ ticket.venueName || 'Venue' }}</span>
          <h2 class="text-2xl font-bold mt-1">{{ ticket.eventTitle || 'Live Event' }}</h2>
          <p class="text-sm text-muted mt-0.5">{{ ticket.eventDate ? (ticket.eventDate | sfDate) : (ticket.createdAt | sfDate) }}</p>
        </div>
        <app-status-badge [status]="ticket.status" />
      </div>

      <div class="grid grid-cols-1 md:grid-cols-2 gap-8 items-center">
        <!-- QR Code Display (Client-Rendered / High-Contrast) -->
        <div class="flex flex-col items-center justify-center p-6 bg-white rounded-2xl shadow-inner border border-slate-200 text-slate-900">
          <div class="w-48 h-48 flex flex-col items-center justify-center bg-slate-50 border border-dashed border-slate-300 rounded-xl p-4 text-center">
            <span class="font-mono text-xs font-bold text-indigo-600 break-all select-all">{{ ticket.qrCodeData }}</span>
          </div>
          <span class="font-mono text-sm font-bold tracking-widest mt-3">{{ ticket.ticketCode }}</span>
          <span class="text-[11px] text-slate-500 mt-1">Show this QR code at the venue gate</span>
        </div>

        <!-- Ticket Details & Download -->
        <div class="space-y-4">
          <div class="grid grid-cols-3 gap-3 p-4 bg-[var(--color-canvas)] rounded-xl border border-[var(--color-border)] text-center">
            <div>
              <span class="text-[10px] text-muted uppercase tracking-wider block">Section</span>
              <span class="text-sm font-bold">{{ ticket.section || '—' }}</span>
            </div>
            <div>
              <span class="text-[10px] text-muted uppercase tracking-wider block">Row</span>
              <span class="text-sm font-bold">{{ ticket.rowNumber || '—' }}</span>
            </div>
            <div>
              <span class="text-[10px] text-muted uppercase tracking-wider block">Seat</span>
              <span class="text-sm font-bold">{{ ticket.seatNumber || '—' }}</span>
            </div>
          </div>

          <div class="text-xs space-y-1 text-muted">
            <p><b>Attendee:</b> {{ ticket.attendeeName || ticket.customerEmail }}</p>
            <p><b>Price:</b> {{ ticket.price | sfCurrency }} (Tax: {{ ticket.taxAmount | sfCurrency }})</p>
            @if (ticket.venueAddress) {
              <p><b>Venue Address:</b> {{ ticket.venueAddress }}</p>
            }
          </div>

          <app-tactile-button variant="primary" size="md" (clicked)="downloadCurrentPdf()" class="w-full">
            📄 Download Ticket PDF
          </app-tactile-button>
        </div>
      </div>
    </app-glass-card>
  </div>
}
```

---

## 5. Step-by-Step Implementation Sequence
1. **Define Ticket Models and API Services:**
   - Create `src/app/models/ticket.model.ts`.
   - Implement `TicketApiService` (`getMyTickets`, `getGuestTicket`, `downloadTicketPdf`) and `UserApiService` (`updateProfile`).
2. **Build OrderConfirmationComponent (`/order-confirmation/:paymentId`):**
   - Fire `canvas-confetti` fireworks effect on mount.
   - Display payment summary, itemized seat list, and download buttons.
3. **Build GuestTicketComponent (`/tickets/guest/:ticketCode`):**
   - Implement multi-ticket tabbed switcher ("Ticket 1 of N", "Ticket 2 of N").
   - Display high-density QR code for selected ticket.
   - Render Account-Linking aesthetic prompt banner.
4. **Build MyTicketsComponent (`/profile/tickets`):**
   - Render Apple Wallet-style 3D pass cards for authenticated users with upcoming vs past event tabs.
5. **Build UserSettingsComponent (`/profile/settings`):**
   - Render phone update form and theme switcher selector.
6. **Register Routes & Write Unit Tests:**
   - Add routes in `app.routes.ts`.
   - Write unit tests for `GuestTicketComponent` tab switching and `MyTicketsComponent` filtering.

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
```
- [ ] Multi-ticket guest viewer allows switching between seats and individual QR codes.
- [ ] Account-linking banner renders with direct sign-up/sign-in link.
- [ ] Ticket PDF downloads work for individual and bundle requests.
- [ ] Confetti animation fires on order confirmation.
- [ ] All unit tests pass cleanly.
- [ ] Task file is moved to `.ai/tasks/completed/phase-09-frontend-portal/008-order-confirmation-multi-ticket-guest-and-portal.md`.
