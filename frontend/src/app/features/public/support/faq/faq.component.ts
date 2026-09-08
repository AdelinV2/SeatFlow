import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ContentPageComponent } from '../../../../shared/layout/content-page/content-page.component';
import { REFUND_CUTOFF_LABEL } from '../../legal/refund-policy';

/**
 * Public FAQ page (TASK-P16-004). Summaries only: every policy/domain fact
 * links to its authoritative page (Terms, Privacy, Cookies, Refunds, Tax,
 * Security, Contact) instead of duplicating rules that could drift.
 */
@Component({
  selector: 'app-faq',
  standalone: true,
  imports: [ContentPageComponent, RouterLink],
  templateUrl: './faq.component.html',
  styleUrl: './faq.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FaqComponent {
  readonly toc = [
    { id: 'booking', label: 'Booking and holds' },
    { id: 'tickets', label: 'Tickets and access' },
    { id: 'payments', label: 'Payments, tax, and refunds' },
    { id: 'account', label: 'Accounts and staff tools' },
    { id: 'ai-privacy', label: 'AI assistant and privacy' },
    { id: 'demo-help', label: 'Demo help' },
  ] as const;

  readonly refundCutoffLabel = REFUND_CUTOFF_LABEL;

  private readonly expandedIds = signal<ReadonlySet<string>>(new Set(['seats-holds']));

  isExpanded(id: string): boolean {
    return this.expandedIds().has(id);
  }

  toggle(id: string): void {
    this.expandedIds.update((current) => {
      const next = new Set(current);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }
}
