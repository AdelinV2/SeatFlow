import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import {
  CreateEventSessionRequest,
  UpdateEventSessionRequest,
} from '../../../../models/admin-event.model';
import { EventDetail, EventSession } from '../../../../models/event.model';
import { AdminEventApiService } from '../../../../services/admin-event-api.service';
import { SkeletonLoaderComponent } from '../../../../shared/components/skeleton-loader/skeleton-loader.component';
import { DateFormatPipe } from '../../../../shared/pipes/date-format.pipe';

export interface SessionLockStatus {
  locked: boolean;
  reason?: string;
}

@Component({
  selector: 'app-admin-session-manager',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, DateFormatPipe, SkeletonLoaderComponent],
  templateUrl: './admin-session-manager.component.html',
  styleUrl: './admin-session-manager.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminSessionManagerComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly snackBar = inject(MatSnackBar);
  private readonly adminEventApi = inject(AdminEventApiService);

  readonly id = input<string>();

  readonly event = signal<EventDetail | null>(null);
  readonly sessions = signal<EventSession[]>([]);
  readonly isLoading = signal<boolean>(true);
  readonly isSaving = signal<boolean>(false);
  readonly isDeleting = signal<boolean>(false);
  readonly errorMessage = signal<string | null>(null);

  // Modal states
  readonly showEditModal = signal<boolean>(false);
  readonly editingSession = signal<EventSession | null>(null);
  readonly sessionToDelete = signal<EventSession | null>(null);

  // Form signals
  readonly formStartsAt = signal<string>('');
  readonly formEndsAt = signal<string>('');
  readonly formSaleStartsAt = signal<string>('');
  readonly formSaleEndsAt = signal<string>('');
  readonly formTimezone = signal<string>('UTC');
  readonly formErrors = signal<string[]>([]);

  readonly eventId = computed<string | null>(() => {
    return this.id() || this.route.snapshot.paramMap.get('id');
  });

  readonly sortedSessions = computed<EventSession[]>(() => {
    return [...this.sessions()].sort(
      (a, b) => new Date(a.startsAt).getTime() - new Date(b.startsAt).getTime(),
    );
  });

  ngOnInit(): void {
    const eid = this.eventId();
    if (eid) {
      this.loadData(eid);
    } else {
      this.errorMessage.set('Event ID is missing.');
      this.isLoading.set(false);
    }
  }

  loadData(eventId: string): void {
    this.isLoading.set(true);
    this.errorMessage.set(null);

    forkJoin({
      event: this.adminEventApi.getEventById(eventId),
      sessions: this.adminEventApi.getEventSessions(eventId),
    }).subscribe({
      next: ({ event, sessions }) => {
        this.event.set(event);
        this.sessions.set(sessions);
        this.isLoading.set(false);
      },
      error: (err: unknown) => {
        this.isLoading.set(false);
        this.errorMessage.set('Failed to load event or showtime sessions.');
        console.error('Failed to load session data:', err);
      },
    });
  }

  isSessionLocked(session: EventSession): SessionLockStatus {
    const now = new Date();
    if (session.status !== 'SCHEDULED') {
      return { locked: true, reason: `Status is ${session.status}` };
    }
    const endsAt = new Date(session.endsAt);
    if (endsAt <= now) {
      return { locked: true, reason: 'Session has already ended' };
    }
    if (session.saleStartsAt) {
      const saleStartsAt = new Date(session.saleStartsAt);
      if (saleStartsAt <= now) {
        return { locked: true, reason: 'Ticket sales have already opened' };
      }
    }
    const ev = this.event();
    if (ev && ev.status === 'PUBLISHED') {
      if (!session.saleStartsAt) {
        return { locked: true, reason: 'Event is published with open sales' };
      }
      const saleStartsAt = new Date(session.saleStartsAt);
      if (saleStartsAt <= now) {
        return { locked: true, reason: 'Event is published with open sales' };
      }
    }
    return { locked: false };
  }

  openCreateModal(): void {
    const now = new Date();
    const defaultStart = new Date(now.getTime() + 24 * 60 * 60 * 1000);
    const defaultEnd = new Date(defaultStart.getTime() + 2 * 60 * 60 * 1000);

    this.editingSession.set(null);
    this.formStartsAt.set(this.toDatetimeLocal(defaultStart.toISOString()));
    this.formEndsAt.set(this.toDatetimeLocal(defaultEnd.toISOString()));
    this.formSaleStartsAt.set('');
    this.formSaleEndsAt.set('');
    this.formTimezone.set(Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC');
    this.formErrors.set([]);
    this.showEditModal.set(true);
  }

  openEditModal(session: EventSession): void {
    const lock = this.isSessionLocked(session);
    if (lock.locked) {
      this.snackBar.open(`Cannot edit locked session: ${lock.reason}`, 'Close', {
        duration: 4000,
        panelClass: 'snack-warning',
      });
      return;
    }

    this.editingSession.set(session);
    this.formStartsAt.set(this.toDatetimeLocal(session.startsAt));
    this.formEndsAt.set(this.toDatetimeLocal(session.endsAt));
    this.formSaleStartsAt.set(this.toDatetimeLocal(session.saleStartsAt));
    this.formSaleEndsAt.set(this.toDatetimeLocal(session.saleEndsAt));
    this.formTimezone.set(session.timezone || 'UTC');
    this.formErrors.set([]);
    this.showEditModal.set(true);
  }

  closeEditModal(): void {
    this.showEditModal.set(false);
    this.editingSession.set(null);
    this.formErrors.set([]);
  }

  saveSession(): void {
    const errors = this.validateForm();
    if (errors.length > 0) {
      this.formErrors.set(errors);
      return;
    }

    const eid = this.eventId();
    if (!eid) return;

    this.isSaving.set(true);
    const startsAtIso = this.toIsoString(this.formStartsAt());
    const endsAtIso = this.toIsoString(this.formEndsAt());
    const saleStartsAtIso = this.toIsoString(this.formSaleStartsAt());
    const saleEndsAtIso = this.toIsoString(this.formSaleEndsAt());
    const timezone = this.formTimezone().trim() || 'UTC';

    const session = this.editingSession();
    if (session) {
      const updateReq: UpdateEventSessionRequest = {
        startsAt: startsAtIso!,
        endsAt: endsAtIso!,
        saleStartsAt: saleStartsAtIso,
        saleEndsAt: saleEndsAtIso,
        timezone,
      };

      this.adminEventApi.updateEventSession(eid, session.id, updateReq).subscribe({
        next: () => {
          this.isSaving.set(false);
          this.closeEditModal();
          this.snackBar.open('Showtime updated successfully', 'Close', {
            duration: 3000,
            panelClass: 'snack-success',
          });
          this.loadData(eid);
        },
        error: (err: { error?: { message?: string } }) => {
          this.isSaving.set(false);
          const msg = err.error?.message || 'Failed to update showtime session';
          this.formErrors.set([msg]);
        },
      });
    } else {
      const createReq: CreateEventSessionRequest = {
        startsAt: startsAtIso!,
        endsAt: endsAtIso!,
        saleStartsAt: saleStartsAtIso,
        saleEndsAt: saleEndsAtIso,
        timezone,
      };

      this.adminEventApi.createEventSession(eid, createReq).subscribe({
        next: () => {
          this.isSaving.set(false);
          this.closeEditModal();
          this.snackBar.open('Showtime created successfully', 'Close', {
            duration: 3000,
            panelClass: 'snack-success',
          });
          this.loadData(eid);
        },
        error: (err: { error?: { message?: string } }) => {
          this.isSaving.set(false);
          const msg = err.error?.message || 'Failed to create showtime session';
          this.formErrors.set([msg]);
        },
      });
    }
  }

  openDeleteModal(session: EventSession): void {
    const lock = this.isSessionLocked(session);
    if (lock.locked) {
      this.snackBar.open(`Cannot delete locked session: ${lock.reason}`, 'Close', {
        duration: 4000,
        panelClass: 'snack-warning',
      });
      return;
    }
    this.sessionToDelete.set(session);
  }

  closeDeleteModal(): void {
    this.sessionToDelete.set(null);
  }

  confirmDelete(): void {
    const session = this.sessionToDelete();
    const eid = this.eventId();
    if (!session || !eid) return;

    this.isDeleting.set(true);
    this.adminEventApi.deleteEventSession(eid, session.id).subscribe({
      next: () => {
        this.isDeleting.set(false);
        this.closeDeleteModal();
        this.snackBar.open('Showtime deleted successfully', 'Close', {
          duration: 3000,
          panelClass: 'snack-success',
        });
        this.loadData(eid);
      },
      error: (err: { error?: { message?: string } }) => {
        this.isDeleting.set(false);
        const msg = err.error?.message || 'Failed to delete showtime session';
        this.snackBar.open(msg, 'Close', {
          duration: 5000,
          panelClass: 'snack-error',
        });
      },
    });
  }

  private validateForm(): string[] {
    const errors: string[] = [];
    const startsAt = this.formStartsAt();
    const endsAt = this.formEndsAt();
    const saleStartsAt = this.formSaleStartsAt();
    const saleEndsAt = this.formSaleEndsAt();
    const timezone = this.formTimezone().trim();

    if (!startsAt) {
      errors.push('Start time is required.');
    }
    if (!endsAt) {
      errors.push('End time is required.');
    }

    if (startsAt && endsAt) {
      const startMs = new Date(startsAt).getTime();
      const endMs = new Date(endsAt).getTime();
      if (endMs <= startMs) {
        errors.push('End time must be strictly after start time.');
      }
    }

    if (saleStartsAt && saleEndsAt) {
      const saleStartMs = new Date(saleStartsAt).getTime();
      const saleEndMs = new Date(saleEndsAt).getTime();
      if (saleEndMs <= saleStartMs) {
        errors.push('Sale end time must be after sale start time.');
      }
      if (endsAt && saleEndMs > new Date(endsAt).getTime()) {
        errors.push('Sale end time cannot be after session end time.');
      }
    }
    // Mirror of EventSessionServiceImpl.validateSchedule: the sale window must
    // close on or before the session starts (a sale ending mid-showing is
    // rejected server-side), and open on or before the session starts.
    if (startsAt && saleStartsAt) {
      const startMs = new Date(startsAt).getTime();
      const saleStartMs = new Date(saleStartsAt).getTime();
      if (!Number.isNaN(startMs) && !Number.isNaN(saleStartMs) && saleStartMs > startMs) {
        errors.push('Sale start time must be on or before session start time.');
      }
    }
    if (startsAt && saleEndsAt) {
      const startMs = new Date(startsAt).getTime();
      const saleEndMs = new Date(saleEndsAt).getTime();
      if (!Number.isNaN(startMs) && !Number.isNaN(saleEndMs) && saleEndMs > startMs) {
        errors.push('Sale end time must be on or before session start time.');
      }
    }

    // Mirror of the server's ZoneId.of(timezone) check: reject identifiers the
    // platform cannot resolve instead of relying on a backend round trip.
    if (timezone && !this.isValidTimezone(timezone)) {
      errors.push('Timezone must be a valid IANA timezone identifier (e.g. Europe/Bucharest or UTC).');
    }

    return errors;
  }

  private isValidTimezone(timezone: string): boolean {
    try {
      Intl.DateTimeFormat(undefined, { timeZone: timezone });
      return true;
    } catch {
      return false;
    }
  }

  toDatetimeLocal(iso?: string | null): string {
    if (!iso) return '';
    const date = new Date(iso);
    if (isNaN(date.getTime())) return '';
    const pad = (n: number) => n.toString().padStart(2, '0');
    const yyyy = date.getFullYear();
    const mm = pad(date.getMonth() + 1);
    const dd = pad(date.getDate());
    const hh = pad(date.getHours());
    const mi = pad(date.getMinutes());
    return `${yyyy}-${mm}-${dd}T${hh}:${mi}`;
  }

  private toIsoString(localDateTime?: string | null): string | null {
    if (!localDateTime) return null;
    const date = new Date(localDateTime);
    return isNaN(date.getTime()) ? null : date.toISOString();
  }
}
