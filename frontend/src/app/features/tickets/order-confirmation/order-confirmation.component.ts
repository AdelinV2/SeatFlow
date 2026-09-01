import { isPlatformBrowser } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  input,
  OnInit,
  PLATFORM_ID,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import confetti from 'canvas-confetti';
import { UserContextService } from '../../../core/auth/user-context.service';
import { EventDetail } from '../../../models/event.model';
import { PaymentStatusResponse } from '../../../models/payment.model';
import { EventApiService } from '../../../services/event-api.service';
import { PaymentApiService } from '../../../services/payment-api.service';
import {
  ReservationApiService,
  ReservationResponse,
} from '../../../services/reservation-api.service';
import { GlassCardComponent } from '../../../shared/components/glass-card/glass-card.component';
import { SkeletonLoaderComponent } from '../../../shared/components/skeleton-loader/skeleton-loader.component';
import { TactileButtonComponent } from '../../../shared/components/tactile-button/tactile-button.component';
import { CurrencyFormatPipe } from '../../../shared/pipes/currency-format.pipe';
import { DateFormatPipe } from '../../../shared/pipes/date-format.pipe';

@Component({
  selector: 'app-order-confirmation',
  standalone: true,
  imports: [
    RouterLink,
    GlassCardComponent,
    TactileButtonComponent,
    SkeletonLoaderComponent,
    CurrencyFormatPipe,
    DateFormatPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './order-confirmation.component.html',
  styleUrl: './order-confirmation.component.scss',
})
export class OrderConfirmationComponent implements OnInit {
  readonly paymentId = input<string>();
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly paymentApi = inject(PaymentApiService);
  private readonly reservationApi = inject(ReservationApiService);
  private readonly eventApi = inject(EventApiService);
  private readonly platformId = inject(PLATFORM_ID);
  private readonly destroyRef = inject(DestroyRef);
  readonly userContext = inject(UserContextService);

  private readonly confettiTimers: ReturnType<typeof setTimeout>[] = [];

  readonly payment = signal<PaymentStatusResponse | null>(null);
  readonly reservation = signal<ReservationResponse | null>(null);
  readonly event = signal<EventDetail | null>(null);
  readonly isLoading = signal(true);
  readonly errorMessage = signal<string | null>(null);

  readonly orderReference = computed(() => {
    const id = this.payment()?.id;
    return id ? id.slice(0, 8).toUpperCase() : '—';
  });

  readonly reservationReference = computed(() => {
    const id = this.reservation()?.id ?? this.payment()?.reservationId;
    return id ? id.slice(0, 8).toUpperCase() : '—';
  });

  readonly customerEmail = computed(
    () => this.payment()?.customerEmail ?? this.reservation()?.customerEmail ?? '',
  );

  readonly customerName = computed(
    () => this.reservation()?.customerName ?? this.userContext.userName() ?? 'Valued Customer',
  );

  readonly isGuest = computed(() => !this.userContext.isAuthenticated());

  readonly seats = computed(() => this.reservation()?.seats ?? []);

  readonly seatCount = computed(() => this.seats().length);

  readonly totalAmount = computed(
    () => this.payment()?.amount ?? this.reservation()?.totalAmount ?? 0,
  );

  readonly taxAmount = computed(() => this.payment()?.taxAmount ?? 0);

  readonly netAmount = computed(() => {
    const directNet = this.payment()?.netAmount;
    if (directNet !== undefined && directNet > 0) {
      return directNet;
    }
    return Math.max(0, this.totalAmount() - this.taxAmount());
  });

  readonly currency = computed(() => this.payment()?.currency ?? 'USD');

  ngOnInit(): void {
    const paymentId = this.paymentId() || this.route.snapshot.paramMap.get('paymentId');
    if (!paymentId) {
      this.errorMessage.set('No payment identifier was provided in the URL.');
      this.isLoading.set(false);
      return;
    }

    this.loadOrderDetails(paymentId);
    this.launchCelebrationConfetti();
    this.destroyRef.onDestroy(() => {
      this.confettiTimers.forEach((t) => clearTimeout(t));
      if (isPlatformBrowser(this.platformId)) {
        try {
          confetti.reset();
        } catch {
          // ignore
        }
      }
    });
  }

  private launchCelebrationConfetti(): void {
    if (!isPlatformBrowser(this.platformId)) {
      return;
    }

    try {
      // Stage 1: Immediate central burst
      confetti({
        particleCount: 80,
        spread: 70,
        origin: { y: 0.6 },
        colors: ['#4f46e5', '#8b5cf6', '#06b6d4', '#10b981', '#f59e0b'],
      });

      // Stage 2: Left cannon
      this.confettiTimers.push(
        setTimeout(() => {
          confetti({
            particleCount: 45,
            angle: 60,
            spread: 55,
            origin: { x: 0.1, y: 0.7 },
            colors: ['#6366f1', '#a855f7', '#ec4899', '#3b82f6'],
          });
        }, 250),
      );

      // Stage 3: Right cannon
      this.confettiTimers.push(
        setTimeout(() => {
          confetti({
            particleCount: 45,
            angle: 120,
            spread: 55,
            origin: { x: 0.9, y: 0.7 },
            colors: ['#10b981', '#14b8a6', '#6366f1', '#f59e0b'],
          });
        }, 400),
      );
    } catch {
      // Confetti is a non-critical progressive enhancement
    }
  }

  private loadOrderDetails(paymentId: string): void {
    this.isLoading.set(true);
    this.errorMessage.set(null);

    this.paymentApi
      .getPaymentStatus(paymentId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (payment) => {
          this.payment.set(payment);
          this.fetchReservationAndEvent(payment.reservationId, payment.customerEmail);
        },
        error: () => {
          this.isLoading.set(false);
          this.errorMessage.set(
            'We were unable to locate your payment confirmation. If your card was charged, please check your email for the receipt and digital ticket link.',
          );
        },
      });
  }

  private fetchReservationAndEvent(reservationId: string, customerEmailProof?: string): void {
    const reservation$ = this.userContext.isAuthenticated()
      ? this.reservationApi.getReservation(reservationId)
      : this.reservationApi.getReservation(reservationId, customerEmailProof);

    reservation$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (reservation) => {
          this.reservation.set(reservation);
          this.isLoading.set(false);
          if (reservation.eventId) {
            this.fetchEvent(reservation.eventId);
          }
        },
        error: () => {
          this.isLoading.set(false);
        },
      });
  }

  private fetchEvent(eventId: string): void {
    this.eventApi
      .getEventById(eventId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (event) => {
          this.event.set(event);
          if (event.venueId) {
            this.eventApi
              .getVenueById(event.venueId)
              .pipe(takeUntilDestroyed(this.destroyRef))
              .subscribe({
                next: (venue) => {
                  this.event.update((curr) =>
                    curr
                      ? {
                          ...curr,
                          venueName: venue.name,
                          venueAddress: venue.address,
                          venueCity: venue.city,
                          venueCountry: venue.country,
                        }
                      : curr,
                  );
                },
              });
          }
        },
        error: () => {
          // Event details enrichment is optional
        },
      });
  }

  printReceipt(): void {
    if (isPlatformBrowser(this.platformId)) {
      window.print();
    }
  }
}
