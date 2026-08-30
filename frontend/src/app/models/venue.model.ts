export interface VenueSectionSeat {
  seatId: string;
  rowLabel: string;
  seatNumber: number;
  gridX: number;
  gridY: number;
  isActive: boolean;
}

export interface VenueSectionLayout {
  sectionId: string;
  name: string;
  rowCount: number;
  colCount: number;
  seats: VenueSectionSeat[];
}

export interface VenueLayout {
  venueId: string;
  name: string;
  address?: string;
  latitude?: number;
  longitude?: number;
  capacity: number;
  totalConfiguredSeats?: number;
  sections: VenueSectionLayout[];
}

export interface VenueSummary {
  id: string;
  name: string;
  address: string;
  city: string;
  country: string;
  capacity: number;
  latitude?: number;
  longitude?: number;
  totalConfiguredSeats?: number;
  createdAt?: string;
}

export interface CreateVenueRequest {
  name: string;
  address: string;
  city: string;
  country?: string;
  capacity: number;
  latitude?: number;
  longitude?: number;
}

export interface UpdateVenueRequest {
  name?: string;
  address?: string;
  city?: string;
  country?: string;
  capacity?: number;
  latitude?: number;
  longitude?: number;
}

export interface CreateSectionRequest {
  name: string;
  rowCount: number;
  colCount: number;
}

export interface UpdateSeatStatusRequest {
  isActive: boolean;
}
