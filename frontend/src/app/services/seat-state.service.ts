import { HttpClient } from '@angular/common/http';
import { computed, inject, Injectable, OnDestroy, signal } from '@angular/core';
import { finalize } from 'rxjs';
import { Seat, SeatAvailabilityResponse, SeatStatus } from '../models/seat.model';

@Injectable({ providedIn: 'root' })
export class SeatStateService implements OnDestroy {
  private readonly http = inject(HttpClient);
  private reconciliationRequestId = 0;
  private seatUpdateSequence = 0;
  private readonly seatUpdateVersions = new Map<string, number>();

  readonly seats = signal<Seat[]>([]);
  readonly isLoading = signal(false);
  // REV-002 (FIX-2): explicit non-bookable availability-error state. When the
  // authoritative REST baseline fails for the current session, seats keep
  // their non-authoritative statuses and must stay non-selectable until a
  // successful retry or a session change. Cleared on every new reconciliation
  // attempt, on setSeats/clearSeats, and on destroy.
  readonly availabilityError = signal(false);
  readonly currentEventId = signal<string | null>(null);
  readonly currentSessionId = signal<string | null>(null);

  readonly hasSession = computed(() => !!this.currentSessionId());
  readonly availableSeats = computed(() =>
    this.seats().filter((seat) => seat.status === 'AVAILABLE' && seat.isActive),
  );
  readonly heldSeats = computed(() => this.seats().filter((seat) => seat.status === 'HELD'));
  readonly soldSeats = computed(() =>
    this.seats().filter((seat) => seat.status === 'SOLD' || seat.status === 'RESERVED'),
  );
  readonly totalAvailable = computed(() => this.availableSeats().length);
  readonly totalHeld = computed(() => this.heldSeats().length);
  readonly totalSold = computed(() => this.soldSeats().length);

  setSeats(seats: Seat[], eventId: string, sessionId?: string | null): void {
    this.reconciliationRequestId += 1;
    this.isLoading.set(false);
    this.availabilityError.set(false);
    this.seatUpdateSequence = 0;
    this.seatUpdateVersions.clear();
    this.currentEventId.set(eventId);
    this.currentSessionId.set(sessionId ?? null);
    this.seats.set(seats);
  }

  clearSeats(): void {
    this.reconciliationRequestId += 1;
    this.isLoading.set(false);
    this.availabilityError.set(false);
    this.seatUpdateSequence = 0;
    this.seatUpdateVersions.clear();
    this.currentSessionId.set(null);
    this.seats.set([]);
  }

  updateSeatStatus(seatId: string, status: SeatStatus): void {
    this.seatUpdateVersions.set(seatId, ++this.seatUpdateSequence);
    this.seats.update((currentSeats) =>
      currentSeats.map((seat) => (seat.id === seatId ? { ...seat, status } : seat)),
    );
  }

  reconcileAvailability(
    eventSessionId: string,
    selectedSeatIds?: Set<string>,
    onConflict?: (conflictSeatId: string) => void,
    onSettled?: () => void,
    onError?: () => void,
  ): void {
    const requestId = ++this.reconciliationRequestId;
    const updateSequenceAtRequest = this.seatUpdateSequence;
    this.isLoading.set(true);
    this.availabilityError.set(false);

    this.http
      .get<SeatAvailabilityResponse>(`/api/event-sessions/${eventSessionId}/seats/availability`)
      .pipe(
        finalize(() => {
          if (requestId === this.reconciliationRequestId) {
            this.isLoading.set(false);
            onSettled?.();
          }
        }),
      )
      .subscribe({
        next: (response) => {
          // Ignore delayed responses if reconciliation was superseded or if the session changed
          if (
            requestId !== this.reconciliationRequestId ||
            (this.currentSessionId() && this.currentSessionId() !== eventSessionId)
          ) {
            return;
          }

          const availabilityMap = new Map(
            (response.seatStatuses ?? response.seats ?? []).map((seat) => [
              seat.seatId,
              seat.status,
            ]),
          );

          // Authoritative baseline received for the still-current session.
          this.availabilityError.set(false);

          this.seats.update((currentSeats) =>
            currentSeats.map((seat) => {
              if ((this.seatUpdateVersions.get(seat.id) ?? 0) > updateSequenceAtRequest) {
                return seat;
              }

              const serverStatus =
                availabilityMap.get(seat.id) ??
                (seat.isActive && seat.status !== 'DISABLED' ? 'AVAILABLE' : undefined);
              if (!serverStatus || serverStatus === seat.status) {
                return seat;
              }

              if (selectedSeatIds?.has(seat.id) && serverStatus !== 'AVAILABLE') {
                onConflict?.(seat.id);
              }

              return { ...seat, status: serverStatus };
            }),
          );
        },
        error: (error: unknown) => {
          if (requestId === this.reconciliationRequestId) {
            console.error('Failed to reconcile seat availability:', error);
            // Enter the explicit non-bookable error state (REV-002 FIX-2):
            // seats are non-authoritative, so selection/hold stay disabled
            // until a successful retry or a session change.
            this.availabilityError.set(true);
            onError?.();
          }
        },
      });
  }

  ngOnDestroy(): void {
    this.reconciliationRequestId += 1;
    this.isLoading.set(false);
    this.availabilityError.set(false);
  }
}
