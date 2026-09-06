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
  distinctUntilChanged,
  forkJoin,
  map,
  of,
  Subject,
} from 'rxjs';
import { UserContextService } from '../../../core/auth/user-context.service';
import { EventDetail, EventSession } from '../../../models/event.model';
import { EventSeatMapResponse, Seat } from '../../../models/seat.model';
import { EventApiService } from '../../../services/event-api.service';
import {
  CreateReservationRequest,
  ReservationApiService,
} from '../../../services/reservation-api.service';
import { SeatStateService } from '../../../services/seat-state.service';
import { WebSocketService } from '../../../services/websocket.service';
import { DateFormatPipe } from '../../../shared/pipes/date-format.pipe';
import { resolveSectionColor } from '../../../shared/utils/layout-geometry';
import { SessionSelectorComponent } from '../../events/session-selector/session-selector.component';
import { isSessionCustomerBookable } from '../../events/session-booking-eligibility';
import { SeatMapComponent } from '../seat-map/seat-map.component';
import { SelectionDockComponent } from '../selection-dock/selection-dock.component';

export type BookingFlowState =
  | 'EVENT_LOADING'
  | 'EVENT_READY_NO_SESSION'
  | 'SESSION_SELECTED_LOADING_AVAILABILITY'
  | 'SESSION_READY'
  | 'SEATS_SELECTED';

