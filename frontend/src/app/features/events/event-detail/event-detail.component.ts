import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  OnInit,
  signal,
} from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { EventDetail, VenueDetail } from '../../../models/event.model';
import { EventApiService } from '../../../services/event-api.service';
import { VenueApiService } from '../../../services/venue-api.service';
import { StatusBadgeComponent } from '../../../shared/components/status-badge/status-badge.component';
import { CurrencyFormatPipe } from '../../../shared/pipes/currency-format.pipe';
import { DateFormatPipe } from '../../../shared/pipes/date-format.pipe';
import { VenueMapViewComponent } from '../venue-map-view/venue-map-view.component';

@Component({
  selector: 'app-event-detail',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    StatusBadgeComponent,
    VenueMapViewComponent,
    CurrencyFormatPipe,
    DateFormatPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './event-detail.component.html',
  styleUrl: './event-detail.component.scss',
})
export class EventDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly eventApiService = inject(EventApiService);
  private readonly venueApiService = inject(VenueApiService);

  readonly id = input<string>();

  readonly event = signal<EventDetail | null>(null);
  readonly venue = signal<VenueDetail | null>(null);
  readonly isLoading = signal<boolean>(true);
  readonly errorMessage = signal<string | null>(null);

  readonly minPrice = computed<number>(() => {
    const ev = this.event();
    if (!ev || !ev.pricingTiers || ev.pricingTiers.length === 0) return 0;
    return Math.min(...ev.pricingTiers.map((t) => t.price));
  });

  readonly maxPrice = computed<number>(() => {
    const ev = this.event();
    if (!ev || !ev.pricingTiers || ev.pricingTiers.length === 0) return 0;
    return Math.max(...ev.pricingTiers.map((t) => t.price));
  });

  readonly currency = computed<string>(() => {
    const ev = this.event();
    return ev?.pricingTiers?.[0]?.currency || 'USD';
  });

  readonly venueCoordinates = computed<{ lat: number; lng: number }>(() => {
    const v = this.venue();
    const ev = this.event();

    if (v?.latitude && v?.longitude) {
      return { lat: v.latitude, lng: v.longitude };
    }
    if (ev?.latitude && ev?.longitude) {
      return { lat: ev.latitude, lng: ev.longitude };
    }

    // Default coordinates (e.g. Bucharest / Central City)
    return { lat: 44.4323, lng: 26.1063 };
  });

  constructor() {
    effect(() => {
      const eventId = this.id() || this.route.snapshot.paramMap.get('id');
      if (eventId) {
        this.loadEvent(eventId);
      } else {
        this.errorMessage.set('Event ID is missing');
        this.isLoading.set(false);
      }
    });
  }

  ngOnInit(): void {
    // Initialized via reactive effect
  }

  loadEvent(eventId: string): void {
    this.isLoading.set(true);
    this.errorMessage.set(null);

    this.eventApiService.getEventById(eventId).subscribe({
      next: (detail) => {
        this.event.set(detail);
        this.isLoading.set(false);

        if (detail.venueId) {
          this.loadVenue(detail.venueId);
        }
      },
      error: (err) => {
        this.isLoading.set(false);
        if (err.status === 404) {
          this.errorMessage.set(
            'This event could not be found or is no longer available.',
          );
        } else {
          this.errorMessage.set(
            err?.error?.message || 'Failed to load event details. Please try again later.',
          );
        }
      },
    });
  }

  private loadVenue(venueId: string): void {
    this.venueApiService.getVenueById(venueId).subscribe({
      next: (v) => {
        this.venue.set(v);
      },
      error: () => {
        // Non-fatal if venue service cannot be reached
      },
    });
  }
}
