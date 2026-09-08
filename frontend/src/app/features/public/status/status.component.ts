import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ContentPageComponent } from '../../../shared/layout/content-page/content-page.component';
import { SystemHealthService } from '../../../services/system-health.service';

/**
 * Public platform status page (TASK-P16-004). Sanitized coarse health only:
 * overall status text, two user-facing capabilities derived from the existing
 * probes, last-checked time, and a manual refresh. Never serializes raw health
 * responses, hostnames, topology, versions, stack traces, or secrets, and
 * never presents demo probes as an SLA.
 */
@Component({
  selector: 'app-status',
  standalone: true,
  imports: [ContentPageComponent, RouterLink, DatePipe],
  templateUrl: './status.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatusComponent {
  private readonly health = inject(SystemHealthService);

  readonly toc = [
    { id: 'current-status', label: 'Current status' },
    { id: 'capabilities', label: 'Capabilities' },
    { id: 'about', label: 'About these checks' },
  ] as const;

  readonly status = this.health.status;
  readonly statusLabel = this.health.statusLabel;
  readonly lastChecked = this.health.lastChecked;
  readonly serviceReport = this.health.serviceReport;

  /** Sanitized user-facing capabilities; booleans only, no raw payloads. */
  readonly eventBrowsingUp = computed(() => {
    const report = this.serviceReport();
    return report.venues === 'UP' || report.events === 'UP';
  });

  readonly bookingGatewayUp = computed(() => this.serviceReport().gateway === 'UP');

  refresh(): void {
    this.health.checkHealth();
  }
}
