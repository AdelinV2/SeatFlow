import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ContentPageComponent } from '../../../../shared/layout/content-page/content-page.component';
import { PUBLIC_SUPPORT_CONTACT } from '../support-contact.config';

/**
 * Public contact page (TASK-P16-004). No form backend exists, so no form is
 * rendered: only a configured `mailto:` channel (or an explicit owner
 * configuration gate), plus honest statements that no organizer directory,
 * staffed desk, SLA, DPO, or self-service portal exists.
 */
@Component({
  selector: 'app-contact',
  standalone: true,
  imports: [ContentPageComponent, RouterLink],
  templateUrl: './contact.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ContactComponent {
  readonly toc = [
    { id: 'demo-feedback', label: 'Portfolio and demo feedback' },
    { id: 'event-support', label: 'Event-specific support' },
    { id: 'privacy-requests', label: 'Privacy and data-rights requests' },
    { id: 'security-reports', label: 'Security reports' },
    { id: 'what-to-include', label: 'What to include' },
  ] as const;

  readonly supportContact = PUBLIC_SUPPORT_CONTACT;
}
