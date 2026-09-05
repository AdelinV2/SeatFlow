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
  currency?: string;
  status: SeatStatus;
  isActive: boolean;
  positionX?: number;
  positionY?: number;
  sectionPositionX?: number;
  sectionPositionY?: number;
  sectionWidth?: number;
  sectionHeight?: number;
  sectionRotationDeg?: number;
  sectionZIndex?: number;
  sectionColor?: string;
  sectionShapeMetadata?: Record<string, unknown> | object | null;
  categoryName?: string;
  pricingTierId?: string;
  pricingTiers?: {
    id?: string;
    categoryName?: string;
    price: number;
    currency: string;
  }[];
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

export interface SeatMapSeatResponse {
  seatId: string;
  rowLabel: string;
  seatNumber: number;
  gridX: number;
  gridY: number;
  isActive: boolean;
  positionX?: number;
  positionY?: number;
}

export interface SeatMapSectionResponse {
  sectionId: string;
  name: string;
  rowCount: number;
  colCount: number;
  isActive?: boolean;
  positionX?: number;
  positionY?: number;
  width?: number;
  height?: number;
  rotationDeg?: number;
  zIndex?: number;
  shapeMetadata?: Record<string, unknown> | object | null;
  seats: SeatMapSeatResponse[];
  pricingTiers?: {
    id?: string;
    sectionId: string;
    categoryName?: string;
    price: number;
    currency?: string;
  }[];
}

export interface EventSeatMapElementGeometry {
  x: number;
  y: number;
  width: number;
  height: number;
  rotationDeg?: number;
}

export interface EventSeatMapLayoutElement {
  elementId: string;
  type: string;
  label?: string | null;
  /**
   * Null when the venue row carries no usable geometry (backend
   * EventServiceImpl.toLayoutElement emits null then). Read-only consumers
   * must drop such elements instead of rendering invisible zero-size rects.
   */
  geometry: EventSeatMapElementGeometry | null;
  zIndex?: number;
}

export interface EventSeatMapResponse {
  eventId: string;
  venueId: string;
  eventTitle: string;
  status?: string;
  eventDate: string;
  venueName: string;
  venueCapacity: number;
  totalConfiguredSeats: number;
  sections: SeatMapSectionResponse[];
  layoutVersion?: number;
  layoutElements?: EventSeatMapLayoutElement[];
  elements?: EventSeatMapLayoutElement[];
}
