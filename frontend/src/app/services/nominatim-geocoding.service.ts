import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, Observable, of } from 'rxjs';
import { catchError } from 'rxjs/operators';

export interface GeocodingResult {
  placeId: number;
  displayName: string;
  street: string;
  lat: number;
  lon: number;
  city?: string;
  country?: string;
  postcode?: string;
}

interface RawNominatimAddress {
  city?: string;
  town?: string;
  village?: string;
  municipality?: string;
  country?: string;
  postcode?: string;
  road?: string;
  pedestrian?: string;
  footway?: string;
  street?: string;
  plaza?: string;
  square?: string;
  house_number?: string;
  theatre?: string;
  amenity?: string;
  building?: string;
  historic?: string;
  tourism?: string;
  leisure?: string;
  neighbourhood?: string;
  suburb?: string;
  quarter?: string;
  [key: string]: unknown;
}

interface RawNominatimItem {
  place_id: number;
  display_name: string;
  lat: string;
  lon: string;
  address?: RawNominatimAddress;
}

@Injectable({ providedIn: 'root' })
export class NominatimGeocodingService {
  private readonly http = inject(HttpClient);
  private readonly searchUrl = 'https://nominatim.openstreetmap.org/search';
  private readonly reverseUrl = 'https://nominatim.openstreetmap.org/reverse';

  searchAddress(query: string): Observable<GeocodingResult[]> {
    const trimmed = query.trim();
    if (!trimmed) {
      return of([]);
    }

    return this.http
      .get<RawNominatimItem[]>(this.searchUrl, {
        params: {
          q: trimmed,
          format: 'json',
          addressdetails: '1',
          limit: '5',
        },
      })
      .pipe(
        map((results) =>
          (results || []).map((r) => this.mapRawToGeocodingResult(r))
        ),
        catchError(() => of([]))
      );
  }

  reverseGeocode(lat: number, lon: number): Observable<GeocodingResult | null> {
    return this.http
      .get<RawNominatimItem>(this.reverseUrl, {
        params: {
          lat: lat.toString(),
          lon: lon.toString(),
          format: 'json',
          addressdetails: '1',
        },
      })
      .pipe(
        map((res) => (res ? this.mapRawToGeocodingResult(res) : null)),
        catchError(() => of(null))
      );
  }

  private mapRawToGeocodingResult(raw: RawNominatimItem): GeocodingResult {
    const addr = raw.address;
    const city = addr?.city || addr?.town || addr?.village || addr?.municipality || undefined;
    const country = addr?.country || undefined;
    const postcode = addr?.postcode || undefined;

    let street = '';

    if (addr) {
      const parts: string[] = [];
      const placeName = addr.theatre || addr.amenity || addr.building || addr.historic || addr.tourism || addr.leisure;
      const road = addr.road || addr.pedestrian || addr.footway || addr.street || addr.plaza || addr.square;
      const houseNumber = addr.house_number;
      const neighbourhood = addr.neighbourhood || addr.suburb || addr.quarter;

      if (placeName && placeName !== road && placeName !== city) {
        parts.push(placeName);
      }
      if (road) {
        if (houseNumber) {
          parts.push(`${road} ${houseNumber}`);
        } else {
          parts.push(road);
        }
      }
      if (neighbourhood && neighbourhood !== city && neighbourhood !== road && !parts.includes(neighbourhood)) {
        parts.push(neighbourhood);
      }

      if (parts.length > 0) {
        street = parts.join(', ');
      }
    }

    if (!street) {
      const displayParts = raw.display_name.split(',').map((s) => s.trim());
      if (city) {
        const cityIndex = displayParts.findIndex(
          (p) => p.toLowerCase() === city.toLowerCase()
        );
        if (cityIndex > 0) {
          street = displayParts.slice(0, cityIndex).join(', ');
        }
      }

      if (!street && displayParts.length > 2) {
        street = displayParts.slice(0, displayParts.length - 2).join(', ');
      }

      if (!street) {
        street = raw.display_name;
      }
    }

    return {
      placeId: raw.place_id,
      displayName: raw.display_name,
      street,
      lat: parseFloat(raw.lat),
      lon: parseFloat(raw.lon),
      city,
      country,
      postcode,
    };
  }
}
