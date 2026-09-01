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
import { UserContextService } from '../../../core/auth/user-context.service';
import { AdminVenueApiService } from '../../../services/admin-venue-api.service';
import { AdminUserApiService } from '../../../services/admin-user-api.service';
import { SystemHealthService } from '../../../services/system-health.service';
import { VenueSummary } from '../../../models/venue.model';
import { UserProfile } from '../../../models/user.model';
import { SkeletonLoaderComponent } from '../../../shared/components/skeleton-loader/skeleton-loader.component';

@Component({
  selector: 'app-admin-portal',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    SkeletonLoaderComponent,
  ],
  templateUrl: './admin-portal.component.html',
  styleUrl: './admin-portal.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminPortalComponent implements OnInit {
  readonly userContext = inject(UserContextService);
  readonly health = inject(SystemHealthService);
  private readonly venueApi = inject(AdminVenueApiService);
  private readonly userApi = inject(AdminUserApiService);

  readonly isLoading = signal<boolean>(true);
  readonly venues = signal<VenueSummary[]>([]);
  readonly users = signal<UserProfile[]>([]);
  readonly totalVenues = signal<number>(0);
  readonly totalUsers = signal<number>(0);

  readonly totalCapacity = computed(() =>
    this.venues().reduce((sum, v) => sum + (v.capacity || 0), 0)
  );

  readonly totalConfiguredSeats = computed(() =>
    this.venues().reduce((sum, v) => sum + (v.totalConfiguredSeats || 0), 0)
  );

  ngOnInit(): void {
    this.loadDashboardData();
  }

  loadDashboardData(): void {
    this.isLoading.set(true);

    this.venueApi.getVenues({ size: 10 }).subscribe({
      next: (res) => {
        this.venues.set(res.content || []);
        this.totalVenues.set(res.totalElements || res.content?.length || 0);
      },
      error: () => {
        this.venues.set([]);
      },
    });

    this.userApi.getUsers({ size: 5, sort: 'createdAt', direction: 'desc' }).subscribe({
      next: (res) => {
        this.users.set(res.content || []);
        this.totalUsers.set(res.totalElements || res.content?.length || 0);
        this.isLoading.set(false);
      },
      error: () => {
        this.users.set([]);
        this.isLoading.set(false);
      },
    });
  }
}
