import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  input,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  catchError,
  combineLatest,
  distinctUntilChanged,
  map,
  of,
  startWith,
  Subject,
  switchMap,
} from 'rxjs';
import { UserContextService } from '../../../core/auth/user-context.service';
import { EventSeatMapResponse, Seat } from '../../../models/seat.model';
import { EventApiService } from '../../../services/event-api.service';
import {
  CreateReservationRequest,
  ReservationApiService,
} from '../../../services/reservation-api.service';
import { SeatStateService } from '../../../services/seat-state.service';
import { WebSocketService } from '../../../services/websocket.service';
import { SeatMapComponent } from '../seat-map/seat-map.component';
import { SelectionDockComponent } from '../selection-dock/selection-dock.component';

interface SeatMapLoadResult {
  eventId: string;
  response: EventSeatMapResponse | null;
  error: unknown | null;
}

@Component({
  selector: 'app-seat-selection',
  standalone: true,
  imports: [CommonModule, RouterLink, SeatMapComponent, SelectionDockComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './seat-selection.component.html',
  styleUrl: './seat-selection.component.scss',
})
export class SeatSelectionComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly eventApi = inject(EventApiService);
  private readonly reservationApi = inject(ReservationApiService);
  private readonly seatStateService = inject(SeatStateService);
  private readonly webSocketService = inject(WebSocketService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);
  readonly userContext = inject(UserContextService);
  private readonly reloadRequests = new Subject<void>();
  private pendingIdempotencyKey: string | null = null;
  private pendingRequestFingerprint: string | null = null;

  readonly id = input<string>();
  readonly maxSeats = 10;
  readonly seatMap = signal<EventSeatMapResponse | null>(null);
  readonly selectedSeatIds = signal<Set<string>>(new Set());
  readonly isLoading = signal(true);
  readonly isCreatingHold = signal(false);
  readonly loadError = signal<string | null>(null);
  readonly guestEmail = signal('');
  readonly guestEmailTouched = signal(false);

  readonly seats = this.seatStateService.seats;
  readonly isAuthenticated = this.userContext.isAuthenticated;
  readonly selectedSeats = computed(() => {
    const selectedIds = this.selectedSeatIds();
    return this.seats().filter((seat) => selectedIds.has(seat.id));
  });
  readonly guestEmailIsValid = computed(() => this.isValidEmail(this.guestEmail()));
  readonly connectionStatus = this.webSocketService.connectionStatus;

  constructor() {
    this.destroyRef.onDestroy(() => {
      this.webSocketService.disconnect();
      this.reloadRequests.complete();
    });
  }

  ngOnInit(): void {
    const eventIds = this.route.paramMap.pipe(
      map((params) => params.get('id') ?? this.id() ?? ''),
      distinctUntilChanged(),
    );

    combineLatest([eventIds, this.reloadRequests.pipe(startWith(undefined))])
      .pipe(
        switchMap(([eventId]) => {
          this.prepareForLoad(eventId);
          if (!eventId) {
            return of<SeatMapLoadResult>({
              eventId,
              response: null,
              error: new Error('Event ID is missing.'),
            });
          }
          return this.eventApi.getEventSeatMap(eventId).pipe(
            map((response) => ({ eventId, response, error: null })),
            catchError((error: unknown) => of({ eventId, response: null, error })),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((result) => this.handleSeatMapResult(result));
  }

  retryLoad(): void {
    this.reloadRequests.next();
  }

  toggleSeat(seat: Seat): void {
    const selected = this.selectedSeatIds().has(seat.id);
    if (!selected && this.selectedSeatIds().size >= this.maxSeats) {
      this.snackBar.open('Maximum 10 seats allowed per reservation.', 'Close', {
        duration: 3500,
        panelClass: 'snack-warning',
        politeness: 'polite',
      });
      return;
    }
    if (!selected && (seat.status !== 'AVAILABLE' || !seat.isActive)) {
      return;
    }

    this.selectedSeatIds.update((current) => {
      const updated = new Set(current);
      if (updated.has(seat.id)) {
        updated.delete(seat.id);
      } else {
        updated.add(seat.id);
      }
      return updated;
    });
    this.resetPendingAttempt();
  }

  removeSeat(seat: Seat): void {
    this.selectedSeatIds.update((current) => {
      const updated = new Set(current);
      updated.delete(seat.id);
      return updated;
    });
    this.resetPendingAttempt();
  }

  updateGuestEmail(event: Event): void {
    this.guestEmail.set((event.target as HTMLInputElement).value);
    this.resetPendingAttempt();
  }

  createHold(): void {
    if (this.isCreatingHold()) {
      return;
    }

    const selectedSeats = this.selectedSeats();
    if (selectedSeats.length === 0) {
      return;
    }

    const customerEmail = (this.userContext.userEmail() || this.guestEmail()).trim();
    if (!this.isAuthenticated() && !this.isValidEmail(customerEmail)) {
      this.guestEmailTouched.set(true);
      this.snackBar.open('Enter a valid email address to hold these seats.', 'Close', {
        duration: 4000,
        panelClass: 'snack-warning',
        politeness: 'polite',
      });
      return;
    }

    const fingerprint = `${this.seatMap()?.eventId ?? ''}:${selectedSeats
      .map((seat) => `${seat.id}:${seat.price}`)
      .join('|')}:${customerEmail.toLowerCase()}`;
    if (this.pendingRequestFingerprint !== fingerprint || !this.pendingIdempotencyKey) {
      this.pendingRequestFingerprint = fingerprint;
      this.pendingIdempotencyKey = globalThis.crypto.randomUUID();
    }

    const request: CreateReservationRequest = {
      eventId: this.seatMap()!.eventId,
      ...(customerEmail ? { customerEmail } : {}),
      seatIds: selectedSeats.map((seat) => seat.id),
      seatPrices: selectedSeats.map((seat) => seat.price),
      idempotencyKey: this.pendingIdempotencyKey,
    };

    this.isCreatingHold.set(true);
    this.reservationApi
      .createReservation(request)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (reservation) => {
          this.pendingIdempotencyKey = null;
          this.pendingRequestFingerprint = null;
          void this.router.navigate(['/checkout', reservation.id]);
        },
        error: () => {
          this.isCreatingHold.set(false);
          this.snackBar.open(
            'We could not hold those seats. Please review availability and retry.',
            'Close',
            {
              duration: 5000,
              panelClass: 'snack-warning',
              politeness: 'assertive',
            },
          );
        },
      });
  }

  private prepareForLoad(eventId: string): void {
    this.webSocketService.disconnect();
    this.isLoading.set(true);
    this.loadError.set(null);
    this.seatMap.set(null);
    this.selectedSeatIds.set(new Set());
    this.seatStateService.setSeats([], eventId);
    this.resetPendingAttempt();
  }

  private handleSeatMapResult(result: SeatMapLoadResult): void {
    this.isLoading.set(false);
    if (!result.response || result.error) {
      this.loadError.set('The seat map could not be loaded. Please try again.');
      return;
    }

    this.seatMap.set(result.response);
    this.seatStateService.setSeats(this.flattenSeats(result.response), result.eventId);
    this.webSocketService.connectForEvent(
      result.eventId,
      (seatId) => this.handleSeatConflict(seatId),
      () => this.selectedSeatIds(),
    );
  }

  private flattenSeats(response: EventSeatMapResponse): Seat[] {
    return (response.sections ?? []).flatMap((section) => {
      const tier = section.pricingTiers?.[0];
      const price = Number(tier?.price ?? 0);
      const hasValidPrice = Number.isFinite(price) && price > 0;
      return (section.seats ?? []).map((seat) => ({
        id: seat.seatId,
        sectionId: section.sectionId,
        sectionName: section.name,
        rowLabel: seat.rowLabel,
        seatNumber: seat.seatNumber,
        gridX: seat.gridX,
        gridY: seat.gridY,
        price: hasValidPrice ? price : 0,
        currency: tier?.currency ?? 'USD',
        status: seat.isActive && hasValidPrice ? ('AVAILABLE' as const) : ('DISABLED' as const),
        isActive: seat.isActive && hasValidPrice,
      }));
    });
  }

  private handleSeatConflict(seatId: string): void {
    if (this.isCreatingHold()) {
      return;
    }

    const conflictingSeat = this.seats().find((seat) => seat.id === seatId);
    this.selectedSeatIds.update((current) => {
      const updated = new Set(current);
      updated.delete(seatId);
      return updated;
    });
    this.resetPendingAttempt();

    const label = conflictingSeat
      ? `${conflictingSeat.rowLabel}-${conflictingSeat.seatNumber}`
      : seatId;
    this.snackBar.open(`Seat ${label} was just reserved by another user.`, 'Close', {
      duration: 5000,
      panelClass: 'snack-warning',
      politeness: 'assertive',
    });
  }

  private resetPendingAttempt(): void {
    this.pendingIdempotencyKey = null;
    this.pendingRequestFingerprint = null;
  }

  private isValidEmail(email: string): boolean {
    return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim());
  }
}
