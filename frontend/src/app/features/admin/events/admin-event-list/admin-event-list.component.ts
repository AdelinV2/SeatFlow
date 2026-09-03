import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatSnackBar } from '@angular/material/snack-bar';
import { AdminEventApiService } from '../../../../services/admin-event-api.service';
import { AdminVenueApiService } from '../../../../services/admin-venue-api.service';
import { EventDetail, EventStatus } from '../../../../models/event.model';
import { SkeletonLoaderComponent } from '../../../../shared/components/skeleton-loader/skeleton-loader.component';
import { DateFormatPipe } from '../../../../shared/pipes/date-format.pipe';

export interface ActionModalState {
  type: 'PUBLISH' | 'CANCEL';
  event: EventDetail;
}

@Component({
  selector: 'app-admin-event-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    FormsModule,
    DateFormatPipe,
    SkeletonLoaderComponent,
  ],
  templateUrl: './admin-event-list.component.html',
  styleUrl: './admin-event-list.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminEventListComponent implements OnInit {
  private readonly adminEventApi = inject(AdminEventApiService);
  private readonly adminVenueApi = inject(AdminVenueApiService);
  private readonly snackBar = inject(MatSnackBar);

  readonly events = signal<EventDetail[]>([]);
  readonly venueNames = signal<Map<string, string>>(new Map());
  readonly isLoading = signal<boolean>(true);
  readonly searchQuery = signal<string>('');
  readonly selectedStatus = signal<string>('ALL');
  readonly selectedCategory = signal<string>('ALL');
  readonly actionInProgressId = signal<string | null>(null);
  readonly modalState = signal<ActionModalState | null>(null);

  readonly statusTabs: { label: string; value: string }[] = [
    { label: 'All Events', value: 'ALL' },
    { label: 'Draft', value: 'DRAFT' },
    { label: 'Published', value: 'PUBLISHED' },
    { label: 'Cancelled', value: 'CANCELLED' },
    { label: 'Completed', value: 'COMPLETED' },
  ];

  readonly categories: string[] = [
    'ALL',
    'CONCERT',
    'THEATRE',
    'SPORTS',
    'FESTIVAL',
    'COMEDY',
    'SYMPHONY',
    'OTHER',
  ];

  readonly counts = computed(() => {
    const all = this.events();
    return {
      all: all.length,
      draft: all.filter((e) => e.status === 'DRAFT').length,
      published: all.filter((e) => e.status === 'PUBLISHED').length,
      cancelled: all.filter((e) => e.status === 'CANCELLED').length,
      completed: all.filter((e) => e.status === 'COMPLETED').length,
    };
  });

  readonly filteredEvents = computed(() => {
    const list = this.events();
    const query = this.searchQuery().toLowerCase().trim();
    const status = this.selectedStatus();
    const cat = this.selectedCategory();
    const venues = this.venueNames();

    return list.filter((event) => {
      const vName = venues.get(event.venueId) || '';
      const matchesQuery =
        !query ||
        event.title.toLowerCase().includes(query) ||
        (event.description && event.description.toLowerCase().includes(query)) ||
        vName.toLowerCase().includes(query);

      const matchesStatus = status === 'ALL' || event.status === status;
      const matchesCategory = cat === 'ALL' || event.category === cat;

      return matchesQuery && matchesStatus && matchesCategory;
    });
  });

  ngOnInit(): void {
    this.loadVenues();
    this.loadEvents();
  }

  loadVenues(): void {
    this.adminVenueApi.getVenues({ size: 100 }).subscribe({
      next: (res) => {
        const map = new Map<string, string>();
        for (const v of res.content || []) {
          map.set(v.id, v.name);
        }
        this.venueNames.set(map);
      },
    });
  }

  loadEvents(): void {
    this.isLoading.set(true);
    this.adminEventApi.getAdminEvents({ size: 100 }).subscribe({
      next: (res) => {
        this.events.set(res.content || []);
        this.isLoading.set(false);
      },
      error: () => {
        this.events.set([]);
        this.isLoading.set(false);
      },
    });
  }

  getVenueName(venueId: string): string {
    return this.venueNames().get(venueId) || 'Venue';
  }

  hasPricing(event: EventDetail): boolean {
    return !!(event.pricingTiers && event.pricingTiers.length > 0);
  }

  openPublishModal(event: EventDetail): void {
    if (!this.hasPricing(event)) {
      this.snackBar.open(
        'Configure section pricing before publishing this event.',
        'Close',
        { duration: 4000 }
      );
      return;
    }
    this.modalState.set({ type: 'PUBLISH', event });
  }

  openCancelModal(event: EventDetail): void {
    this.modalState.set({ type: 'CANCEL', event });
  }

  closeModal(): void {
    this.modalState.set(null);
  }

  confirmAction(): void {
    const modal = this.modalState();
    if (!modal) return;

    const event = modal.event;
    const targetStatus: EventStatus = modal.type === 'PUBLISH' ? 'PUBLISHED' : 'CANCELLED';

    this.actionInProgressId.set(event.id);
    this.closeModal();

    this.adminEventApi.updateEvent(event.id, { status: targetStatus }).subscribe({
      next: (updated) => {
        this.actionInProgressId.set(null);
        this.events.update((list) =>
          list.map((e) => (e.id === updated.id ? { ...e, ...updated } : e))
        );
        this.snackBar.open(
          `Event "${event.title}" has been ${targetStatus.toLowerCase()} successfully!`,
          'Close',
          { duration: 4000 }
        );
      },
      error: (err) => {
        this.actionInProgressId.set(null);
        this.snackBar.open(
          err?.error?.message || `Failed to update status to ${targetStatus}`,
          'Close',
          { duration: 4000 }
        );
      },
    });
  }
}
