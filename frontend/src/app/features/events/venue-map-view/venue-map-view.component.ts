import { isPlatformBrowser } from '@angular/common';
import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  input,
  OnDestroy,
  PLATFORM_ID,
  viewChild,
} from '@angular/core';
import * as L from 'leaflet';
import { ThemeService } from '../../../core/theme/theme.service';

@Component({
  selector: 'app-venue-map-view',
  standalone: true,
  imports: [],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './venue-map-view.component.html',
  styleUrl: './venue-map-view.component.scss',
})
export class VenueMapViewComponent implements AfterViewInit, OnDestroy {
  private readonly platformId = inject(PLATFORM_ID);
  private readonly themeService = inject(ThemeService);
  private readonly destroyRef = inject(DestroyRef);

  readonly mapContainer = viewChild<ElementRef<HTMLDivElement>>('mapContainer');

  readonly venueName = input<string>('Venue');
  readonly venueAddress = input<string>('');
  readonly venueCity = input<string>('');
  readonly latitude = input<number>(44.4323);
  readonly longitude = input<number>(26.1063);
  readonly zoom = input<number>(15);

  private map: L.Map | null = null;
  private tileLayer: L.TileLayer | null = null;
  private marker: L.Marker | null = null;

  readonly googleMapsUrl = computed(() => {
    const lat = this.latitude();
    const lng = this.longitude();
    const query = `${this.venueName()} ${this.venueAddress()} ${this.venueCity()}`.trim();
    return `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(query || `${lat},${lng}`)}`;
  });

  readonly appleMapsUrl = computed(() => {
    const lat = this.latitude();
    const lng = this.longitude();
    const name = this.venueName().trim();
    return `https://maps.apple.com/?q=${encodeURIComponent(name)}&ll=${lat},${lng}`;
  });

  readonly wazeUrl = computed(() => {
    const lat = this.latitude();
    const lng = this.longitude();
    return `https://waze.com/ul?ll=${lat},${lng}&navigate=yes`;
  });

  constructor() {
    // Dynamic theme-adaptive tile switching effect
    effect(() => {
      const isDark = this.themeService.isDark();
      if (this.map) {
        this.updateTileLayer(isDark);
      }
    });

    // Reactive coordinate and venue update effect
    effect(() => {
      const lat = this.latitude();
      const lng = this.longitude();
      const zoom = this.zoom();
      const venue = this.venueName();
      const address = this.venueAddress();
      const city = this.venueCity();

      if (this.map) {
        this.map.setView([lat, lng], zoom);
        this.updateMarker(lat, lng, venue, address, city);
      }
    });
  }

  private resizeTimeoutId?: ReturnType<typeof setTimeout>;

  ngAfterViewInit(): void {
    if (!isPlatformBrowser(this.platformId)) {
      return;
    }

    const container = this.mapContainer()?.nativeElement;
    if (container) {
      this.initMap(container);
    }
  }

  private initMap(container: HTMLElement): void {
    const lat = this.latitude();
    const lng = this.longitude();
    const zoom = this.zoom();
    const isDark = this.themeService.isDark();

    this.map = L.map(container, {
      center: [lat, lng],
      zoom,
      zoomControl: true,
      scrollWheelZoom: false,
    });

    this.updateTileLayer(isDark);
    this.updateMarker(lat, lng, this.venueName(), this.venueAddress(), this.venueCity());

    // Invalidate size to ensure crisp rendering after layout paint
    this.resizeTimeoutId = setTimeout(() => {
      this.map?.invalidateSize();
    }, 250);
  }

  private updateTileLayer(isDark: boolean): void {
    if (!this.map) return;

    if (this.tileLayer) {
      this.map.removeLayer(this.tileLayer);
    }

    const tileUrl = isDark
      ? 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png'
      : 'https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png';

    this.tileLayer = L.tileLayer(tileUrl, {
      subdomains: 'abcd',
      maxZoom: 19,
      attribution:
        '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a> contributors &copy; <a href="https://carto.com/attributions" target="_blank" rel="noopener">CARTO</a>',
    }).addTo(this.map);
  }

  private updateMarker(
    lat: number,
    lng: number,
    venueName: string,
    venueAddress: string,
    venueCity: string,
  ): void {
    if (!this.map) return;

    if (this.marker) {
      this.map.removeLayer(this.marker);
    }

    const pinIcon = L.divIcon({
      className: 'sf-custom-venue-pin',
      html: `
        <div class="pin-wrapper">
          <span class="pin-pulse"></span>
          <div class="pin-marker">
            <svg viewBox="0 0 24 24" class="pin-svg" fill="currentColor">
              <path d="M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5a2.5 2.5 0 0 1 0-5 2.5 2.5 0 0 1 0 5z"/>
            </svg>
          </div>
        </div>
      `,
      iconSize: [36, 36],
      iconAnchor: [18, 36],
      popupAnchor: [0, -36],
    });

    this.marker = L.marker([lat, lng], { icon: pinIcon }).addTo(this.map);

    const fullAddress = [venueAddress, venueCity].filter(Boolean).join(', ');
    const popupContent = `
      <div class="sf-map-popup">
        <h4 class="popup-title">${venueName || 'Venue'}</h4>
        ${fullAddress ? `<p class="popup-address">${fullAddress}</p>` : ''}
      </div>
    `;

    this.marker.bindPopup(popupContent);
  }

  private cleanupMap(): void {
    if (this.resizeTimeoutId) {
      clearTimeout(this.resizeTimeoutId);
      this.resizeTimeoutId = undefined;
    }
    if (this.map) {
      this.map.remove();
      this.map = null;
      this.tileLayer = null;
      this.marker = null;
    }
  }

  ngOnDestroy(): void {
    this.cleanupMap();
  }
}
