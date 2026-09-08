import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ContentPageComponent } from '../../../../shared/layout/content-page/content-page.component';

/**
 * Public security and trust page (TASK-P16-002). Only verifiable high-level
 * claims backed by code/config. No certifications, residency, SLA, or secret
 * material. No exact thresholds or bypass details.
 */
@Component({
  selector: 'app-security',
  standalone: true,
  imports: [ContentPageComponent, RouterLink],
  templateUrl: './security.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SecurityComponent {
  readonly toc = [
    { id: 'authentication', label: 'Authentication and authorization' },
    { id: 'booking-integrity', label: 'Booking integrity' },
    { id: 'payments', label: 'Payments' },
    { id: 'guest-access', label: 'Guest ticket access' },
    { id: 'platform-controls', label: 'Platform controls' },
    { id: 'ai-boundary', label: 'AI assistant boundary' },
    { id: 'not-claimed', label: 'What is not claimed' },
  ] as const;
}
