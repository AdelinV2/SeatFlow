import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
} from '@angular/core';
import { EventSession } from '../../../models/event.model';
import { DateFormatPipe } from '../../../shared/pipes/date-format.pipe';
import { isSessionCustomerBookable } from '../session-booking-eligibility';

export type SessionSalesState = 'OPEN' | 'UPCOMING' | 'CLOSED' | 'CANCELLED' | 'COMPLETED';

export interface EnrichedSession {
  session: EventSession;
  state: SessionSalesState;
  stateLabel: string;
  badgeClass: string;
  isSelectable: boolean;
}

@Component({
  selector: 'app-session-selector',
  standalone: true,
  imports: [CommonModule, DateFormatPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './session-selector.component.html',
  styleUrl: './session-selector.component.scss',
})
export class SessionSelectorComponent {
  readonly sessions = input<EventSession[]>([]);
  readonly selectedSessionId = input<string | null>(null);
  readonly disabled = input<boolean>(false);
  readonly emptyMessage = input<string>('No upcoming showtimes available for this event.');

  readonly sessionSelected = output<EventSession>();

  readonly enrichedSessions = computed<EnrichedSession[]>(() => {
    const list = this.sessions() ?? [];
    const now = new Date();

    return [...list]
      .sort((a, b) => new Date(a.startsAt).getTime() - new Date(b.startsAt).getTime())
      .map((session) => {
        let state: SessionSalesState = 'OPEN';
        let stateLabel = 'Available';
        let badgeClass = 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 border-emerald-500/20';
        let isSelectable = true;

        if (session.status === 'CANCELLED') {
          state = 'CANCELLED';
          stateLabel = 'Cancelled';
          badgeClass = 'bg-rose-500/10 text-rose-600 dark:text-rose-400 border-rose-500/20';
          isSelectable = false;
        } else if (session.status === 'COMPLETED' || new Date(session.endsAt) <= now) {
          state = 'COMPLETED';
          stateLabel = 'Ended';
          badgeClass = 'bg-slate-500/10 text-slate-600 dark:text-slate-400 border-slate-500/20';
          isSelectable = false;
        } else if (new Date(session.startsAt) <= now) {
          // Already started but not yet ended (REV-004 FIX-2): the
          // reservation service rejects startsAt <= now, so the showtime
          // stays disabled even though it has not ended.
          state = 'COMPLETED';
          stateLabel = 'Started';
          badgeClass = 'bg-slate-500/10 text-slate-600 dark:text-slate-400 border-slate-500/20';
          isSelectable = false;
        } else if (!isSessionCustomerBookable(session, now)) {
          // Non-bookable sale window (upcoming or closed). Keep the specific
          // label so customers know why the showtime is disabled.
          if (session.saleStartsAt && new Date(session.saleStartsAt) > now) {
            state = 'UPCOMING';
            stateLabel = 'Sales Upcoming';
            badgeClass = 'bg-amber-500/10 text-amber-600 dark:text-amber-400 border-amber-500/20';
          } else {
            state = 'CLOSED';
            stateLabel = 'Sales Closed';
            badgeClass = 'bg-slate-500/10 text-slate-600 dark:text-slate-400 border-slate-500/20';
          }
          isSelectable = false;
        }

        return {
          session,
          state,
          stateLabel,
          badgeClass,
          isSelectable,
        };
      });
  });

  selectSession(item: EnrichedSession): void {
    if (this.disabled() || !item.isSelectable) {
      return;
    }
    this.sessionSelected.emit(item.session);
  }
}
