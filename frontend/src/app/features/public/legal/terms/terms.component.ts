import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ContentPageComponent } from '../../../../shared/layout/content-page/content-page.component';

/**
 * Public Terms page (TASK-P16-002). Portfolio/demo terms derived from the
 * implemented booking, payment-test, ticket, refund-by-reference, and AI
 * behavior. Contains no invented company, SLA, certification, or jurisdiction
 * claim; unresolved operator facts are explicit gates owned by the deployer.
 */
@Component({
  selector: 'app-terms',
  standalone: true,
  imports: [ContentPageComponent, RouterLink],
  templateUrl: './terms.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TermsComponent {
  readonly toc = [
    { id: 'demo-nature', label: 'Portfolio demo nature' },
    { id: 'accounts-guest', label: 'Accounts and guest checkout' },
    { id: 'reservations', label: 'Reservations and availability' },
    { id: 'payments', label: 'Payments and test mode' },
    { id: 'tickets', label: 'Tickets and QR access' },
    { id: 'refunds', label: 'Refunds and cancellations' },
    { id: 'acceptable-use', label: 'Acceptable use' },
    { id: 'ai-assistant', label: 'AI assistant' },
    { id: 'ip-availability', label: 'Intellectual property and availability' },
    { id: 'governing-law', label: 'Governing law' },
  ] as const;
}
