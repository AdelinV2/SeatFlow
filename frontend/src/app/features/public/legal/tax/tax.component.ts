import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ContentPageComponent } from '../../../../shared/layout/content-page/content-page.component';

/**
 * Public tax and payment-demo disclosure (TASK-P16-003). Explains the
 * implemented Stripe Tax preview boundary (tax-inclusive breakdown for a
 * billing address, transmitted for preview and not stored beyond the payment
 * tax/net record), USD Test Mode behavior, and the fact that previews are
 * informational demo values. This page gives no tax advice and promises no
 * legally valid invoice or accounting treatment.
 */
@Component({
  selector: 'app-tax',
  standalone: true,
  imports: [ContentPageComponent, RouterLink],
  templateUrl: './tax.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TaxComponent {
  readonly toc = [
    { id: 'demo', label: 'Demo and Test Mode' },
    { id: 'currency', label: 'Currency and displayed prices' },
    { id: 'preview', label: 'Tax preview: inputs and outputs' },
    { id: 'records', label: 'What SeatFlow keeps' },
    { id: 'final-amount', label: 'Which amount is final' },
    { id: 'refunds', label: 'Refunds and tax' },
    { id: 'no-advice', label: 'No tax advice or invoice promise' },
    { id: 'related', label: 'Related pages' },
  ] as const;
}
