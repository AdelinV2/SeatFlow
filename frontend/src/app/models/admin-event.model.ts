import { EventCategory, EventStatus } from './event.model';

export interface CreateEventRequest {
  title: string;
  description: string;
  category: EventCategory | string;
  bannerUrl: string;
  // P12-007: legacy eventDate write removed. Schedule is created exclusively
  // via POST /api/admin/events/{id}/sessions after the catalog event exists.
  venueId: string;
}

export interface UpdateEventRequest {
  title?: string;
  description?: string;
  category?: EventCategory | string;
  bannerUrl?: string;
  // P12-007: legacy eventDate write removed; sessions own the schedule.
  status?: EventStatus;
}

export interface CreateEventSessionRequest {
  startsAt: string;
  endsAt: string;
  saleStartsAt?: string | null;
  saleEndsAt?: string | null;
  timezone?: string | null;
}

export interface UpdateEventSessionRequest {
  startsAt: string;
  endsAt: string;
  saleStartsAt?: string | null;
  saleEndsAt?: string | null;
  timezone?: string | null;
}

export interface PricingTierConfig {
  sectionId: string;
  categoryName: string;
  price: number;
  currency: string;
}

export interface ConfigurePricingRequest {
  pricingTiers: PricingTierConfig[];
}

export interface BannerPreset {
  id: string;
  title: string;
  category: string;
  url: string;
}
