import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ContentPageComponent } from '../../../../shared/layout/content-page/content-page.component';

/**
 * Public cookies and similar-technologies disclosure (TASK-P16-002). Covers
 * cookies plus localStorage/sessionStorage/SDK storage from the verified
 * inventory. Records NOT_REQUIRED_FOR_CURRENT_RUNTIME: no consent banner is
 * rendered because no optional storage exists; no fake preference modal ships.
 */
@Component({
  selector: 'app-cookies',
  standalone: true,
  imports: [ContentPageComponent, RouterLink],
  templateUrl: './cookies.component.html',
  styleUrl: './cookies.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CookiesComponent {
  readonly toc = [
    { id: 'decision', label: 'Consent decision' },
    { id: 'storage-table', label: 'Storage in use' },
    { id: 'manage', label: 'Manage storage' },
    { id: 'network-requests', label: 'Network requests without storage' },
    { id: 'changes', label: 'When this changes' },
  ] as const;
}
