export type EventCategory =
  | 'CONCERT'
  | 'THEATRE'
  | 'SPORTS'
  | 'FESTIVAL'
  | 'COMEDY'
  | 'SYMPHONY'
  | 'OTHER';

export type EventStatus = 'DRAFT' | 'PUBLISHED' | 'CANCELLED' | 'COMPLETED';

export interface EventPricingTier {
  id?: string;
  sectionId: string;
  sectionName?: string;
  categoryName?: string;
  price: number;
  currency: string;
}

export interface EventSummary {
  id: string;
  title: string;
  description?: string;
  category: EventCategory;
  bannerUrl: string;
  eventDate: string;
  venueName?: string;
  status?: EventStatus;
  minPrice: number;
  maxPrice: number;
  currency?: string;
}

export interface EventDetail {
  id: string;
  venueId: string;
  title: string;
  description: string;
  category: EventCategory;
  bannerUrl: string;
  eventDate: string;
  status: EventStatus;
  pricingTiers: EventPricingTier[];
  createdAt: string;
  updatedAt?: string;
  // Enriched venue fields (fetched via VenueApiService.getVenueById(venueId))
  venueName?: string;
  venueAddress?: string;
  venueCity?: string;
  venueCountry?: string;
  latitude?: number;
  longitude?: number;
}

export interface VenueDetail {
  id: string;
  name: string;
  address: string;
  city: string;
  country: string;
  capacity: number;
  latitude?: number;
  longitude?: number;
  sections?: VenueSection[];
}

export interface VenueSection {
  id: string;
  name: string;
  rowCount?: number;
  colCount?: number;
  activeSeatCount?: number;
}

export interface PagedResult<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  isFirst: boolean;
  isLast: boolean;
}

export interface CalendarDay {
  date: Date;
  dayNumber: number;
  isCurrentMonth: boolean;
  isToday: boolean;
  isPast: boolean;
  isSelected: boolean;
  events: EventSummary[];
}

export interface EventListFilter {
  category?: EventCategory | null;
  search?: string | null;
  page?: number;
  size?: number;
  sort?: string;
}
