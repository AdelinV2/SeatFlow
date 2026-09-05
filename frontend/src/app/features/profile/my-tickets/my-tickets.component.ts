import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { UserContextService } from '../../../core/auth/user-context.service';
import { EventDetail } from '../../../models/event.model';
import { TicketItem } from '../../../models/ticket.model';
import { EventApiService } from '../../../services/event-api.service';
import {
  ReservationApiService,
  ReservationResponse,
  ReservationSeatDetail,
} from '../../../services/reservation-api.service';
import { TicketApiService } from '../../../services/ticket-api.service';
import { GlassCardComponent } from '../../../shared/components/glass-card/glass-card.component';
import { QrModalComponent } from '../../../shared/components/qr-modal/qr-modal.component';
import { SkeletonLoaderComponent } from '../../../shared/components/skeleton-loader/skeleton-loader.component';
import { StatusBadgeComponent } from '../../../shared/components/status-badge/status-badge.component';
import { TactileButtonComponent } from '../../../shared/components/tactile-button/tactile-button.component';
import { CurrencyFormatPipe } from '../../../shared/pipes/currency-format.pipe';
import { DateFormatPipe } from '../../../shared/pipes/date-format.pipe';

export type TicketTabFilter = 'upcoming' | 'past';

@Component({
  selector: 'app-my-tickets',
  standalone: true,
  imports: [
    RouterLink,
    GlassCardComponent,
    StatusBadgeComponent,
    TactileButtonComponent,
    SkeletonLoaderComponent,
    DateFormatPipe,
    CurrencyFormatPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './my-tickets.component.html',
  styleUrl: './my-tickets.component.scss',
})
export class MyTicketsComponent implements OnInit {
  private readonly ticketService = inject(TicketApiService);
  private readonly eventApi = inject(EventApiService);
  private readonly reservationApi = inject(ReservationApiService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);
  readonly userContext = inject(UserContextService);

  readonly allTickets = signal<TicketItem[]>([]);
  readonly activeTab = signal<TicketTabFilter>('upcoming');
  readonly isLoading = signal<boolean>(true);
  readonly downloadingTicketId = signal<string | null>(null);
  readonly eventDetailsMap = signal<Map<string, EventDetail>>(new Map());
  readonly reservationDetailsMap = signal<Map<string, ReservationResponse>>(new Map());

  readonly upcomingTickets = computed(() => {
    const now = Date.now();
    return this.allTickets().filter((ticket) => {
      // 1. Used or Cancelled tickets belong in the past tab
      if (ticket.status === 'USED' || ticket.status === 'CANCELLED') {
        return false;
      }
      // 2. P12-007: showing date derives from the reservation session snapshot
      // first, then the ticket display date, then the event sessions. No
      // event-level instant remains.
      const showingDateStr = this.showingDate(ticket);
      if (showingDateStr) {
        const showingTime = new Date(showingDateStr).getTime();
        if (!Number.isNaN(showingTime)) {
          return showingTime >= now;
        }
      }
      // 3. If event date is not yet loaded or unknown, active VALID tickets default to Upcoming
      return ticket.status === 'VALID' || !ticket.status;
    });
  });

  readonly pastTickets = computed(() => {
    const now = Date.now();
    return this.allTickets().filter((ticket) => {
      if (ticket.status === 'USED' || ticket.status === 'CANCELLED') {
        return true;
      }
      const showingDateStr = this.showingDate(ticket);
      if (showingDateStr) {
        const showingTime = new Date(showingDateStr).getTime();
        if (!Number.isNaN(showingTime)) {
          return showingTime < now;
        }
      }
      return false;
    });
  });

  // P12-007: single source for a ticket's showing instant. Prefers the
  // immutable reservation session snapshot, then ticket display metadata,
  // then the ticket's OWN session from the parent event's sessions (matched
  // by eventSessionId, never sessions[0]). Returns undefined when the
  // ticket's session cannot be identified, so callers fall back to the
  // default upcoming rule instead of inventing a date.
  showingDate(ticket: TicketItem): string | undefined {
    const reservation = this.reservationDetailsMap().get(ticket.reservationId);
    if (reservation?.sessionStartsAt) return reservation.sessionStartsAt;
    if (ticket.eventDate) return ticket.eventDate;
    const sessions = this.eventDetailsMap().get(ticket.eventId)?.sessions;
    if (!ticket.eventSessionId) return undefined;
    return sessions?.find((s) => s.id === ticket.eventSessionId)?.startsAt;
  }

  readonly displayedTickets = computed(() =>
    this.activeTab() === 'upcoming' ? this.upcomingTickets() : this.pastTickets(),
  );

  ngOnInit(): void {
    this.loadMyTickets();
  }

  loadMyTickets(): void {
    this.isLoading.set(true);
    this.ticketService
      .getMyTickets(0, 50)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => {
          const tickets = page.content || [];
          this.allTickets.set(tickets);
          this.enrichEventDetails(tickets);
          this.enrichReservationDetails(tickets);
          this.isLoading.set(false);
        },
        error: () => {
          this.isLoading.set(false);
          this.snackBar.open('Unable to load your tickets. Please refresh.', 'Close', {
            duration: 4500,
            panelClass: 'snack-error',
          });
        },
      });
  }

  private enrichEventDetails(tickets: TicketItem[]): void {
    const uniqueEventIds = Array.from(new Set(tickets.map((t) => t.eventId).filter(Boolean)));
    if (uniqueEventIds.length === 0) return;

    const observables = uniqueEventIds.map((id) =>
      this.eventApi.getEventById(id).pipe(catchError(() => of(null))),
    );

    forkJoin(observables)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((events) => {
        const map = new Map<string, EventDetail>();
        for (const ev of events) {
          if (ev) {
            map.set(ev.id, ev);
            if (ev.venueId && !ev.venueName) {
              this.eventApi
                .getVenueById(ev.venueId)
                .pipe(
                  catchError(() => of(null)),
                  takeUntilDestroyed(this.destroyRef),
                )
                .subscribe((venue) => {
                  if (venue) {
                    this.eventDetailsMap.update((currentMap) => {
                      const newMap = new Map(currentMap);
                      const existing = newMap.get(ev.id);
                      if (existing) {
                        newMap.set(ev.id, {
                          ...existing,
                          venueName: venue.name,
                          venueAddress: venue.address,
                          venueCity: venue.city,
                          venueCountry: venue.country,
                        });
                      }
                      return newMap;
                    });
                  }
                });
            }
          }
        }
        this.eventDetailsMap.set(map);
      });
  }

  private enrichReservationDetails(tickets: TicketItem[]): void {
    const uniqueResIds = Array.from(new Set(tickets.map((t) => t.reservationId).filter(Boolean)));
    if (uniqueResIds.length === 0) return;

    const observables = uniqueResIds.map((id) =>
      this.reservationApi.getReservation(id).pipe(catchError(() => of(null))),
    );

    forkJoin(observables)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((reservations) => {
        const map = new Map<string, ReservationResponse>();
        for (const res of reservations) {
          if (res) {
            map.set(res.id, res);
          }
        }
        this.reservationDetailsMap.set(map);
      });
  }

  getSeatDetail(ticket: TicketItem): ReservationSeatDetail | undefined {
    const res = this.reservationDetailsMap().get(ticket.reservationId);
    return res?.seats?.find((s) => s.seatId === ticket.seatId);
  }

  setTab(tab: TicketTabFilter): void {
    this.activeTab.set(tab);
  }

  openQrModal(ticket: TicketItem): void {
    const ev = this.eventDetailsMap().get(ticket.eventId);
    this.dialog.open(QrModalComponent, {
      width: '420px',
      maxWidth: 'calc(100vw - 2rem)',
      panelClass: 'seatflow-qr-dialog',
      data: {
        qrCodeData: ticket.qrCodeData || ticket.ticketCode,
        ticketCode: ticket.ticketCode,
        title: ticket.eventTitle || ev?.title || 'Digital Ticket Pass',
      },
    });
  }

  downloadPdf(ticket: TicketItem): void {
    this.downloadingTicketId.set(ticket.id);
    this.ticketService
      .downloadTicketPdf(ticket.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (blob) => {
          this.downloadingTicketId.set(null);
          const url = window.URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.href = url;
          a.download = `SeatFlow-Ticket-${ticket.ticketCode}.pdf`;
          a.click();
          setTimeout(() => window.URL.revokeObjectURL(url), 1000);
          this.snackBar.open('Ticket PDF downloaded.', 'Close', {
            duration: 3500,
            panelClass: 'snack-success',
          });
        },
        error: () => {
          this.downloadingTicketId.set(null);
          this.snackBar.open('Ticket PDF could not be downloaded.', 'Close', {
            duration: 4000,
            panelClass: 'snack-error',
          });
        },
      });
  }
}

