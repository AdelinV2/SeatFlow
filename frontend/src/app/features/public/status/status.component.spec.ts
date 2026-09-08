import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { StatusComponent } from './status.component';
import { SystemHealthService } from '../../../services/system-health.service';

describe('StatusComponent (TASK-P16-004)', () => {
  let fixture: ComponentFixture<StatusComponent>;
  let health: SystemHealthService;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [StatusComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    fixture = TestBed.createComponent(StatusComponent);
    health = TestBed.inject(SystemHealthService);
    fixture.detectChanges();
  });

  it('uses the P16-001 content shell and renders exactly one h1', () => {
    expect(fixture.nativeElement.querySelector('app-content-page')).not.toBeNull();
    expect(fixture.nativeElement.querySelectorAll('h1').length).toBe(1);
  });

  it('handles CHECKING, OPERATIONAL, DEGRADED, and DOWN with text labels', () => {
    const states = [
      ['CHECKING', 'Checking System Health'],
      ['OPERATIONAL', 'All Systems Operational'],
      ['DEGRADED', 'Some Systems are Down'],
      ['DOWN', 'Systems Down'],
    ] as const;
    for (const [state, label] of states) {
      health.setStatus(state);
      fixture.detectChanges();
      const text: string = fixture.nativeElement.textContent ?? '';
      expect(text).withContext(`missing label for ${state}`).toContain(label);
      expect(text).withContext(`missing code for ${state}`).toContain(state);
    }
  });

  it('renders sanitized capabilities with a retry action and contact link', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    expect(text).toContain('Event browsing');
    expect(text).toContain('Booking gateway');
    expect(text).toContain('Last checked');
    expect(fixture.nativeElement.querySelector('button')).not.toBeNull();
    const raw: string = fixture.nativeElement.innerHTML as string;
    expect(raw).toContain('/support/contact');
    expect(raw).toContain('/support/faq');
  });

  it('never serializes raw health responses or infrastructure details', () => {
    health.setStatus('OPERATIONAL');
    fixture.detectChanges();
    const raw: string = fixture.nativeElement.innerHTML as string;
    const text: string = fixture.nativeElement.textContent ?? '';
    for (const forbidden of [
      'actuator',
      '/actuator/health',
      'txyyirobwnomhxygbacq',
      'supabase.co',
      'redis',
      'kafka',
      'eureka',
      'localhost:',
      '8080',
      'eyJhbGci',
      'sk_test',
      'whsec_',
      '{"status"',
    ]) {
      expect(raw).withContext(`leaked detail: ${forbidden}`).not.toContain(forbidden);
      expect(text).withContext(`leaked detail: ${forbidden}`).not.toContain(forbidden);
    }
  });

  it('disclaims SLA semantics with no incident history or uptime promise', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    // The page must explicitly disclaim SLA-style promises.
    expect(text).toContain('no uptime promise');
    expect(text).toContain('no incident history');
    expect(text).toContain('no maintenance schedule');
    const lowered = text.toLowerCase();
    // ...while never making a positive availability/certification promise.
    for (const forbidden of [
      '99.9%',
      'we guarantee',
      'guaranteed uptime',
      'guaranteed response',
      'certified',
      'sla commitment',
    ]) {
      expect(lowered).withContext(`forbidden claim: ${forbidden}`).not.toContain(forbidden);
    }
  });
});
