import { TestBed, ComponentFixture } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { FooterComponent } from './footer.component';
import { SystemHealthService } from '../../../services/system-health.service';

describe('FooterComponent', () => {
  let fixture: ComponentFixture<FooterComponent>;
  let component: FooterComponent;
  let healthService: SystemHealthService;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [FooterComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        SystemHealthService,
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(FooterComponent);
    component = fixture.componentInstance;
    healthService = TestBed.inject(SystemHealthService);
  });

  it('renders green indicator and "All Systems Operational" when operational', () => {
    healthService.setStatus('OPERATIONAL');
    fixture.detectChanges();

    const indicator = fixture.nativeElement.querySelector('.status-indicator');
    const container = indicator?.parentElement;

    expect(container?.textContent).toContain('All Systems Operational');
    expect(container?.classList.contains('text-emerald-500')).toBeTrue();
    expect(indicator?.classList.contains('bg-emerald-500')).toBeTrue();
  });

  it('renders yellow/amber indicator and "Some Systems are Down" when degraded', () => {
    healthService.setStatus('DEGRADED');
    fixture.detectChanges();

    const indicator = fixture.nativeElement.querySelector('.status-indicator');
    const container = indicator?.parentElement;

    expect(container?.textContent).toContain('Some Systems are Down');
    expect(container?.classList.contains('text-amber-500')).toBeTrue();
    expect(indicator?.classList.contains('bg-amber-500')).toBeTrue();
  });

  it('renders red/rose indicator and "Systems Down / Offline" when all systems are down', () => {
    healthService.setStatus('DOWN');
    fixture.detectChanges();

    const indicator = fixture.nativeElement.querySelector('.status-indicator');
    const container = indicator?.parentElement;

    expect(container?.textContent).toContain('Systems Down / Offline');
    expect(container?.classList.contains('text-rose-500')).toBeTrue();
    expect(indicator?.classList.contains('bg-rose-500')).toBeTrue();
  });
});
