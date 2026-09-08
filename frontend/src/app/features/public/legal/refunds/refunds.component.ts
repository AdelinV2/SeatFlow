import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ContentPageComponent } from '../../../../shared/layout/content-page/content-page.component';

/**
 * Public refund and cancellation policy (TASK-P16-003). Explanatory surface
 * only: the server enforces the Phase 13 / ADR-012 rule (at least 24 hours
 * remain before the event session starts, full-reservation scope). This page
 * states no additional refund promise, advertises no partial/per-ticket flow,
 * exposes no guest self-service endpoint (guests use the support/contact
 * path), and marks the portfolio deployment as Stripe Test Mode with no real
 * money movement.
 */
@Component({
  selector: 'app-refunds',
  standalone: true,
  imports: [ContentPageComponent, RouterLink],
  templateUrl: './refunds.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RefundsComponent {
  readonly toc = [
    { id: 'eligibility', label: 'The 24-hour eligibility rule' },
    { id: 'scope', label: 'Full reservation scope' },
    { id: 'who', label: 'Who can request a refund' },
    { id: 'workflow', label: 'What happens after you request' },
    { id: 'demo', label: 'Portfolio demo and Test Mode' },
    { id: 'related', label: 'Related pages' },
  ] as const;
}
