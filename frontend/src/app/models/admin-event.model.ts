import { EventCategory, EventStatus } from './event.model';

export interface CreateEventRequest {
  title: string;
  description: string;
  category: EventCategory | string;
  bannerUrl: string;
  eventDate: string;
  venueId: string;
}

export interface UpdateEventRequest {
  title?: string;
  description?: string;
  category?: EventCategory | string;
  bannerUrl?: string;
  eventDate?: string;
  status?: EventStatus;
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
