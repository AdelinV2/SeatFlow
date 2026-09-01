import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SystemHealthService } from './system-health.service';

describe('SystemHealthService', () => {
  let service: SystemHealthService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        SystemHealthService,
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });

    service = TestBed.inject(SystemHealthService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  function flushInitialRequests(
    actuatorStatus: number = 200,
    venuesStatus: number = 200,
    eventsStatus: number = 200
  ): void {
    const actuatorReq = httpMock.match('/actuator/health');
    actuatorReq.forEach((r) =>
      actuatorStatus === 200
        ? r.flush({ status: 'UP' })
        : r.error(new ProgressEvent('error'), { status: actuatorStatus })
    );

    const venuesReq = httpMock.match('/api/venues?size=1');
    venuesReq.forEach((r) =>
      venuesStatus === 200
        ? r.flush({ content: [] })
        : r.error(new ProgressEvent('error'), { status: venuesStatus })
    );

    const eventsReq = httpMock.match('/api/events?size=1');
    eventsReq.forEach((r) =>
      eventsStatus === 200
        ? r.flush({ content: [] })
        : r.error(new ProgressEvent('error'), { status: eventsStatus })
    );
  }

  it('should be created and have operational default state', () => {
    flushInitialRequests();
    expect(service).toBeTruthy();
    expect(service.status()).toBe('OPERATIONAL');
    expect(service.statusLabel()).toBe('All Systems Operational');
  });

  it('should transition to DEGRADED and DOWN appropriately', () => {
    flushInitialRequests();

    service.setStatus('DEGRADED');
    expect(service.statusLabel()).toBe('Some Systems are Down');
    expect(service.statusColor()).toContain('amber');

    service.setStatus('DOWN');
    expect(service.statusLabel()).toBe('Systems Down / Offline');
    expect(service.statusColor()).toContain('rose');

    service.setStatus('OPERATIONAL');
    expect(service.statusLabel()).toBe('All Systems Operational');
    expect(service.statusColor()).toContain('emerald');
  });

  it('should determine status based on service availability responses', () => {
    // Check when services are all up
    service.checkHealth();
    flushInitialRequests(200, 200, 200);
    expect(service.status()).toBe('OPERATIONAL');

    // Check when one service fails -> DEGRADED
    service.checkHealth();
    flushInitialRequests(200, 500, 200);
    expect(service.status()).toBe('DEGRADED');

    // Check when all services fail -> DOWN
    service.checkHealth();
    flushInitialRequests(503, 503, 503);
    expect(service.status()).toBe('DOWN');
  });
});
