import { Clipboard } from '@angular/cdk/clipboard';
import { isPlatformBrowser } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  OnInit,
  PLATFORM_ID,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, RouterLink } from '@angular/router';
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

export interface DisplayTicketSeat {
  seatId: string;
  ticketCode: string;
  ticketId: string;
  rowNumber: string;
  seatNumber: number | string;
  section: string;
  price: number;
  qrCodeData: string;
}

@Component({
  selector: 'app-guest-ticket',
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
  templateUrl: './guest-ticket.component.html',
  styleUrl: './guest-ticket.component.scss',
})
export class GuestTicketComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly ticketService = inject(TicketApiService);
  private readonly reservationApi = inject(ReservationApiService);
  private readonly eventApi = inject(EventApiService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly clipboard = inject(Clipboard);
  private readonly platformId = inject(PLATFORM_ID);
  private readonly destroyRef = inject(DestroyRef);
  readonly userContext = inject(UserContextService);

  readonly primaryTicket = signal<TicketItem | null>(null);
  readonly reservation = signal<ReservationResponse | null>(null);
  readonly event = signal<EventDetail | null>(null);
  readonly selectedTicketIndex = signal<number>(0);
  readonly isLoading = signal<boolean>(true);
  readonly errorMessage = signal<string | null>(null);
  readonly isDownloadingPdf = signal<boolean>(false);
  readonly isCopied = signal<boolean>(false);
  readonly qrImageUrl = signal<string>('');

  private copyTimeoutId?: ReturnType<typeof setTimeout>;

  readonly ticketList = computed<DisplayTicketSeat[]>(() => {
    const primary = this.primaryTicket();
    if (!primary) return [];

    const res = this.reservation();
    if (!res || !res.seats || res.seats.length <= 1) {
      return [
        {
          seatId: primary.seatId,
          ticketCode: primary.ticketCode,
          ticketId: primary.id,
          rowNumber: primary.rowNumber || '—',
          seatNumber: primary.seatNumber || 1,
          section: primary.section || 'General Admission',
          price: primary.price,
          qrCodeData: primary.qrCodeData,
        },
      ];
    }

    return res.seats.map((seat: ReservationSeatDetail, idx: number) => {
      const isPrimary = seat.seatId === primary.seatId;
      return {
        seatId: seat.seatId,
        ticketCode: isPrimary ? primary.ticketCode : `${primary.ticketCode}-${idx + 1}`,
        ticketId: isPrimary ? primary.id : `${primary.id}-${idx + 1}`,
        rowNumber: seat.rowNumber || primary.rowNumber || '—',
        seatNumber: seat.seatNumber || idx + 1,
        section: primary.section || 'General Admission',
        price: seat.price || primary.price,
        qrCodeData: isPrimary ? primary.qrCodeData : `${primary.qrCodeData}#seat=${seat.seatId}`,
      };
    });
  });

  readonly activeTicket = computed<DisplayTicketSeat | null>(() => {
    const list = this.ticketList();
    const index = this.selectedTicketIndex();
    return list[index] ?? list[0] ?? null;
  });

  readonly totalSeatsCount = computed(() => this.ticketList().length);

  constructor() {
    effect((onCleanup) => {
      const ticket = this.activeTicket();
      const payload = ticket?.qrCodeData?.trim() ?? '';
      let cancelled = false;

      untracked(() => this.qrImageUrl.set(''));

      if (!payload || !isPlatformBrowser(this.platformId)) {
        return;
      }

      if (payload.startsWith('data:image/')) {
        untracked(() => this.qrImageUrl.set(payload));
        return;
      }

      void import('qrcode')
        .then((module) => {
          const qr = (module as { default?: { toDataURL: typeof module.toDataURL } }).default ?? module;
          return qr.toDataURL(payload, {
            errorCorrectionLevel: 'H',
            margin: 2,
            width: 256,
            color: {
              dark: '#0f172a',
              light: '#ffffff',
            },
          });
        })
        .then((url) => {
          if (!cancelled) {
            this.qrImageUrl.set(url);
          }
        })
        .catch(() => {
          // Non-critical fallback
        });

      onCleanup(() => {
        cancelled = true;
      });
    });
  }

  ngOnInit(): void {
    const ticketCode = this.route.snapshot.paramMap.get('ticketCode');
    if (!ticketCode) {
      this.errorMessage.set('No ticket access code was found in the link.');
      this.isLoading.set(false);
      return;
    }

    this.loadTicketData(ticketCode);
  }

  private loadTicketData(ticketCode: string): void {
    this.isLoading.set(true);
    this.errorMessage.set(null);

    this.ticketService
      .getGuestTicket(ticketCode)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (ticket) => {
          this.primaryTicket.set(ticket);
          this.isLoading.set(false);

          if (ticket.reservationId) {
            this.loadReservationDetails(ticket.reservationId, ticket.customerEmail);
          }
          if (ticket.eventId) {
            this.loadEventDetails(ticket.eventId);
          }
        },
        error: () => {
          this.isLoading.set(false);
          this.errorMessage.set(
            'We were unable to find a valid ticket matching this code. Please make sure the URL is complete or check your confirmation email.',
          );
        },
      });
  }

  private loadReservationDetails(reservationId: string, customerEmailProof?: string): void {
    this.reservationApi
      .getReservation(reservationId, customerEmailProof)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (res) => this.reservation.set(res),
        error: () => {
          // Optional enrichment
        },
      });
  }

  private loadEventDetails(eventId: string): void {
    this.eventApi
      .getEventById(eventId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (event) => this.event.set(event),
        error: () => {
          // Optional enrichment
        },
      });
  }

  selectTicket(index: number): void {
    if (index >= 0 && index < this.ticketList().length) {
      this.selectedTicketIndex.set(index);
    }
  }

  openQrModal(): void {
    const active = this.activeTicket();
    const primary = this.primaryTicket();
    const ev = this.event();
    if (!active || !primary) return;

    this.dialog.open(QrModalComponent, {
      width: '420px',
      maxWidth: 'calc(100vw - 2rem)',
      panelClass: 'seatflow-qr-dialog',
      data: {
        qrCodeData: active.qrCodeData,
        ticketCode: active.ticketCode,
        title: ev?.title ? `${ev.title} — Pass #${this.selectedTicketIndex() + 1}` : 'Digital Ticket Pass',
      },
    });
  }

  copyTicketCode(): void {
    const active = this.activeTicket();
    if (!active) return;

    if (this.clipboard.copy(active.ticketCode)) {
      this.isCopied.set(true);
      if (this.copyTimeoutId !== undefined) {
        clearTimeout(this.copyTimeoutId);
      }
      this.copyTimeoutId = setTimeout(() => {
        this.isCopied.set(false);
        this.copyTimeoutId = undefined;
      }, 2500);

      this.snackBar.open('Ticket code copied to clipboard.', 'Close', {
        duration: 3000,
        panelClass: 'snack-success',
      });
    }
  }

  downloadCurrentPdf(): void {
    const primary = this.primaryTicket();
    if (!primary) return;

    this.isDownloadingPdf.set(true);
    this.ticketService
      .downloadTicketPdf(primary.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (blob) => {
          this.isDownloadingPdf.set(false);
          const url = window.URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.href = url;
          a.download = `SeatFlow-Ticket-${primary.ticketCode}.pdf`;
          a.click();
          window.URL.revokeObjectURL(url);
          this.snackBar.open('Ticket PDF downloaded successfully.', 'Close', {
            duration: 3500,
            panelClass: 'snack-success',
          });
        },
        error: () => {
          this.isDownloadingPdf.set(false);
          this.snackBar.open('Could not download PDF. Please try again.', 'Close', {
            duration: 4000,
            panelClass: 'snack-error',
          });
        },
      });
  }
}
