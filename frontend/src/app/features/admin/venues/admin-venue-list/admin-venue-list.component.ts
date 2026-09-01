import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { AdminVenueApiService } from '../../../../services/admin-venue-api.service';
import { VenueSummary } from '../../../../models/venue.model';
import { SkeletonLoaderComponent } from '../../../../shared/components/skeleton-loader/skeleton-loader.component';

@Component({
  selector: 'app-admin-venue-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    FormsModule,
    SkeletonLoaderComponent,
  ],
  templateUrl: './admin-venue-list.component.html',
  styleUrl: './admin-venue-list.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminVenueListComponent implements OnInit {
  private readonly venueApi = inject(AdminVenueApiService);

  readonly venues = signal<VenueSummary[]>([]);
  readonly isLoading = signal<boolean>(true);
  readonly searchQuery = signal<string>('');
  readonly selectedCity = signal<string>('ALL');

  readonly uniqueCities = computed(() => {
    const list = this.venues().map((v) => v.city).filter(Boolean);
    return ['ALL', ...Array.from(new Set(list))];
  });

  readonly filteredVenues = computed(() => {
    const query = this.searchQuery().toLowerCase().trim();
    const city = this.selectedCity();

    return this.venues().filter((v) => {
      const matchesQuery =
        !query ||
        v.name.toLowerCase().includes(query) ||
        v.address.toLowerCase().includes(query) ||
        v.city.toLowerCase().includes(query);

      const matchesCity = city === 'ALL' || v.city === city;
      return matchesQuery && matchesCity;
    });
  });

  ngOnInit(): void {
    this.loadVenues();
  }

  loadVenues(): void {
    this.isLoading.set(true);
    this.venueApi.getVenues({ size: 50 }).subscribe({
      next: (res) => {
        this.venues.set(res.content || []);
        this.isLoading.set(false);
      },
      error: () => {
        this.venues.set([]);
        this.isLoading.set(false);
      },
    });
  }

  onSearchChange(val: string): void {
    this.searchQuery.set(val);
  }

  onCitySelect(city: string): void {
    this.selectedCity.set(city);
  }
}
