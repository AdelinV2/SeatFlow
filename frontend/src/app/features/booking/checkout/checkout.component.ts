import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  InjectionToken,
  OnDestroy,
  OnInit,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  Appearance,
  loadStripe,
  Stripe,
  StripeAddressElement,
  StripeAddressElementChangeEvent,
  StripeElements,
  StripePaymentElement,
} from '@stripe/stripe-js';
import { EMPTY, Subject } from 'rxjs';
import { catchError, debounceTime, switchMap } from 'rxjs/operators';
import { ThemeService } from '../../../core/theme/theme.service';
import { UserContextService } from '../../../core/auth/user-context.service';
import { PaymentIntentResponse, TaxPreviewRequest } from '../../../models/payment.model';
import { EventApiService } from '../../../services/event-api.service';
import { EventSeatMapResponse } from '../../../models/seat.model';
import { PaymentApiService } from '../../../services/payment-api.service';
import {
  ReservationApiService,
  ReservationResponse,
  UpdateReservationPricingRequest,
} from '../../../services/reservation-api.service';
import { HoldCountdownComponent } from '../../../shared/components/hold-countdown/hold-countdown.component';
import { TactileButtonComponent } from '../../../shared/components/tactile-button/tactile-button.component';
import { CurrencyFormatPipe } from '../../../shared/pipes/currency-format.pipe';
import { HoldExpiredDialogComponent } from './hold-expired-dialog/hold-expired-dialog.component';

const defaultStripePublishableKey = 'pk_test_replace_with_your_publishable_key';
const seatFlowTestPaymentMethod = 'pm_card_visa';

