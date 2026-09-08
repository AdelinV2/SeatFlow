import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ContentPageComponent } from '../../../../shared/layout/content-page/content-page.component';

/**
 * Public privacy notice (TASK-P16-002). GDPR Article 13 transparency built from
 * the verified P16-002 storage/provider inventory. Unresolved controller,
 * retention, and provider facts are explicit gates, never invented values.
 */
@Component({
  selector: 'app-privacy',
  standalone: true,
  imports: [ContentPageComponent, RouterLink],
  templateUrl: './privacy.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PrivacyComponent {
  readonly toc = [
    { id: 'demo-context', label: 'Demo context' },
    { id: 'controller', label: 'Controller and contact' },
    { id: 'data-categories', label: 'Data categories' },
    { id: 'purposes-bases', label: 'Purposes and legal bases' },
    { id: 'recipients', label: 'Recipients and providers' },
    { id: 'transfers', label: 'International transfers' },
    { id: 'retention', label: 'Retention' },
    { id: 'rights', label: 'Your rights' },
    { id: 'necessity', label: 'Is providing data required?' },
    { id: 'automated', label: 'Automated decisions' },
    { id: 'security', label: 'Security' },
    { id: 'related', label: 'Related pages' },
  ] as const;
}
