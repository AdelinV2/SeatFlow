import {
  Injectable,
  inject,
  signal,
  computed,
  PLATFORM_ID,
  DestroyRef,
} from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { isPlatformBrowser } from '@angular/common';
import { catchError, of, forkJoin } from 'rxjs';

export type SystemHealthStatus = 'OPERATIONAL' | 'DEGRADED' | 'DOWN' | 'CHECKING';

export interface ServiceHealthReport {
  gateway: 'UP' | 'DOWN';
  venues: 'UP' | 'DOWN';
  events: 'UP' | 'DOWN';
  users: 'UP' | 'DOWN';
}

@Injectable({ providedIn: 'root' })
export class SystemHealthService {
  private readonly http = inject(HttpClient);
  private readonly platformId = inject(PLATFORM_ID);
  private readonly destroyRef = inject(DestroyRef);

  readonly status = signal<SystemHealthStatus>('OPERATIONAL');
  readonly serviceReport = signal<ServiceHealthReport>({
    gateway: 'UP',
    venues: 'UP',
    events: 'UP',
    users: 'UP',
  });
  readonly lastChecked = signal<Date>(new Date());

  readonly statusLabel = computed(() => {
    switch (this.status()) {
      case 'OPERATIONAL':
        return 'All Systems Operational';
      case 'DEGRADED':
        return 'Some Systems are Down';
      case 'DOWN':
        return 'Systems Down / Offline';
      case 'CHECKING':
        return 'Checking System Health...';
    }
  });

  readonly statusColor = computed(() => {
    switch (this.status()) {
      case 'OPERATIONAL':
        return 'text-emerald-500';
      case 'DEGRADED':
        return 'text-amber-500';
      case 'DOWN':
        return 'text-rose-500';
      case 'CHECKING':
        return 'text-indigo-400';
    }
  });

  readonly statusBgColor = computed(() => {
    switch (this.status()) {
      case 'OPERATIONAL':
        return 'bg-emerald-500';
      case 'DEGRADED':
        return 'bg-amber-500';
      case 'DOWN':
        return 'bg-rose-500';
      case 'CHECKING':
        return 'bg-indigo-400';
    }
  });

  readonly isOperational = computed(() => this.status() === 'OPERATIONAL');

  private checkTimer?: ReturnType<typeof setInterval>;

  constructor() {
    if (isPlatformBrowser(this.platformId)) {
      this.checkHealth();

      this.checkTimer = setInterval(() => this.checkHealth(), 45000);

      window.addEventListener('offline', () => {
        this.status.set('DOWN');
      });

      window.addEventListener('online', () => {
        this.checkHealth();
      });

      this.destroyRef.onDestroy(() => {
        if (this.checkTimer) clearInterval(this.checkTimer);
      });
    }
  }

  checkHealth(): void {
    const actuator$ = this.http.get<{ status: string }>('/actuator/health').pipe(
      catchError(() => of(null))
    );
    const venues$ = this.http.get('/api/venues?size=1').pipe(
      catchError(() => of(null))
    );
    const events$ = this.http.get('/api/events?size=1').pipe(
      catchError(() => of(null))
    );

    forkJoin({
      actuator: actuator$,
      venues: venues$,
      events: events$,
    }).subscribe({
      next: ({ actuator, venues, events }) => {
        this.lastChecked.set(new Date());

        const gatewayUp = actuator !== null || venues !== null || events !== null;
        const venuesUp = venues !== null;
        const eventsUp = events !== null;

        this.serviceReport.set({
          gateway: gatewayUp ? 'UP' : 'DOWN',
          venues: venuesUp ? 'UP' : 'DOWN',
          events: eventsUp ? 'UP' : 'DOWN',
          users: gatewayUp ? 'UP' : 'DOWN',
        });

        if (!gatewayUp && !venuesUp && !eventsUp) {
          this.status.set('DOWN');
        } else if (!venuesUp || !eventsUp) {
          this.status.set('DEGRADED');
        } else {
          this.status.set('OPERATIONAL');
        }
      },
      error: () => {
        this.status.set('DOWN');
      },
    });
  }

  setStatus(status: SystemHealthStatus): void {
    this.status.set(status);
  }
}
