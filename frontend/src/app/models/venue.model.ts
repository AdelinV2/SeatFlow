export type LayoutElementType = 'STAGE' | 'AISLE' | 'LABEL' | 'BARRIER' | 'DECORATION';

export interface LayoutGeometry {
  x: number;
  y: number;
  width: number;
  height: number;
  rotationDeg: number;
}

export interface VenueLayoutElement {
  elementId: string | null;
  type: LayoutElementType;
  label: string | null;
  geometry: LayoutGeometry;
  zIndex: number;
}

export interface VenueSectionSeat {
  seatId: string | null;
  rowLabel: string;
  seatNumber: number;
  gridX: number;
  gridY: number;
  positionX: number;
  positionY: number;
  isActive: boolean;
}

export interface VenueSectionLayout {
  sectionId: string | null;
  /**
   * Stable client-side draft key used to target sections inside the versioned
   * editor draft. Persisted sections use their `sectionId`; never-saved draft
   * sections use a generated UUID. Never persisted to the backend and ignored
   * by canonical dirty-checking/save serialization (see editor state service).
   */
  draftKey?: string | null;
  name: string;
  rowCount: number;
  colCount: number;
  isActive: boolean;
  positionX: number;
  positionY: number;
  width: number;
  height: number;
  rotationDeg: number;
  zIndex: number;
  shapeMetadata: object | null;
  seats: VenueSectionSeat[];
}

export interface VenueLayout {
  venueId: string;
  name: string;
  address?: string;
  latitude?: number;
  longitude?: number;
  capacity: number;
  totalConfiguredSeats: number;
  layoutVersion: number;
  sections: VenueSectionLayout[];
  elements: VenueLayoutElement[];
}

export interface SaveVenueLayoutRequest {
  layoutVersion: number;
  sections: VenueSectionLayout[];
  elements: VenueLayoutElement[];
}

export type DeepReadonly<T> = T extends (...args: never[]) => unknown
  ? T
  : T extends ReadonlyArray<infer U>
    ? ReadonlyArray<DeepReadonly<U>>
    : T extends Array<infer U>
      ? ReadonlyArray<DeepReadonly<U>>
      : T extends object
        ? { readonly [K in keyof T]: DeepReadonly<T[K]> }
        : T;

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
