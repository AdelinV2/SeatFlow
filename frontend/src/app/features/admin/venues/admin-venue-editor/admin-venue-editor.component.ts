import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  inject,
  OnDestroy,
  OnInit,
  PLATFORM_ID,
  signal,
  viewChild,
} from '@angular/core';
import { CommonModule, isPlatformBrowser } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import * as L from 'leaflet';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import { AdminVenueApiService } from '../../../../services/admin-venue-api.service';
import {
  GeocodingResult,
  NominatimGeocodingService,
} from '../../../../services/nominatim-geocoding.service';
import { ThemeService } from '../../../../core/theme/theme.service';

@Component({
  selector: 'app-admin-venue-editor',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    RouterModule,
  ],
  templateUrl: './admin-venue-editor.component.html',
  styleUrl: './admin-venue-editor.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminVenueEditorComponent implements OnInit, AfterViewInit, OnDestroy {
  private readonly fb = inject(FormBuilder);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  private readonly venueApi = inject(AdminVenueApiService);
  private readonly geocodingService = inject(NominatimGeocodingService);
  private readonly themeService = inject(ThemeService);
  private readonly platformId = inject(PLATFORM_ID);

  readonly mapContainer = viewChild<ElementRef<HTMLDivElement>>('mapContainer');

  readonly isEditMode = signal<boolean>(false);
  readonly venueId = signal<string | null>(null);
  readonly isLoading = signal<boolean>(false);
  readonly isSaving = signal<boolean>(false);
  readonly errorMessage = signal<string | null>(null);

  // Address search autocompletion
  readonly isSearchingAddress = signal<boolean>(false);
  readonly searchSuggestions = signal<GeocodingResult[]>([]);
  private readonly addressSearch$ = new Subject<string>();

  // Leaflet map instance
  private map: L.Map | null = null;
  private tileLayer: L.TileLayer | null = null;
  private marker: L.Marker | null = null;
  private resizeTimeoutId?: ReturnType<typeof setTimeout>;

  readonly venueForm = this.fb.group({
    name: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(100)]],
    capacity: [500, [Validators.required, Validators.min(1), Validators.max(500000)]],
    address: ['', [Validators.required, Validators.minLength(3), Validators.maxLength(255)]],
    city: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(100)]],
    country: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(100)]],
    latitude: [51.5074, [Validators.required]],
    longitude: [-0.1278, [Validators.required]],
  });

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (id && id !== 'new') {
      this.isEditMode.set(true);
      this.venueId.set(id);
      this.loadVenueDetails(id);
    }

    this.setupAddressAutocomplete();
  }

  ngAfterViewInit(): void {
    if (!isPlatformBrowser(this.platformId)) {
      return;
    }

    const container = this.mapContainer()?.nativeElement;
    if (container) {
      this.initMap(container);
    }
  }

  private setupAddressAutocomplete(): void {
    this.addressSearch$
      .pipe(
        debounceTime(350),
        distinctUntilChanged(),
        switchMap((query) => {
          if (!query || query.length < 3) {
            this.isSearchingAddress.set(false);
            return [];
          }
          this.isSearchingAddress.set(true);
          return this.geocodingService.searchAddress(query);
        })
      )
      .subscribe({
        next: (results) => {
          this.searchSuggestions.set(results);
          this.isSearchingAddress.set(false);
        },
        error: () => {
          this.searchSuggestions.set([]);
          this.isSearchingAddress.set(false);
        },
      });
  }

  onAddressInput(event: Event): void {
    const target = event.target as HTMLInputElement;
    const value = target.value;
    this.addressSearch$.next(value);
  }

  selectAddressSuggestion(suggestion: GeocodingResult): void {
    this.venueForm.patchValue({
      address: suggestion.street || suggestion.displayName,
      city: suggestion.city || this.venueForm.value.city || '',
      country: suggestion.country || this.venueForm.value.country || '',
      latitude: suggestion.lat,
      longitude: suggestion.lon,
    });

    this.searchSuggestions.set([]);
    this.updateMapPosition(suggestion.lat, suggestion.lon, 16);
  }

  private loadVenueDetails(id: string): void {
    this.isLoading.set(true);
    this.venueApi.getVenueById(id).subscribe({
      next: (venue) => {
        this.venueForm.patchValue({
          name: venue.name,
          capacity: venue.capacity,
          address: venue.address,
          city: venue.city,
          country: venue.country || '',
        });
        this.isLoading.set(false);
      },
      error: (err) => {
        this.errorMessage.set(err?.error?.message || 'Failed to load venue details.');
        this.isLoading.set(false);
      },
    });
  }

  private initMap(container: HTMLElement): void {
    const lat = this.venueForm.value.latitude || 44.4323;
    const lng = this.venueForm.value.longitude || 26.1063;
    const isDark = this.themeService.isDark();

    this.map = L.map(container, {
      center: [lat, lng],
      zoom: 14,
      zoomControl: true,
      scrollWheelZoom: true,
    });

    this.updateTileLayer(isDark);
    this.createDraggableMarker(lat, lng);

    this.map.on('click', (e: L.LeafletMouseEvent) => {
      const clickedLat = e.latlng.lat;
      const clickedLng = e.latlng.lng;
      this.updateMapPosition(clickedLat, clickedLng);
      this.onLocationChanged(clickedLat, clickedLng);
    });

    this.resizeTimeoutId = setTimeout(() => {
      this.map?.invalidateSize();
    }, 250);
  }

  private updateTileLayer(isDark: boolean): void {
    if (!this.map) return;

    if (this.tileLayer) {
      this.map.removeLayer(this.tileLayer);
    }

    const tileUrl = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';

    this.tileLayer = L.tileLayer(tileUrl, {
      maxZoom: 19,
      className: isDark ? 'dark-map-tiles' : '',
      attribution:
        '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a> contributors',
    }).addTo(this.map);
  }

  private createDraggableMarker(lat: number, lng: number): void {
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
    });

    this.marker = L.marker([lat, lng], {
      icon: pinIcon,
      draggable: true,
    }).addTo(this.map);

    this.marker.on('dragend', () => {
      const pos = this.marker?.getLatLng();
      if (pos) {
        this.onLocationChanged(pos.lat, pos.lng);
      }
    });
  }

  private updateMapPosition(lat: number, lng: number, zoom?: number): void {
    if (!this.map) return;
    this.map.setView([lat, lng], zoom || this.map.getZoom());
    if (this.marker) {
      this.marker.setLatLng([lat, lng]);
    } else {
      this.createDraggableMarker(lat, lng);
    }
  }

  private onLocationChanged(lat: number, lng: number): void {
    this.venueForm.patchValue({
      latitude: parseFloat(lat.toFixed(6)),
      longitude: parseFloat(lng.toFixed(6)),
    });

    // Reverse geocode to update address, city, and country inputs on every pin move
    this.geocodingService.reverseGeocode(lat, lng).subscribe({
      next: (res) => {
        if (res) {
          const updates: {
            address?: string;
            city?: string;
            country?: string;
          } = {};

          if (res.street || res.displayName) {
            updates.address = res.street || res.displayName;
          }
          if (res.city) {
            updates.city = res.city;
          }
          if (res.country) {
            updates.country = res.country;
          }

          this.venueForm.patchValue(updates);
        }
      },
    });
  }

  onSubmit(): void {
    if (this.venueForm.invalid) {
      this.venueForm.markAllAsTouched();
      return;
    }

    this.isSaving.set(true);
    this.errorMessage.set(null);

    const formVal = this.venueForm.getRawValue();
    const payload = {
      name: formVal.name!,
      capacity: Number(formVal.capacity),
      address: formVal.address!,
      city: formVal.city!,
      country: formVal.country || '',
      latitude: Number(formVal.latitude),
      longitude: Number(formVal.longitude),
    };

    if (this.isEditMode() && this.venueId()) {
      this.venueApi.updateVenue(this.venueId()!, payload).subscribe({
        next: (venue) => {
          this.isSaving.set(false);
          this.snackBar.open(`Venue "${venue.name}" updated successfully!`, 'Close', {
            duration: 4000,
            panelClass: 'snack-success',
          });
          this.router.navigate(['/admin/venues']);
        },
        error: (err) => {
          this.isSaving.set(false);
          this.errorMessage.set(err?.error?.message || 'Failed to update venue.');
        },
      });
    } else {
      this.venueApi.createVenue(payload).subscribe({
        next: (venue) => {
          this.isSaving.set(false);
          this.snackBar.open(`Venue "${venue.name}" created successfully!`, 'Close', {
            duration: 4000,
            panelClass: 'snack-success',
          });
          // Redirect to 2D Seat Grid designer for this new venue
          this.router.navigate(['/admin/venues', venue.id, 'designer']);
        },
        error: (err) => {
          this.isSaving.set(false);
          this.errorMessage.set(err?.error?.message || 'Failed to create venue.');
        },
      });
    }
  }

  ngOnDestroy(): void {
    if (this.resizeTimeoutId) {
      clearTimeout(this.resizeTimeoutId);
      this.resizeTimeoutId = undefined;
    }
    if (this.map) {
      this.map.remove();
      this.map = null;
    }
  }
}
