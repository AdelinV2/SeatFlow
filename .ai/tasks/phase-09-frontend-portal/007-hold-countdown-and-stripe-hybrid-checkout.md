# TASK-P09-007: 15-Minute Hold Countdown, Stripe Elements & Hybrid Guest Checkout

## 1. Task Metadata
- **Task ID:** `TASK-P09-007`
- **Git Branch:** `feat/p09-007-checkout-and-stripe`
- **Target Module:** `frontend/src/app/features/booking/checkout/`, `frontend/src/app/services/`
- **Phase:** `Phase 09 - Frontend Portal`
- **Related Specs:** `.ai/architecture/06-api-contracts.md` (Section 2.4, 2.5), `.ai/architecture/07-frontend-specification.md` (Section 4.7, 4.8), `frontend/AGENTS.md`
- **Related ADRs:** `ADR-001` (Hybrid Guest Checkout), `ADR-004` (Stripe Tax & Tax-Inclusive Pricing)
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the checkout experience at `/checkout/:reservationId`. This incorporates the 15-minute hold countdown timer with progress ring and automatic expiration modal, the **Hybrid Checkout customer form** (auto-filled for authenticated users vs email/name validation for guests per ADR-001), Stripe Elements (`PaymentElement` + `AddressElement`), **tax-inclusive pricing display** (ADR-004), 3DS confirmation handling, and idempotency key propagation.

### Critical Invariants to Enforce:
- [ ] **15-Minute Hold Expiration Modal & Redirect:** When the countdown reaches `00:00`, immediately freeze the payment form, open an unclosable "Hold Expired" modal dialog, and redirect the user back to `/events/:id` to prevent invalid payment authorizations.
- [ ] **Hybrid Checkout Invariant (ADR-001):** Authenticated users have email and name pre-populated from `UserContextService`. Guest users must provide a validated email address and attendee name.
- [ ] **Tax-Inclusive Pricing Invariant (ADR-004):** The displayed checkout total represents the gross amount. A transparent fiscal sub-breakdown is rendered (e.g., `Total: $120.00 (Includes $22.80 VAT/Taxes)`).
- [ ] **Idempotency Key Transmission:** Every payment intent invocation (`POST /api/payments/intent`) must send a deterministic client-generated `idempotencyKey`.
- [ ] **Stripe Theme Matching:** Stripe Elements must dynamically inherit the active theme appearance (`night` theme rules for Midnight Slate, `stripe` flat rules for Warm Alabaster).

---

## 3. Exact File Inventory
- `[NEW]` `frontend/src/app/models/payment.model.ts`
- `[NEW]` `frontend/src/app/services/payment-api.service.ts`
- `[NEW]` `frontend/src/app/features/booking/checkout/checkout.component.ts`
- `[NEW]` `frontend/src/app/features/booking/checkout/checkout.component.html`
- `[NEW]` `frontend/src/app/features/booking/checkout/checkout.component.scss`
- `[NEW]` `frontend/src/app/features/booking/checkout/hold-expired-dialog/hold-expired-dialog.component.ts`
- `[NEW]` `frontend/src/app/features/booking/checkout/hold-expired-dialog/hold-expired-dialog.component.html`
- `[NEW]` `frontend/src/app/services/payment-api.service.spec.ts`
- `[NEW]` `frontend/src/app/features/booking/checkout/checkout.component.spec.ts`
- `[MODIFY]` `frontend/src/app/app.routes.ts`

---

## 4. Technical Specifications & Contracts

### 4.1 Payment Models & Service (`src/app/services/payment-api.service.ts`)

```typescript
export interface CreatePaymentIntentRequest {
  reservationId: string;
  idempotencyKey: string;
}

export interface PaymentIntentResponse {
  paymentId: string;
  clientSecret: string;
  amount: number;
  currency: string;
  status: string;
}

export interface PaymentStatusResponse {
  id: string;
  reservationId: string;
  customerEmail: string;
  amount: number;
  taxAmount: number;
  netAmount: number;
  currency: string;
  status: 'INITIATED' | 'PROCESSING' | 'SUCCESS' | 'FAILED' | 'CANCELLED';
  createdAt: string;
}
```

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { CreatePaymentIntentRequest, PaymentIntentResponse, PaymentStatusResponse } from '../models/payment.model';

