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
import { EventDetail, EventPricingTier, VenueDetail } from '../../../models/event.model';
import { EventApiService } from '../../../services/event-api.service';
import { NominatimGeocodingService } from '../../../services/nominatim-geocoding.service';
import { VenueApiService } from '../../../services/venue-api.service';
import { StatusBadgeComponent } from '../../../shared/components/status-badge/status-badge.component';
import { CurrencyFormatPipe } from '../../../shared/pipes/currency-format.pipe';
import { DateFormatPipe } from '../../../shared/pipes/date-format.pipe';
import { VenueMapViewComponent } from '../venue-map-view/venue-map-view.component';

interface PricingSection {
  id: string;
  name: string;
  tiers: EventPricingTier[];
}

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
  private readonly geocodingService = inject(NominatimGeocodingService);
  private readonly venueApiService = inject(VenueApiService);

  readonly id = input<string>();

  readonly event = signal<EventDetail | null>(null);
  readonly venue = signal<VenueDetail | null>(null);
  readonly isLoading = signal<boolean>(true);
  readonly errorMessage = signal<string | null>(null);
  private readonly geocodedCoordinates = signal<{ lat: number; lng: number } | null>(null);

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

  readonly pricingSections = computed<PricingSection[]>(() => {
    const ev = this.event();
    if (!ev?.pricingTiers?.length) return [];

    const sectionNames = new Map<string, string>();
    for (const section of this.venue()?.sections ?? []) {
      sectionNames.set(section.id, section.name);
    }

    const grouped = new Map<string, PricingSection>();
    for (const tier of ev.pricingTiers) {
      const sectionId = tier.sectionId || 'general';
      const section = grouped.get(sectionId) ?? {
        id: sectionId,
        name: tier.sectionName || sectionNames.get(sectionId) || 'Venue Section',
        tiers: [],
      };
      section.tiers.push(tier);
      grouped.set(sectionId, section);
    }

    return [...grouped.values()];
  });

  readonly venueCoordinates = computed<{ lat: number; lng: number }>(() => {
    const v = this.venue();
    const ev = this.event();
    const fallback = this.geocodedCoordinates();

    if (this.hasCoordinates(v)) {
      return { lat: v.latitude, lng: v.longitude };
    }
    if (this.hasCoordinates(ev)) {
      return { lat: ev.latitude, lng: ev.longitude };
    }
    if (fallback) return fallback;

    return { lat: 45.7541, lng: 21.2259 };
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
    this.geocodedCoordinates.set(null);

    this.eventApiService.getEventById(eventId).subscribe({
      next: (detail) => {
        this.event.set(detail);
        this.isLoading.set(false);

        if (detail.venueId) {
          this.loadVenue(detail.venueId);
        } else if (!this.hasCoordinates(detail) && (detail.venueName || detail.venueCity || detail.venueAddress)) {
          this.geocodeEvent(detail);
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
        if (!this.hasCoordinates(v)) {
          this.geocodeVenue(v);
        }
      },
      error: () => {
        const ev = this.event();
        if (ev && !this.hasCoordinates(ev)) {
          this.geocodeEvent(ev);
        }
      },
    });
  }

  private geocodeVenue(venue: VenueDetail): void {
    const candidates = this.buildGeocodeCandidates(venue.name, venue.address, venue.city, venue.country);
    if (candidates.length === 0) return;

    this.geocodingService.geocodeBestMatch(candidates).subscribe((result) => {
      if (result && Number.isFinite(result.lat) && Number.isFinite(result.lon)) {
        this.geocodedCoordinates.set({ lat: result.lat, lng: result.lon });
      }
    });
  }

  private geocodeEvent(event: EventDetail): void {
    const candidates = this.buildGeocodeCandidates(
      event.venueName,
      event.venueAddress,
      event.venueCity,
      event.venueCountry,
    );
    if (candidates.length === 0) return;

    this.geocodingService.geocodeBestMatch(candidates).subscribe((result) => {
      if (result && Number.isFinite(result.lat) && Number.isFinite(result.lon)) {
        this.geocodedCoordinates.set({ lat: result.lat, lng: result.lon });
      }
    });
  }

  private buildGeocodeCandidates(
    name?: string,
    address?: string,
    city?: string,
    country?: string,
  ): string[] {
    const cleanName = name?.trim();
    const cleanAddr = address?.trim();
    const cleanCity = city?.trim();
    const cleanCountry = country?.trim();

    const candidates: string[] = [];

    // 1. Exact street address / full venue location
    if (cleanAddr) {
      candidates.push(cleanAddr);
    }
    // 2. Venue name with city
    if (cleanName && cleanCity) {
      candidates.push(`${cleanName}, ${cleanCity}`);
    }
    // 3. Venue name with city and country
    if (cleanName && cleanCity && cleanCountry) {
      candidates.push(`${cleanName}, ${cleanCity}, ${cleanCountry}`);
    }
    // 4. Address with city if address doesn't already contain city
    if (cleanAddr && cleanCity && !cleanAddr.toLowerCase().includes(cleanCity.toLowerCase())) {
      candidates.push(`${cleanAddr}, ${cleanCity}`);
    }
    // 5. Venue name only
    if (cleanName) {
      candidates.push(cleanName);
    }
    // 6. City with country / city only
    if (cleanCity && cleanCountry) {
      candidates.push(`${cleanCity}, ${cleanCountry}`);
    } else if (cleanCity) {
      candidates.push(cleanCity);
    }

    return [...new Set(candidates.filter(Boolean))];
  }

  private hasCoordinates(
    location: { latitude?: number; longitude?: number } | null | undefined,
  ): location is { latitude: number; longitude: number } {
    return (
      typeof location?.latitude === 'number' &&
      Number.isFinite(location.latitude) &&
      typeof location?.longitude === 'number' &&
      Number.isFinite(location.longitude) &&
      (location.latitude !== 0 || location.longitude !== 0)
    );
  }
}
