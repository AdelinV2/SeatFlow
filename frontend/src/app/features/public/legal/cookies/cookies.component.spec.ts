import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { CookiesComponent } from './cookies.component';

describe('CookiesComponent (TASK-P16-002)', () => {
  let fixture: ComponentFixture<CookiesComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CookiesComponent],
      providers: [provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(CookiesComponent);
    fixture.detectChanges();
  });

  it('uses the P16-001 content shell and renders exactly one h1', () => {
    expect(fixture.nativeElement.querySelector('app-content-page')).not.toBeNull();
    expect(fixture.nativeElement.querySelectorAll('h1').length).toBe(1);
  });

  it('covers cookies and equivalent terminal storage, not cookies alone', () => {
    const text: string = (fixture.nativeElement.textContent ?? '').toLowerCase();
    expect(text).toContain('localstorage');
    expect(text).toContain('sessionstorage');
    expect(text).toContain('sdk');
  });

  it('documents the theme preference and guest reservation storage behavior', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    expect(text).toContain('seatflow_theme_mode');
    expect(text).toContain('seatflow:reservation-email:');
    expect(text).toContain('Supabase');
    expect(text).toContain('Stripe');
  });

  it('records NOT_REQUIRED_FOR_CURRENT_RUNTIME and ships no fake consent banner', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    expect(text).toContain('NOT_REQUIRED_FOR_CURRENT_RUNTIME');
    expect(
      fixture.nativeElement.querySelector(
        'app-cookie-banner, .cookie-banner, [data-testid="cookie-banner"]',
      ),
    ).toBeNull();
  });

  it('represents auth storage accurately without exposing token values', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    expect(text).toContain('auth-token');
    for (const secret of ['eyJhbGci', 'Bearer ', 'gsk_', 'sk_test', 'whsec_']) {
      expect(text).not.toContain(secret);
    }
  });
});
