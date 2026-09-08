import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ContentPageComponent } from '../../../shared/layout/content-page/content-page.component';

/**
 * Public API documentation landing page (TASK-P16-004). Portfolio overview
 * only: API domains, safe authentication summary, server-authoritative
 * invariants, and protected-surface notice. No live Swagger proxy, no internal
 * hostnames or ports, no tokens or secrets, no bypass instructions, and no
 * actuator/admin endpoint inventory.
 */
@Component({
  selector: 'app-api-docs',
  standalone: true,
  imports: [ContentPageComponent, RouterLink],
  templateUrl: './api-docs.component.html',
  styleUrl: './api-docs.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ApiDocsComponent {
  readonly toc = [
    { id: 'domains', label: 'API domains' },
    { id: 'authentication', label: 'Authentication' },
    { id: 'invariants', label: 'Server-authoritative invariants' },
    { id: 'live-docs', label: 'Live documentation' },
    { id: 'protected', label: 'Protected surfaces' },
  ] as const;
}
