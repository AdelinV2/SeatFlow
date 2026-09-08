import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { SecurityComponent } from './security.component';

describe('SecurityComponent (TASK-P16-002)', () => {
  let fixture: ComponentFixture<SecurityComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SecurityComponent],
      providers: [provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(SecurityComponent);
    fixture.detectChanges();
  });

  it('uses the P16-001 content shell and renders exactly one h1', () => {
    expect(fixture.nativeElement.querySelector('app-content-page')).not.toBeNull();
    expect(fixture.nativeElement.querySelectorAll('h1').length).toBe(1);
  });

  it('makes only verifiable high-level claims', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    for (const claim of [
      'Supabase',
      'role checks on the server',
      '10 seats',
      '15 minutes',
      'Stripe Elements',
      'rate limiting',
      'allow-list',
    ]) {
      expect(text).withContext(`missing verifiable claim: ${claim}`).toContain(claim);
    }
  });

  it('makes no certification, residency, SLA, or encryption claim', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    // The page must disclaim these topics; it must never assert them positively.
    expect(text).toContain('claims no certification');
    for (const forbidden of [
      'is certified',
      'are certified',
      'certified by',
      'compliant and certified',
      'guaranteed uptime',
      'we guarantee 99.9',
      'data stays only',
      'we use end-to-end encryption',
    ]) {
      expect(text).not.toContain(forbidden);
    }
  });

  it('leaks no sensitive internals', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    for (const secret of [
      'eyJhbGci',
      'gsk_',
      'sk_test',
      'whsec_',
      'BEGIN PRIVATE',
      'actuator',
      'txyyirobwnomhxygbacq',
    ]) {
      expect(text).not.toContain(secret);
    }
  });
});
