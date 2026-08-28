import { HttpClient } from '@angular/common/http';
import { computed, inject, Injectable, OnDestroy, signal } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { finalize } from 'rxjs';
import { Seat, SeatAvailabilityResponse, SeatStatus } from '../models/seat.model';

@Injectable({ providedIn: 'root' })
export class SeatStateService implements OnDestroy {
  private readonly http = inject(HttpClient);
  private readonly snackBar = inject(MatSnackBar);
  private reconciliationRequestId = 0;
  private seatUpdateSequence = 0;
  private readonly seatUpdateVersions = new Map<string, number>();

  readonly seats = signal<Seat[]>([]);
  readonly isLoading = signal(false);
  readonly currentEventId = signal<string | null>(null);

  readonly availableSeats = computed(() =>
    this.seats().filter((seat) => seat.status === 'AVAILABLE' && seat.isActive),
  );
  readonly heldSeats = computed(() => this.seats().filter((seat) => seat.status === 'HELD'));
  readonly soldSeats = computed(() =>
    this.seats().filter((seat) => seat.status === 'SOLD' || seat.status === 'RESERVED'),
  );

  setSeats(seats: Seat[], eventId: string): void {
    this.reconciliationRequestId += 1;
    this.isLoading.set(false);
    this.seatUpdateSequence = 0;
    this.seatUpdateVersions.clear();
    this.currentEventId.set(eventId);
    this.seats.set(seats);
  }

  updateSeatStatus(seatId: string, status: SeatStatus): void {
    this.seatUpdateVersions.set(seatId, ++this.seatUpdateSequence);
    this.seats.update((currentSeats) =>
      currentSeats.map((seat) => (seat.id === seatId ? { ...seat, status } : seat)),
    );
  }

  reconcileAvailability(
    eventId: string,
    selectedSeatIds?: Set<string>,
    onConflict?: (conflictSeatId: string) => void,
  ): void {
    const requestId = ++this.reconciliationRequestId;
    const updateSequenceAtRequest = this.seatUpdateSequence;
    this.isLoading.set(true);
    this.http
      .get<SeatAvailabilityResponse>(`/api/reservations/events/${eventId}/availability`)
      .pipe(
        finalize(() => {
          if (requestId === this.reconciliationRequestId) {
            this.isLoading.set(false);
          }
        }),
      )
      .subscribe({
        next: (response) => {
          if (requestId !== this.reconciliationRequestId) {
            return;
          }

          const availabilityMap = new Map(
            (response.seatStatuses ?? response.seats ?? []).map((seat) => [
              seat.seatId,
              seat.status,
            ]),
          );

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
                const seatLabel = seat.rowLabel
                  ? `${seat.rowLabel}-${seat.seatNumber}`
                  : `#${seat.seatNumber}`;
                this.snackBar.open(
                  `Seat ${seatLabel} was just reserved by another user.`,
                  'Close',
                  { duration: 5000, panelClass: 'snack-warning' },
                );
              }

              return { ...seat, status: serverStatus };
            }),
          );
        },
        error: (error: unknown) => {
          if (requestId === this.reconciliationRequestId) {
            console.error('Failed to reconcile seat availability:', error);
          }
        },
      });
  }

  ngOnDestroy(): void {
    this.reconciliationRequestId += 1;
    this.isLoading.set(false);
  }
}