@Component({
  selector: 'app-cancel-order-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="w-full rounded-[1.5rem] bg-[var(--color-surface)] p-6 sm:p-7">
      <p class="text-[10px] font-black uppercase tracking-[0.2em] text-rose-500">Cancel checkout</p>
      <h2
        id="cancel-order-dialog-title"
        class="mt-2 text-xl font-black tracking-tight text-[var(--color-text-primary)]"
      >
        Cancel this order?
      </h2>
      <p class="mt-3 text-sm leading-6 text-[var(--color-text-secondary)]">
        {{ message }}
      </p>
      <div class="mt-7 flex flex-col-reverse gap-3 sm:flex-row sm:justify-end">
        <button
          type="button"
          class="btn-spring rounded-xl border border-[var(--color-border)] px-4 py-2.5 text-xs font-bold text-[var(--color-text-secondary)] transition-colors hover:bg-[var(--color-canvas-subtle)]"
          (click)="close(false)"
        >
          Keep order
        </button>
        <button
          type="button"
          class="btn-spring rounded-xl bg-rose-500 px-4 py-2.5 text-xs font-black text-white transition-colors hover:bg-rose-600"
          (click)="close(true)"
        >
          Cancel order
        </button>
      </div>
    </div>
  `,
})
class CancelOrderDialogComponent {
  private readonly dialogRef = inject(MatDialogRef<CancelOrderDialogComponent, boolean>);
  readonly message = inject<{ message: string }>(MAT_DIALOG_DATA).message;

  close(confirmed: boolean): void {
    this.dialogRef.close(confirmed);
  }
}

export interface CheckoutSeat {
  seatId: string;
  sectionName: string;
  rowLabel: string;
  seatNumber: number;
  pricingTiers: CheckoutPricingTier[];
  selectedPricingTierId: string;
}

export interface CheckoutPricingTier {
  id: string;
  sectionId: string;
  categoryName: string;
  price: number;
  currency: string;
}

function isStripePlaceholder(key: string): boolean {
  const normalized = key.trim().toLowerCase();
  return !normalized.startsWith('pk_test_')
    || normalized.includes('replace')
    || normalized.includes('your_')
    || normalized.includes('dummy')
    || normalized.includes('mock');
}

function readStripePublishableKey(): string {
  const globalObject = globalThis as Record<string, unknown>;
  const envWrapper = (globalObject['__env'] ?? globalObject['env']) as
    Record<string, unknown> | undefined;
  const configuredKey =
    globalObject['STRIPE_PUBLISHABLE_KEY']
    ?? globalObject['NG_APP_STRIPE_PUBLISHABLE_KEY']
    ?? envWrapper?.['STRIPE_PUBLISHABLE_KEY']
    ?? envWrapper?.['NG_APP_STRIPE_PUBLISHABLE_KEY'];
  return typeof configuredKey === 'string' && configuredKey.trim()
    ? configuredKey.trim()
    : defaultStripePublishableKey;
}

export const STRIPE_PUBLISHABLE_KEY = new InjectionToken<string>('STRIPE_PUBLISHABLE_KEY', {
  providedIn: 'root',
  factory: readStripePublishableKey,
});

export const STRIPE_LOADER = new InjectionToken<typeof loadStripe>('STRIPE_LOADER', {
  providedIn: 'root',
  factory: () => loadStripe,
});

@Component({
  selector: 'app-checkout',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    RouterLink,
    HoldCountdownComponent,
    TactileButtonComponent,
    CurrencyFormatPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './checkout.component.html',
  styleUrl: './checkout.component.scss',
})
export class CheckoutComponent implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly formBuilder = inject(FormBuilder);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly reservationApi = inject(ReservationApiService);
  private readonly eventApi = inject(EventApiService);
  private readonly paymentApi = inject(PaymentApiService);
  private readonly themeService = inject(ThemeService);
  private readonly stripeLoader = inject(STRIPE_LOADER);
  private readonly stripePublishableKey = inject(STRIPE_PUBLISHABLE_KEY);
  private readonly destroyRef = inject(DestroyRef);
  readonly userContext = inject(UserContextService);

  readonly paymentElementContainer =
    viewChild<ElementRef<HTMLDivElement>>('paymentElementContainer');
  readonly addressElementContainer =
    viewChild<ElementRef<HTMLDivElement>>('addressElementContainer');

  readonly reservation = signal<ReservationResponse | null>(null);
  readonly paymentIntent = signal<PaymentIntentResponse | null>(null);
  readonly isLoading = signal(true);
  readonly isStripeLoading = signal(false);
  readonly isStripeReady = signal(false);
  readonly isProcessingPayment = signal(false);
  readonly isHoldExpired = signal(false);
  readonly stripeError = signal<string | null>(null);
  readonly paymentElementComplete = signal(false);
  readonly addressElementComplete = signal(false);
  readonly testCardSelected = signal(false);
  readonly isCancellingOrder = signal(false);
  readonly checkoutSeats = signal<CheckoutSeat[]>([]);
  readonly isSeatDetailsLoading = signal(true);
  readonly isTicketTypesConfirmed = signal(false);
  readonly isSavingTicketTypes = signal(false);
  readonly taxAmount = signal(0);
  readonly taxRate = signal<number | null>(null);
  readonly isTaxLoading = signal(false);

  readonly guestForm = this.formBuilder.nonNullable.group({
    customerName: ['', [Validators.required, Validators.minLength(2)]],
    customerEmail: ['', [Validators.required, Validators.email]],
  });

  readonly currencyCode = computed(() =>
    this.paymentIntent()?.currency
      ?? this.checkoutSeats()[0]?.pricingTiers.find((tier) => tier.id === this.checkoutSeats()[0]?.selectedPricingTierId)?.currency
      ?? 'USD',
  );
  readonly grossTotal = computed(() => {
    const selectedSeats = this.checkoutSeats();
    if (selectedSeats.length > 0) {
      return selectedSeats.reduce((total, seat) => {
        const tier = seat.pricingTiers.find((candidate) => candidate.id === seat.selectedPricingTierId);
        return total + Number(tier?.price ?? 0);
      }, 0);
    }
    const seats = this.reservation()?.seats ?? [];
    const lineItemTotal = seats.reduce((total, seat) => total + Number(seat.price ?? 0), 0);
    return lineItemTotal > 0
      ? lineItemTotal
      : Number(this.paymentIntent()?.amount ?? this.reservation()?.totalAmount ?? 0);
  });
  readonly reservationReference = computed(() => {
    const id = this.reservation()?.id;
    return id ? id.slice(0, 8).toUpperCase() : '—';
  });

  private stripe: Stripe | null = null;
  private elements: StripeElements | null = null;
  private paymentElement: StripePaymentElement | null = null;
  private addressElement: StripeAddressElement | null = null;
  private clientSecret: string | null = null;
  private currentPaymentId: string | null = null;
  private customerEmailProof: string | undefined;
  private readonly taxPreviewSubject$ = new Subject<TaxPreviewRequest | null>();
  private paymentAttemptCounter = 0;

  constructor() {
    effect(() => {
      const isDark = this.themeService.isDark();
      this.elements?.update({ appearance: this.stripeAppearance(isDark) });
    });

    this.taxPreviewSubject$
      .pipe(
        debounceTime(400),
        switchMap((address) => {
          if (!address) {
            this.taxAmount.set(0);
            this.taxRate.set(null);
            this.isTaxLoading.set(false);
            return EMPTY;
          }
          if (
            !this.currentPaymentId
            || !address.line1
            || !address.city
            || !address.postalCode
            || !address.country
          ) {
            this.isTaxLoading.set(false);
            return EMPTY;
          }

          this.isTaxLoading.set(true);
          const customerEmailProof = this.getCustomerEmailProof();
          const preview$ = customerEmailProof
            ? this.paymentApi.previewTax(this.currentPaymentId, address, customerEmailProof)
            : this.paymentApi.previewTax(this.currentPaymentId, address);
          return preview$.pipe(
            catchError(() => {
              this.taxAmount.set(0);
              this.taxRate.set(null);
              this.isTaxLoading.set(false);
              return EMPTY;
            }),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((preview) => {
        this.taxAmount.set(Number(preview.taxAmount) || 0);
        this.taxRate.set(
          Number.isFinite(Number(preview.effectiveRate)) ? Number(preview.effectiveRate) : null,
        );
        this.isTaxLoading.set(false);
      });
  }

  ngOnInit(): void {
    const reservationId = this.route.snapshot.paramMap.get('reservationId');
    if (!reservationId) {
      void this.router.navigate(['/events']);
      return;
    }

    if (this.userContext.isAuthenticated()) {
      this.guestForm.setValue({
        customerName: this.userContext.userName(),
        customerEmail: this.userContext.userEmail(),
      });
    }

    this.loadReservation(reservationId);
  }

  ngOnDestroy(): void {
    this.destroyStripeElements();
  }

  loadReservation(reservationId: string): void {
    this.isLoading.set(true);
    const isAuthenticated = this.userContext.isAuthenticated();
    this.customerEmailProof = isAuthenticated
      ? undefined
      : this.reservationApi.getStoredCustomerEmailProof(reservationId)?.trim() || undefined;
    const reservation$ = isAuthenticated
      ? this.reservationApi.getReservation(reservationId)
      : this.reservationApi.getReservation(reservationId, this.customerEmailProof);

    reservation$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (reservation) => {
          this.reservation.set(reservation);
          this.isLoading.set(false);

          if (!this.userContext.isAuthenticated()) {
            this.guestForm.patchValue({
              customerEmail: reservation.customerEmail ?? '',
              customerName: reservation.customerName ?? '',
            });
          }

          if (
            reservation.status !== 'PENDING' ||
            new Date(reservation.expiresAt).getTime() <= Date.now()
          ) {
            this.handleHoldExpired();
            return;
          }

          this.loadCheckoutSeats(reservation);
        },
        error: () => {
          this.isLoading.set(false);
          this.snackBar.open(
            'Unable to load this reservation. Please choose your seats again.',
            'Close',
            {
              duration: 5000,
              panelClass: 'snack-error',
            },
          );
          void this.router.navigate(['/events']);
        },
      });
  }

  private loadCheckoutSeats(reservation: ReservationResponse): void {
    this.isSeatDetailsLoading.set(true);
    this.eventApi
      .getEventSeatMap(reservation.eventId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (seatMap) => {
          this.checkoutSeats.set(this.mapCheckoutSeats(reservation, seatMap));
          this.isSeatDetailsLoading.set(false);
        },
        error: () => {
          this.isSeatDetailsLoading.set(false);
          this.snackBar.open(
            'Unable to load ticket types for these seats. Please try again.',
            'Close',
            { duration: 5000, panelClass: 'snack-error' },
          );
        },
      });
  }

  private mapCheckoutSeats(reservation: ReservationResponse, seatMap: EventSeatMapResponse): CheckoutSeat[] {
    const seatIndex = new Map<string, {
      sectionName: string;
      rowLabel: string;
      seatNumber: number;
      pricingTiers: CheckoutPricingTier[];
    }>();
    for (const section of seatMap.sections ?? []) {
      const pricingTiers = (section.pricingTiers ?? [])
        .filter((tier): tier is typeof tier & { id: string } =>
          typeof tier.id === 'string' && tier.id.length > 0 && Number(tier.price) > 0,
        )
        .map((tier) => ({
          id: tier.id,
          sectionId: tier.sectionId,
          categoryName: tier.categoryName?.trim() || 'Standard',
          price: Number(tier.price),
          currency: tier.currency || 'USD',
        }));
      for (const seat of section.seats ?? []) {
        seatIndex.set(seat.seatId, {
          sectionName: section.name?.trim() || 'General admission',
          rowLabel: seat.rowLabel?.trim() || '—',
          seatNumber: seat.seatNumber || 0,
          pricingTiers,
        });
      }
    }

    return reservation.seats.map((heldSeat, index) => {
      const mapped = seatIndex.get(heldSeat.seatId);
      const pricingTiers = mapped?.pricingTiers ?? [];
      const selected = heldSeat.pricingTierId && pricingTiers.some((tier) => tier.id === heldSeat.pricingTierId)
        ? heldSeat.pricingTierId
        : pricingTiers.find((tier) => tier.price === Number(heldSeat.price))?.id
          ?? pricingTiers[0]?.id
          ?? `legacy-${heldSeat.seatId}`;
      return {
        seatId: heldSeat.seatId,
        sectionName: mapped?.sectionName || 'General admission',
        rowLabel: mapped?.rowLabel || heldSeat.rowNumber?.trim() || '—',
        seatNumber: mapped?.seatNumber || heldSeat.seatNumber || index + 1,
        pricingTiers,
        selectedPricingTierId: selected,
      };
    });
  }

  selectTicketType(seatId: string, pricingTierId: string): void {
    if (this.isTicketTypesConfirmed() || this.isHoldExpired()) {
      return;
    }
    this.checkoutSeats.update((seats) => seats.map((seat) =>
      seat.seatId === seatId && seat.pricingTiers.some((tier) => tier.id === pricingTierId)
        ? { ...seat, selectedPricingTierId: pricingTierId }
      : seat,
    ));
  }

  backToTicketDetails(): void {
    if (!this.isTicketTypesConfirmed() || this.isProcessingPayment() || this.isHoldExpired()) {
      return;
    }

    this.destroyStripeElements();
    this.paymentIntent.set(null);
    this.currentPaymentId = null;
    this.clientSecret = null;
    this.isStripeLoading.set(false);
    this.isStripeReady.set(false);
    this.stripeError.set(null);
    this.testCardSelected.set(false);
    this.paymentElementComplete.set(false);
    this.addressElementComplete.set(false);
    this.taxAmount.set(0);
    this.taxRate.set(null);
    this.isTaxLoading.set(false);
    this.isTicketTypesConfirmed.set(false);
    this.taxPreviewSubject$.next(null);
    this.paymentAttemptCounter++;
  }

  cancelOrder(): void {
    if (this.isCancellingOrder() || this.isHoldExpired()) {
      return;
    }
    const reservation = this.reservation();
    if (!reservation || reservation.status !== 'PENDING') {
      return;
    }

    const dialogRef = this.dialog.open(CancelOrderDialogComponent, {
      width: '380px',
      maxWidth: 'calc(100vw - 2rem)',
      ariaLabel: 'Cancel order',
      data: { message: 'Cancel this order and release the held seats?' },
    });

    dialogRef
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((confirmed) => {
        if (!confirmed || this.isHoldExpired() || this.isCancellingOrder()) {
          return;
        }

        this.cancelPendingOrder(reservation);
      });
  }

  private cancelPendingOrder(reservation: ReservationResponse): void {
    this.isCancellingOrder.set(true);
    this.reservationApi
      .cancelReservation(reservation.id, this.getCustomerEmailProof())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.isCancellingOrder.set(false);
          this.destroyStripeElements();
          this.reservation.set({ ...reservation, status: 'CANCELLED' });
          this.snackBar.open('Order cancelled. Your held seats were released.', 'Close', {
            duration: 4000,
            panelClass: 'snack-success',
          });
          void this.router.navigate(['/events']);
        },
        error: () => {
          this.isCancellingOrder.set(false);
          this.snackBar.open('The order could not be cancelled. Please try again.', 'Close', {
            duration: 5000,
            panelClass: 'snack-error',
          });
        },
      });
  }

  handleFormSubmit(): void {
    if (!this.isTicketTypesConfirmed()) {
      this.continueToPayment();
    } else {
      void this.confirmPayment();
    }
  }

  continueToPayment(): void {
    if (
      this.isSeatDetailsLoading() ||
      this.isSavingTicketTypes() ||
      this.isTicketTypesConfirmed() ||
      this.isHoldExpired()
    ) {
      return;
    }
    if (this.guestForm.invalid) {
      this.guestForm.markAllAsTouched();
      this.snackBar.open('Add a valid attendee name and email for ticket delivery.', 'Close', {
        duration: 4000,
        panelClass: 'snack-warning',
      });
      return;
    }
    const reservation = this.reservation();
    const selections = this.checkoutSeats();
    if (
      !reservation
      || selections.length !== reservation.seats.length
      || selections.some((seat) => !seat.pricingTiers.some((tier) => tier.id === seat.selectedPricingTierId))
    ) {
      this.snackBar.open('Choose an available ticket type for every seat.', 'Close', {
        duration: 4000,
        panelClass: 'snack-warning',
      });
      return;
    }

    const request: UpdateReservationPricingRequest = {
      seats: selections.map((seat) => ({ seatId: seat.seatId, pricingTierId: seat.selectedPricingTierId })),
    };
    this.isSavingTicketTypes.set(true);
    this.reservationApi
      .updateReservationPricing(reservation.id, request, this.getCustomerEmailProof())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (updatedReservation) => {
          this.reservation.set(updatedReservation);
          this.isSavingTicketTypes.set(false);
          this.isTicketTypesConfirmed.set(true);
          void this.initializeStripe(updatedReservation.id);
        },
        error: () => {
          this.isSavingTicketTypes.set(false);
          this.snackBar.open('Ticket types could not be updated. Please try again.', 'Close', {
            duration: 5000,
            panelClass: 'snack-error',
          });
        },
      });
  }

  selectedTier(seat: CheckoutSeat): CheckoutPricingTier | undefined {
    return seat.pricingTiers.find((tier) => tier.id === seat.selectedPricingTierId);
  }

  async initializeStripe(reservationId: string): Promise<void> {
    this.isStripeLoading.set(true);
    this.stripeError.set(null);

    if (isStripePlaceholder(this.stripePublishableKey)) {
      this.handleStripeInitializationError(
        'Add the matching Stripe test publishable key (pk_test_...) to frontend/public/env.js, then reload the page.',
      );
      return;
    }

    try {
      this.stripe = await this.stripeLoader(this.stripePublishableKey, {
        // The Stripe test assistant sends optional browser telemetry to r.stripe.com.
        // Keep the SeatFlow test-card shortcut as the only autofill affordance and
        // avoid an extension-blocked request being reported as a checkout failure.
        developerTools: {
          assistant: {
            enabled: false,
          },
        },
      });
      if (!this.stripe) {
        throw new Error('Stripe could not be loaded.');
      }
    } catch {
      this.handleStripeInitializationError();
      return;
    }

    const request = {
      reservationId,
      idempotencyKey: `pay-intent-${reservationId}-v${this.paymentAttemptCounter}`,
    };
    const customerEmailProof = this.getCustomerEmailProof();
    const paymentIntent$ = customerEmailProof
      ? this.paymentApi.createPaymentIntent(request, customerEmailProof)
      : this.paymentApi.createPaymentIntent(request);

    paymentIntent$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (intent) => this.mountStripeElements(intent),
        error: (error: unknown) => this.handleStripeInitializationError(error),
      });
  }

  applyTestCard(): void {
    if (!this.isStripeReady() || !this.clientSecret) {
      return;
    }

    if (!this.userContext.isAuthenticated()) {
      this.guestForm.patchValue({
        customerName: 'SeatFlow Test Guest',
        customerEmail: 'testbuyer@seatflow.dev',
      });
    }

    this.paymentElement?.update({
      readOnly: true,
      defaultValues: {
        billingDetails: {
          name: this.guestForm.controls.customerName.value,
          email: this.guestForm.controls.customerEmail.value,
          address: {
            country: 'RO',
            postal_code: '010101',
            city: 'Bucharest',
            line1: '1 Test Avenue',
          },
        },
      },
    });
    this.addressElement?.update({
      defaultValues: {
        name: this.guestForm.controls.customerName.value,
        address: {
          country: 'RO',
          postal_code: '010101',
          city: 'Bucharest',
          line1: '1 Test Avenue',
        },
      },
    });
    this.testCardSelected.set(true);
    this.paymentElementComplete.set(true);
    this.addressElementComplete.set(true);
    this.taxPreviewSubject$.next({
      line1: '1 Test Avenue',
      city: 'Bucharest',
      postalCode: '010101',
      country: 'RO',
    });
    this.snackBar.open(
      'SeatFlow test card is ready. No real payment details are needed.',
      'Close',
      {
        duration: 3500,
        panelClass: 'snack-success',
      },
    );
  }

  useManualPaymentEntry(): void {
    this.testCardSelected.set(false);
    this.paymentElementComplete.set(false);
    this.paymentElement?.update({ readOnly: false });
  }

  handleHoldExpired(): void {
    if (this.isHoldExpired()) {
      return;
    }

    this.isHoldExpired.set(true);
    this.isProcessingPayment.set(false);
    this.guestForm.disable();
    this.paymentElement?.update({ readOnly: true });

    const dialogRef = this.dialog.open(HoldExpiredDialogComponent, {
      disableClose: true,
      width: '440px',
      maxWidth: 'calc(100vw - 2rem)',
      panelClass: 'seatflow-expired-dialog',
      ariaLabel: 'Seat hold expired',
    });

    dialogRef
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        const eventId = this.reservation()?.eventId;
        void this.router.navigate(eventId ? ['/events', eventId] : ['/events']);
      });
  }

  paymentDisabled(): boolean {
    return (
      this.isProcessingPayment() ||
      this.isHoldExpired() ||
      !this.isStripeReady() ||
      this.guestForm.invalid ||
      (!this.testCardSelected() &&
        (!this.paymentElementComplete() || !this.addressElementComplete()))
    );
  }

  async confirmPayment(): Promise<void> {
    if (this.isHoldExpired()) {
      return;
    }

    if (this.guestForm.invalid) {
      this.guestForm.markAllAsTouched();
      this.snackBar.open('Add a valid attendee name and email for ticket delivery.', 'Close', {
        duration: 4000,
        panelClass: 'snack-warning',
      });
      return;
    }

    if (!this.stripe || !this.elements || !this.currentPaymentId) {
      this.snackBar.open('Choose your ticket types and continue to payment first.', 'Close', {
        duration: 4000,
        panelClass: 'snack-warning',
      });
      return;
    }

    if (
      !this.testCardSelected() &&
      (!this.paymentElementComplete() || !this.addressElementComplete())
    ) {
      this.snackBar.open('Complete the test payment and billing fields first.', 'Close', {
        duration: 4000,
        panelClass: 'snack-warning',
      });
      return;
    }

    this.isProcessingPayment.set(true);
    const customerEmail = this.guestForm.controls.customerEmail.value.trim();
    const returnUrl = `${window.location.origin}/order-confirmation/${this.currentPaymentId}`;

    if (this.testCardSelected()) {
      // Intentional: confirmCardPayment bypasses PaymentElement UI for the SeatFlow test card
      // shortcut (pm_card_visa). This avoids requiring manual form interaction for the hardcoded
      // token. For real user flows, confirmPayment() with elements is always used (see below).
      const result = await this.stripe.confirmCardPayment(this.clientSecret!, {
        payment_method: seatFlowTestPaymentMethod,
        receipt_email: customerEmail,
        return_url: returnUrl,
      });
      this.handlePaymentResult(result.error?.message, result.paymentIntent?.status);
      return;
    }

    const submitResult = await this.elements.submit();
    if (submitResult.error) {
      this.handlePaymentResult(submitResult.error.message);
      return;
    }

    const result = await this.stripe.confirmPayment({
      elements: this.elements,
      confirmParams: {
        return_url: returnUrl,
        receipt_email: customerEmail,
      },
      redirect: 'if_required',
    });
    this.handlePaymentResult(result.error?.message, result.paymentIntent?.status);
  }

  private destroyStripeElements(): void {
    this.paymentElement?.destroy();
    this.addressElement?.destroy();
    this.paymentElement = null;
    this.addressElement = null;
    this.elements = null;
  }

  private getCustomerEmailProof(): string | undefined {
    if (this.userContext.isAuthenticated()) {
      return undefined;
    }

    return this.customerEmailProof
      ?? (this.guestForm.controls.customerEmail.value.trim() || undefined);
  }

  private mountStripeElements(intent: PaymentIntentResponse): void {
    if (!this.stripe || this.isHoldExpired()) {
      return;
    }

    this.paymentIntent.set(intent);
    this.currentPaymentId = intent.paymentId;
    this.clientSecret = intent.clientSecret;
    this.elements = this.stripe.elements({
      clientSecret: intent.clientSecret,
      appearance: this.stripeAppearance(this.themeService.isDark()),
      loader: 'auto',
      locale: 'en',
    });

    const customerName = this.guestForm.controls.customerName.value;
    const customerEmail = this.guestForm.controls.customerEmail.value;

    this.paymentElement = this.elements.create('payment', {
      layout: { type: 'tabs' },
      business: { name: 'SeatFlow Test Checkout' },
      wallets: { applePay: 'never', googlePay: 'never', link: 'never' },
      defaultValues: {
        billingDetails: {
          name: customerName,
          email: customerEmail,
        },
      },
      fields: { billingDetails: 'never' },
      readOnly: false,
    });
    this.addressElement = this.elements.create('address', {
      mode: 'billing',
      fields: { phone: 'never' },
      display: { name: 'full' },
      defaultValues: { name: customerName || null, address: { country: 'RO' } },
    });

    this.paymentElement.on('change', (event) => this.paymentElementComplete.set(event.complete));
    this.paymentElement.on('loaderror', () => this.handleStripeInitializationError());
    this.addressElement.on('change', (event: StripeAddressElementChangeEvent) => {
      this.addressElementComplete.set(event.complete);
      if (event.complete) {
        this.taxPreviewSubject$.next({
          line1: event.value.address.line1,
          line2: event.value.address.line2 ?? undefined,
          city: event.value.address.city,
          state: event.value.address.state || undefined,
          postalCode: event.value.address.postal_code,
          country: event.value.address.country,
        });
      } else {
        this.taxPreviewSubject$.next(null);
      }
    });
    this.addressElement.on('loaderror', () => this.handleStripeInitializationError());

    setTimeout(() => {
      const paymentContainer = this.paymentElementContainer()?.nativeElement;
      const addressContainer = this.addressElementContainer()?.nativeElement;
      if (!paymentContainer || !addressContainer || !this.paymentElement || !this.addressElement) {
        return;
      }

      this.paymentElement.mount(paymentContainer);
      this.addressElement.mount(addressContainer);
      this.isStripeLoading.set(false);
      this.isStripeReady.set(true);
    });
  }

  private handlePaymentResult(errorMessage?: string, status?: string): void {
    if (errorMessage) {
      this.snackBar.open(errorMessage || 'The test payment could not be completed.', 'Close', {
        duration: 5500,
        panelClass: 'snack-error',
      });
      this.isProcessingPayment.set(false);
      return;
    }

    if (status === 'succeeded' || status === 'processing' || status === 'requires_capture') {
      void this.router.navigate(['/order-confirmation', this.currentPaymentId]);
      return;
    }

    this.isProcessingPayment.set(false);
  }

  private handleStripeInitializationError(error?: unknown): void {
    this.isStripeLoading.set(false);
    this.isStripeReady.set(false);
    const configuredMessage = typeof error === 'string' ? error : this.readApiError(error);
    const message = configuredMessage
      ?? 'The Stripe test gateway is temporarily unavailable. Check the payment-service and Stripe test configuration, then try again.';
    this.stripeError.set(message);
    this.snackBar.open(message, 'Close', {
      duration: 5000,
      panelClass: 'snack-error',
    });
  }

  private readApiError(error: unknown): string | null {
    if (!error || typeof error !== 'object') {
      return null;
    }
    const body = (error as { error?: unknown }).error;
    if (body && typeof body === 'object' && typeof (body as { message?: unknown }).message === 'string') {
      return (body as { message: string }).message;
    }
    return null;
  }

  private stripeAppearance(isDark: boolean): Appearance {
    return {
      theme: isDark ? 'night' : 'stripe',
      labels: 'floating',
      variables: {
        colorPrimary: isDark ? '#818CF8' : '#4F46E5',
        colorBackground: isDark ? '#111827' : '#FFFFFF',
        colorText: isDark ? '#F8FAFC' : '#0F172A',
        colorDanger: '#F43F5E',
        colorTextSecondary: isDark ? '#94A3B8' : '#475569',
        fontFamily: 'Inter, system-ui, sans-serif',
        spacingUnit: '4px',
        borderRadius: '12px',
      },
      rules: {
        '.Input': {
          border: `1px solid ${isDark ? 'rgba(255,255,255,0.10)' : '#E2E8F0'}`,
          boxShadow: 'none',
          padding: '13px 14px',
        },
        '.Input:focus': {
          border: '1px solid #6366F1',
          boxShadow: '0 0 0 3px rgba(99,102,241,0.14)',
        },
        '.Tab': {
          border: `1px solid ${isDark ? 'rgba(255,255,255,0.10)' : '#E2E8F0'}`,
          boxShadow: 'none',
        },
        '.Tab--selected': {
          border: '1px solid #6366F1',
          boxShadow: '0 0 0 3px rgba(99,102,241,0.12)',
        },
      },
    };
  }
}