@Injectable({ providedIn: 'root' })
export class PaymentApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/payments';

  createPaymentIntent(request: CreatePaymentIntentRequest): Observable<PaymentIntentResponse> {
    return this.http.post<PaymentIntentResponse>(`${this.baseUrl}/intent`, request);
  }

  getPaymentStatus(paymentId: string): Observable<PaymentStatusResponse> {
    return this.http.get<PaymentStatusResponse>(`${this.baseUrl}/${paymentId}`);
  }
}
```

### 4.2 Checkout Component (`src/app/features/booking/checkout/`)

```typescript
import { Component, ChangeDetectionStrategy, inject, signal, computed, OnInit, ElementRef, viewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { loadStripe, Stripe, StripeElements, StripePaymentElement } from '@stripe/stripe-js';

import { ReservationApiService, ReservationResponse } from '../../../services/reservation-api.service';
import { PaymentApiService } from '../../../services/payment-api.service';
import { UserContextService } from '../../../core/auth/user-context.service';
import { ThemeService } from '../../../core/theme/theme.service';
import { HoldCountdownComponent } from '../../../shared/components/hold-countdown/hold-countdown.component';
import { TactileButtonComponent } from '../../../shared/components/tactile-button/tactile-button.component';
import { CurrencyFormatPipe } from '../../../shared/pipes/currency-format.pipe';
import { HoldExpiredDialogComponent } from './hold-expired-dialog/hold-expired-dialog.component';

@Component({
  selector: 'app-checkout',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    HoldCountdownComponent,
    TactileButtonComponent,
    CurrencyFormatPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './checkout.component.html',
  styleUrl: './checkout.component.scss',
})
export class CheckoutComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly reservationService = inject(ReservationApiService);
  private readonly paymentService = inject(PaymentApiService);
  readonly userContext = inject(UserContextService);
  private readonly themeService = inject(ThemeService);

  readonly paymentElementContainer = viewChild<ElementRef<HTMLDivElement>>('paymentElementContainer');

  readonly reservation = signal<ReservationResponse | null>(null);
  readonly isLoading = signal<boolean>(true);
  readonly isProcessingPayment = signal<boolean>(false);
  readonly isHoldExpired = signal<boolean>(false);

  private stripe: Stripe | null = null;
  private elements: StripeElements | null = null;
  private currentPaymentId: string | null = null;

  readonly guestForm = this.fb.group({
    customerEmail: ['', [Validators.required, Validators.email]],
    customerName: ['', [Validators.required, Validators.minLength(2)]],
  });

  ngOnInit(): void {
    const reservationId = this.route.snapshot.paramMap.get('reservationId');
    if (!reservationId) {
      this.router.navigate(['/events']);
      return;
    }

    // Auto-fill form if user is authenticated
    if (this.userContext.isAuthenticated()) {
      this.guestForm.patchValue({
        customerEmail: this.userContext.userEmail(),
        customerName: this.userContext.userName(),
      });
    }

    this.loadReservation(reservationId);
  }

  loadReservation(reservationId: string): void {
    this.isLoading.set(true);
    this.reservationService.getReservation(reservationId).subscribe({
      next: (res) => {
        this.reservation.set(res);
        this.isLoading.set(false);
        this.initStripePayment(res.id);
      },
      error: () => {
        this.snackBar.open('Unable to load reservation details.', 'Close', { duration: 4000 });
        this.router.navigate(['/events']);
      },
    });
  }

  async initStripePayment(reservationId: string): Promise<void> {
    const publishableKey = 'pk_test_TYooMQauvdEDq54NiTphI7jx'; // Standard Test Key
    this.stripe = await loadStripe(publishableKey);

    const idempotencyKey = `pay-intent-${reservationId}-${Date.now()}`;
    this.paymentService.createPaymentIntent({ reservationId, idempotencyKey }).subscribe({
      next: (intent) => {
        this.currentPaymentId = intent.paymentId;
        if (this.stripe) {
          const isDark = this.themeService.isDark();
          this.elements = this.stripe.elements({
            clientSecret: intent.clientSecret,
            appearance: {
              theme: isDark ? 'night' : 'stripe',
              variables: {
                colorPrimary: '#6366F1',
                colorBackground: isDark ? '#111827' : '#FFFFFF',
                colorText: isDark ? '#F8FAFC' : '#0F172A',
                borderRadius: '12px',
              },
            },
          });

          const paymentElement = this.elements.create('payment');
          setTimeout(() => {
            if (this.paymentElementContainer()?.nativeElement) {
              paymentElement.mount(this.paymentElementContainer()!.nativeElement);
            }
          }, 0);
        }
      },
      error: (err) => {
        this.snackBar.open('Failed to initialize payment gateway.', 'Close', { duration: 5000 });
      },
    });
  }

  handleHoldExpired(): void {
    this.isHoldExpired.set(true);
    this.dialog.open(HoldExpiredDialogComponent, {
      disableClose: true,
      width: '420px',
    });
  }

  async confirmPayment(): Promise<void> {
    if (!this.stripe || !this.elements || this.isHoldExpired()) return;

    if (!this.userContext.isAuthenticated() && this.guestForm.invalid) {
      this.guestForm.markAllAsTouched();
      this.snackBar.open('Please provide a valid email and name for ticket delivery.', 'Close', { duration: 3500 });
      return;
    }

    this.isProcessingPayment.set(true);

    const { error } = await this.stripe.confirmPayment({
      elements: this.elements,
      confirmParams: {
        return_url: `${window.location.origin}/order-confirmation/${this.currentPaymentId}`,
        receipt_email: this.guestForm.value.customerEmail || undefined,
      },
    });

    if (error) {
      this.snackBar.open(error.message || 'Payment failed. Please try another card.', 'Close', {
        duration: 5000,
        panelClass: 'snack-error',
      });
      this.isProcessingPayment.set(false);
    }
  }
}
```

---

## 5. Step-by-Step Implementation Sequence
1. **Implement Payment API Service:**
   - Create `src/app/models/payment.model.ts` and `src/app/services/payment-api.service.ts`.
2. **Build HoldExpiredDialogComponent:**
   - Create non-dismissible modal with "Seat Hold Expired" message and "Return to Events" CTA.
3. **Build CheckoutComponent View & Layout:**
   - Left column: Guest/Customer info form + Stripe PaymentElement mounting container.
   - Right column: Order summary card, itemized seats, gross total, tax-inclusive notice (ADR-004), and `<app-hold-countdown>`.
4. **Integrate Stripe Elements:**
   - Dynamic loading of `@stripe/stripe-js`.
   - Call `POST /api/payments/intent` with idempotency key.
   - Mount Stripe `PaymentElement` with theme-aware appearance tokens.
5. **Handle Payment Confirmation & Redirection:**
   - Execute `stripe.confirmPayment()` with `return_url` pointing to `/order-confirmation/:paymentId`.
6. **Develop Unit Tests:**
   - Test countdown timer expiry triggers `handleHoldExpired()`.
   - Test guest form validation requirements.
   - Test idempotency key generation and payment intent dispatch.

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
```
- [ ] Checkout page loads reservation details and displays countdown ring.
- [ ] Hold expiration modal opens upon timer reaching `00:00`.
- [ ] Guest checkout requires valid email and name; authenticated checkout auto-populates.
- [ ] Stripe Elements mounts cleanly and processes confirmation redirect.
- [ ] Tax-inclusive price breakdown is transparently rendered.
- [ ] Unit tests pass with 100% success.
- [ ] Task file is moved to `.ai/tasks/completed/phase-09-frontend-portal/007-hold-countdown-and-stripe-hybrid-checkout.md`.
