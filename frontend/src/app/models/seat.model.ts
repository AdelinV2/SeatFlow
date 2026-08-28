export type SeatStatus = 'AVAILABLE' | 'HELD' | 'SOLD' | 'RESERVED' | 'DISABLED';

export interface Seat {
  id: string;
  sectionId: string;
  sectionName?: string;
  rowLabel: string;
  seatNumber: number;
  gridX: number;
  gridY: number;
  price: number;
  status: SeatStatus;
  isActive: boolean;
}

export interface SeatStatusUpdate {
  eventId: string;
  seatId: string;
  status: SeatStatus;
  expiresAt?: string;
  timestamp: string;
}

export interface SeatStatusUpdateMessage {
  eventId: string;
  seatIds: string[];
  status: SeatStatus;
  holdExpiresAt?: string;
  timestamp: string;
}

export interface SeatAvailabilityResponse {
  eventId: string;
  seatStatuses?: {
    seatId: string;
    status: SeatStatus;
  }[];
  seats?: {
    seatId: string;
    status: SeatStatus;
  }[];
}

export interface SeatMapSectionResponse {
  sectionId: string;
  name: string;
  rowCount: number;
  colCount: number;
  seats: {
    seatId: string;
    rowLabel: string;
    seatNumber: number;
    gridX: number;
    gridY: number;
    isActive: boolean;
  }[];
  pricingTiers?: {
    sectionId: string;
    price: number;
  }[];
}

export interface EventSeatMapResponse {
  eventId: string;
  venueId: string;
  eventTitle: string;
  eventDate: string;
  venueName: string;
  venueCapacity: number;
  totalConfiguredSeats: number;
  sections: SeatMapSectionResponse[];
}