@Component({
  selector: 'app-seat-selection',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    DateFormatPipe,
    SeatMapComponent,
    SelectionDockComponent,
    SessionSelectorComponent,
  ],
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
  private currentEventId: string | null = null;
  // Monotonic identity for seat-map loads (REV-001): every loadSeatMap/switch
  // captures its token and mutates state only while still current, so a late
  // response for a departed session can never overwrite the active one.
  private seatMapRequestToken = 0;
  // Generation guard for hold creation (REV-003): incremented on every new
  // hold request and on session switches, so a stale A response arriving after
  // a switch to B is cancelled best-effort instead of navigating to checkout.
  private holdRequestGeneration = 0;

  readonly id = input<string>();
  readonly maxSeats = 10;

  readonly event = signal<EventDetail | null>(null);
  readonly sessions = signal<EventSession[]>([]);
  readonly selectedSession = signal<EventSession | null>(null);
  readonly sessionWarning = signal<string | null>(null);
  readonly showSessionPicker = signal<boolean>(false);
  readonly isTransitioningSession = signal<boolean>(false);
  readonly currentReservationId = signal<string | null>(null);

  readonly seatMap = signal<EventSeatMapResponse | null>(null);
  readonly selectedSeatIds = signal<Set<string>>(new Set());
  readonly conflictingSeatIds = signal<Set<string>>(new Set());
  readonly isLoadingEvent = signal(true);
  readonly isLoadingSeatMap = signal(false);
  readonly isCreatingHold = signal(false);
  readonly loadError = signal<string | null>(null);
  readonly guestEmail = signal('');
  readonly guestEmailTouched = signal(false);

  readonly seats = this.seatStateService.seats;
  readonly seatAvailabilityLoading = this.seatStateService.isLoading;
  // REV-002 (FIX-2): explicit non-bookable availability-error state. While
  // true, seats are non-authoritative: selection/hold stay disabled and an
  // error/retry panel replaces the seat map until a successful retry.
  readonly seatAvailabilityError = this.seatStateService.availabilityError;
  readonly isAuthenticated = this.userContext.isAuthenticated;
  readonly selectedSeats = computed(() => {
    const selectedIds = this.selectedSeatIds();
    return this.seats().filter((seat) => selectedIds.has(seat.id));
  });
  readonly guestEmailIsValid = computed(() => this.isValidEmail(this.guestEmail()));
  readonly connectionStatus = this.webSocketService.connectionStatus;

  readonly bookingState = computed<BookingFlowState>(() => {
    if (this.isLoadingEvent()) {
      return 'EVENT_LOADING';
    }
    if (!this.selectedSession()) {
      return 'EVENT_READY_NO_SESSION';
    }
    if (
      this.isLoadingSeatMap() ||
      this.isTransitioningSession() ||
      this.seatAvailabilityLoading() ||
      this.seatAvailabilityError()
    ) {
      return 'SESSION_SELECTED_LOADING_AVAILABILITY';
    }
    if (this.selectedSeatIds().size > 0) {
      return 'SEATS_SELECTED';
    }
    return 'SESSION_READY';
  });

  constructor() {
    this.destroyRef.onDestroy(() => {
      this.webSocketService.disconnect();
      this.reloadRequests.complete();
    });
  }

  ngOnInit(): void {
    this.route.paramMap
      .pipe(
        map((params) => params.get('id') ?? this.id() ?? ''),
        distinctUntilChanged(),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((eventId) => {
        if (!eventId) {
          this.loadError.set('Event ID is missing.');
          this.isLoadingEvent.set(false);
          return;
        }
        this.currentEventId = eventId;
        this.loadEventAndSessions(eventId);
      });
  }

  loadEventAndSessions(eventId: string): void {
    this.isLoadingEvent.set(true);
    this.loadError.set(null);
    this.sessionWarning.set(null);
    this.webSocketService.disconnect();

    forkJoin({
      event: this.eventApi.getEventById(eventId).pipe(catchError(() => of(null))),
      sessions: this.eventApi.getEventSessions(eventId).pipe(catchError(() => of([]))),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ event, sessions }) => {
          this.isLoadingEvent.set(false);
          if (!event) {
            this.loadError.set('The event could not be found.');
            return;
          }

          this.event.set(event);
          const effectiveSessions =
            sessions && sessions.length > 0 ? sessions : event.sessions ?? [];
          this.sessions.set(effectiveSessions);

          const querySessionId = this.route.snapshot.queryParamMap.get('sessionId');
          if (querySessionId) {
            // Deep links must satisfy the same customer-bookability predicate
            // as selector clicks (REV-004): SCHEDULED plus a live sale window.
            const matched = effectiveSessions.find(
              (s) => s.id === querySessionId && isSessionCustomerBookable(s),
            );
            if (matched) {
              this.applySession(matched, eventId);
            } else {
              this.sessionWarning.set(
                'The requested showtime is not available for this event. Please select a showtime below.',
              );
              this.clearSessionSelection();
            }
          } else {
            this.clearSessionSelection();
          }
        },
        error: (err: unknown) => {
          this.isLoadingEvent.set(false);
          this.loadError.set('Failed to load event data. Please try again.');
          console.error('Failed to load event data:', err);
        },
      });
  }

  private applySession(session: EventSession, eventId: string): void {
    this.selectedSession.set(session);
    this.sessionWarning.set(null);
    this.showSessionPicker.set(false);
    this.loadSeatMap(eventId, session.id);
  }

  private clearSessionSelection(): void {
    // Invalidate pending seat-map/hold completions so they cannot repopulate
    // cleared state (REV-001/REV-003).
    this.seatMapRequestToken += 1;
    this.holdRequestGeneration += 1;
    this.selectedSession.set(null);
    this.seatMap.set(null);
    this.selectedSeatIds.set(new Set());
    this.conflictingSeatIds.set(new Set());
    this.seatStateService.clearSeats();
    this.webSocketService.disconnect();
    this.resetPendingAttempt();
  }

  onSelectSession(session: EventSession): void {
    const current = this.selectedSession();
    if (!current) {
      if (this.currentEventId) {
        void this.router.navigate([], {
          relativeTo: this.route,
          queryParams: { sessionId: session.id },
          queryParamsHandling: 'merge',
          replaceUrl: true,
        });
        this.applySession(session, this.currentEventId);
      }
    } else if (current.id !== session.id) {
      this.switchSession(session);
    } else {
      this.showSessionPicker.set(false);
    }
  }

  /**
   * Atomic session switch sequence (Section 6.4):
   * 1. Mark booking controls disabled during transition;
   * 2. Unsubscribe A realtime;
   * 3. Best-effort cancel active A hold using its reservation identity;
   * 4. Clear A seat/price/hold/cart state unconditionally;
   * 5. Set validated B session;
   * 6. Load B authoritative availability;
   * 7. Subscribe B realtime;
   * 8. Re-enable controls only after B base state is known.
   */
  switchSession(newSession: EventSession): void {
    if (!this.currentEventId) return;
    // REV-003: session changes are unavailable while a hold creation request
    // is in flight — the reservation ID only exists after the response, so a
    // mid-flight switch could neither cancel the hold nor stop the checkout
    // navigation. (The generation guard below remains as defense in depth.)
    if (this.isCreatingHold()) {
      this.snackBar.open('Please wait until your hold request completes before switching showtimes.', 'Close', {
        duration: 3500,
        panelClass: 'snack-warning',
        politeness: 'polite',
      });
      return;
    }
    const eventId = this.currentEventId;

    // REV-001: invalidate any pending seat-map load before switching so its
    // late response can never overwrite session B state.
    const switchToken = ++this.seatMapRequestToken;
    // REV-003: invalidate any in-flight hold completion; a stale success is
    // cancelled best-effort instead of navigating.
    this.holdRequestGeneration += 1;

    // 1. Mark booking controls disabled during transition
    this.isTransitioningSession.set(true);
    this.showSessionPicker.set(false);

    // 2. Unsubscribe A realtime
    this.webSocketService.disconnect();

    // 3. Best-effort cancel active A hold
    const activeHoldId = this.currentReservationId();
    if (activeHoldId) {
      this.reservationApi
        .cancelReservation(activeHoldId)
        .pipe(catchError(() => of(undefined)))
        .subscribe();
      this.currentReservationId.set(null);
    }

    // 4. Clear A local state unconditionally
    this.selectedSeatIds.set(new Set());
    this.conflictingSeatIds.set(new Set());
    this.resetPendingAttempt();
    this.seatStateService.clearSeats();

    // 5. Set validated B session & update query param
    this.selectedSession.set(newSession);
    this.sessionWarning.set(null);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { sessionId: newSession.id },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });

    // 6 & 7. Load B availability and subscribe B realtime.
    // REV-002: reconciliation is an explicit session-tokened step. Controls
    // stay disabled (via seatAvailabilityLoading/isTransitioningSession) until
    // B's authoritative REST response, or a deliberate safe failure, settles —
    // never on WebSocket connection timing.
    const map = this.seatMap();
    if (map) {
      const seats = this.flattenSeats(map);
      this.seatStateService.setSeats(seats, eventId, newSession.id);
      this.webSocketService.connectForSession(
        newSession.id,
        (conflictSeatId) => this.handleSeatConflict(conflictSeatId),
        () => this.selectedSeatIds(),
      );
      this.seatStateService.reconcileAvailability(
        newSession.id,
        this.selectedSeatIds(),
        (conflictSeatId) => this.handleSeatConflict(conflictSeatId),
        () => {
          // REV-002 (FIX-2): clear the transition only on a successful
          // baseline. On availability error the service enters the explicit
          // non-bookable error state and controls stay disabled.
          if (
            switchToken === this.seatMapRequestToken &&
            this.selectedSession()?.id === newSession.id &&
            !this.seatStateService.availabilityError()
          ) {
            this.isTransitioningSession.set(false);
          }
        },
        () => {
          if (
            switchToken === this.seatMapRequestToken &&
            this.selectedSession()?.id === newSession.id
          ) {
            this.isTransitioningSession.set(true);
          }
        },
      );
    } else {
      this.loadSeatMap(eventId, newSession.id);
    }
  }

  loadSeatMap(eventId: string, sessionId: string): void {
    // REV-001: capture a monotonic token and re-verify event + session
    // identity before mutating any shared state.
    const loadToken = ++this.seatMapRequestToken;
    this.isLoadingSeatMap.set(true);
    this.isTransitioningSession.set(true);
    this.loadError.set(null);

    this.eventApi
      .getEventSeatMap(eventId)
      .pipe(
        catchError((error: unknown) => {
          if (loadToken === this.seatMapRequestToken) {
            this.isLoadingSeatMap.set(false);
            this.isTransitioningSession.set(false);
            this.loadError.set('The seat map could not be loaded. Please try again.');
          }
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((response) => {
        // Stale response for a departed event/session: ignore entirely —
        // no map/seat/flag/subscription mutation (REV-001).
        if (
          loadToken !== this.seatMapRequestToken ||
          this.currentEventId !== eventId ||
          this.selectedSession()?.id !== sessionId
        ) {
          return;
        }

        if (!response) {
          this.isLoadingSeatMap.set(false);
          this.isTransitioningSession.set(false);
          return;
        }

        this.seatMap.set(response);
        this.seatStateService.setSeats(this.flattenSeats(response), eventId, sessionId);
        this.webSocketService.connectForSession(
          sessionId,
          (conflictSeatId) => this.handleSeatConflict(conflictSeatId),
          () => this.selectedSeatIds(),
        );
        // REV-002: explicit session-tokened availability step; the transition
        // completes only when B's REST baseline settles.
        this.isLoadingSeatMap.set(false);
        this.seatStateService.reconcileAvailability(
          sessionId,
          this.selectedSeatIds(),
          (conflictSeatId) => this.handleSeatConflict(conflictSeatId),
          () => {
            // REV-002 (FIX-2): see switchSession — never re-enable booking
            // controls on an availability error.
            if (
              loadToken === this.seatMapRequestToken &&
              this.selectedSession()?.id === sessionId &&
              !this.seatStateService.availabilityError()
            ) {
              this.isTransitioningSession.set(false);
            }
          },
          () => {
            if (
              loadToken === this.seatMapRequestToken &&
              this.selectedSession()?.id === sessionId
            ) {
              this.isTransitioningSession.set(true);
            }
          },
        );
      });
  }

  retryLoad(): void {
    if (this.currentEventId) {
      this.loadEventAndSessions(this.currentEventId);
    }
  }

  /**
   * REV-002 (FIX-2): retry the authoritative availability baseline for the
   * still-current session after an availability error. The service clears the
   * error flag when the retry starts (showing loading), and the guarded
   * onSettled below re-enables controls only on success.
   */
  retryAvailability(): void {
    const session = this.selectedSession();
    if (!session || !this.seatMap()) {
      return;
    }
    const retryToken = this.seatMapRequestToken;
    const sessionId = session.id;
    this.isTransitioningSession.set(true);
    this.seatStateService.reconcileAvailability(
      sessionId,
      this.selectedSeatIds(),
      (conflictSeatId) => this.handleSeatConflict(conflictSeatId),
      () => {
        if (
          retryToken === this.seatMapRequestToken &&
          this.selectedSession()?.id === sessionId &&
          !this.seatStateService.availabilityError()
        ) {
          this.isTransitioningSession.set(false);
        }
      },
      () => {
        if (
          retryToken === this.seatMapRequestToken &&
          this.selectedSession()?.id === sessionId
        ) {
          this.isTransitioningSession.set(true);
        }
      },
    );
  }

  toggleSessionPicker(): void {
    this.showSessionPicker.update((v) => !v);
  }

  toggleSeat(seat: Seat): void {
    // REV-002 (FIX-2): seats stay non-interactive until the authoritative
    // availability baseline for the current session has been reconciled, and
    // while an availability error is active (non-authoritative statuses).
    if (
      this.isTransitioningSession() ||
      this.isLoadingSeatMap() ||
      this.seatAvailabilityLoading() ||
      this.seatAvailabilityError() ||
      !this.selectedSession()
    ) {
      return;
    }

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
    // REV-002 (FIX-2): hold submission stays disabled until the availability
    // baseline is known and while an availability error is active; REV-003:
    // exactly one hold request may be in flight.
    if (
      this.isCreatingHold() ||
      this.isTransitioningSession() ||
      this.isLoadingSeatMap() ||
      this.seatAvailabilityLoading() ||
      this.seatAvailabilityError() ||
      !this.selectedSession()
    ) {
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

    const session = this.selectedSession()!;
    const fingerprint = `${session.id}:${selectedSeats
      .map((seat) => `${seat.id}:${seat.price}`)
      .join('|')}:${customerEmail.toLowerCase()}`;
    if (this.pendingRequestFingerprint !== fingerprint || !this.pendingIdempotencyKey) {
      this.pendingRequestFingerprint = fingerprint;
      this.pendingIdempotencyKey = globalThis.crypto.randomUUID();
    }

    const request: CreateReservationRequest = {
      eventSessionId: session.id,
      // P12-007: session is the sole booking key; no eventId is sent.
      ...(customerEmail ? { customerEmail } : {}),
      seatIds: selectedSeats.map((seat) => seat.id),
      seatPrices: selectedSeats.map((seat) => seat.price),
      idempotencyKey: this.pendingIdempotencyKey,
    };

    this.isCreatingHold.set(true);
    // REV-003: bind the request to its session + generation. A completion that
    // arrives after a session switch is stale: cancel the returned reservation
    // best-effort and never mutate current-session state or navigate.
    const holdGeneration = ++this.holdRequestGeneration;
    const holdSessionId = session.id;
    this.reservationApi
      .createReservation(request)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (reservation) => {
          if (
            holdGeneration !== this.holdRequestGeneration ||
            this.selectedSession()?.id !== holdSessionId
          ) {
            this.isCreatingHold.set(false);
            this.reservationApi
              .cancelReservation(reservation.id)
              .pipe(catchError(() => of(undefined)))
              .subscribe();
            return;
          }
          this.currentReservationId.set(reservation.id);
          this.pendingIdempotencyKey = null;
          this.pendingRequestFingerprint = null;
          void this.router.navigate(['/checkout', reservation.id]);
        },
        error: () => {
          this.isCreatingHold.set(false);
          // Guard the error reconciliation against a mid-flight session switch
          // (REV-003): only reconcile the still-current session.
          const current = this.selectedSession();
          if (current && current.id === holdSessionId) {
            this.seatStateService.reconcileAvailability(
              current.id,
              this.selectedSeatIds(),
              (conflictSeatId) => this.handleSeatConflict(conflictSeatId),
            );
          }
          this.snackBar.open(
            'One or more of your selected seats were already reserved. Availability has been updated.',
            'Close',
            {
              duration: 6000,
              panelClass: 'snack-warning',
              politeness: 'assertive',
            },
          );
        },
      });
  }

  private flattenSeats(response: EventSeatMapResponse): Seat[] {
    return (response.sections ?? [])
      .filter((section) => section.isActive !== false)
      .flatMap((section, sectionIdx) => {
        const tiers = section.pricingTiers ?? [];
        const defaultTier =
          tiers.find((t) => t.categoryName?.toLowerCase() === 'standard') ??
          tiers.find((t) => Number(t.price) > 0) ??
          tiers[0];
        const price = Number(defaultTier?.price ?? 0);
        const hasValidPrice = Number.isFinite(price) && price > 0;
        const secPosX = section.positionX ?? 0;
        const secPosY = section.positionY ?? 0;
        const secWidth = section.width ?? (section.colCount != null ? section.colCount * 44 : 0);
        const secHeight = section.height ?? (section.rowCount != null ? section.rowCount * 44 : 0);
        const secRot = section.rotationDeg ?? 0;
        const secZ = section.zIndex ?? 0;

        const secMeta = (section.shapeMetadata as Record<string, unknown>) ?? null;
        const secColor = resolveSectionColor(section, sectionIdx);

        return (section.seats ?? []).map((seat) => {
          const posX = seat.positionX ?? (seat.gridX != null ? seat.gridX * 44 : 0);
          const posY = seat.positionY ?? (seat.gridY != null ? seat.gridY * 44 : 0);
          return {
            id: seat.seatId,
            sectionId: section.sectionId,
            sectionName: section.name,
            rowLabel: seat.rowLabel,
            seatNumber: seat.seatNumber,
            gridX: seat.gridX,
            gridY: seat.gridY,
            price: hasValidPrice ? price : 0,
            currency: defaultTier?.currency ?? 'USD',
            status: seat.isActive && hasValidPrice ? ('AVAILABLE' as const) : ('DISABLED' as const),
            isActive: Boolean(seat.isActive && hasValidPrice),
            positionX: posX,
            positionY: posY,
            sectionPositionX: secPosX,
            sectionPositionY: secPosY,
            sectionWidth: secWidth,
            sectionHeight: secHeight,
            sectionRotationDeg: secRot,
            sectionZIndex: secZ,
            sectionColor: secColor,
            sectionShapeMetadata: secMeta,
            categoryName: defaultTier?.categoryName || 'Standard',
            pricingTierId: defaultTier?.id,
            pricingTiers: tiers.map((t) => ({
              id: t.id,
              categoryName: t.categoryName,
              price: Number(t.price ?? 0),
              currency: t.currency ?? 'USD',
            })),
          };
        });
      });
  }

  private handleSeatConflict(seatId: string): void {
    const conflictingSeat = this.seats().find((seat) => seat.id === seatId);
    this.selectedSeatIds.update((current) => {
      const updated = new Set(current);
      updated.delete(seatId);
      return updated;
    });
    this.conflictingSeatIds.update((current) => new Set(current).add(seatId));
    this.resetPendingAttempt();

    const label = conflictingSeat
      ? `Seat ${conflictingSeat.rowLabel}-${conflictingSeat.seatNumber}`
      : 'A selected seat';
    this.snackBar.open(`${label} was just reserved by another user.`, 'Close', {
      duration: 6000,
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
