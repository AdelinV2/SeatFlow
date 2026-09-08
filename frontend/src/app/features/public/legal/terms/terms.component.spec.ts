import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TermsComponent } from './terms.component';

describe('TermsComponent (TASK-P16-002)', () => {
  let fixture: ComponentFixture<TermsComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [TermsComponent],
      providers: [provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(TermsComponent);
    fixture.detectChanges();
  });

  it('uses the P16-001 content shell and renders exactly one h1', () => {
    const shell = fixture.nativeElement.querySelector('app-content-page');
    expect(shell).not.toBeNull();
    expect(fixture.nativeElement.querySelectorAll('h1').length).toBe(1);
  });

  it('matches demo behavior: holds, server authority, test mode, refunds by reference', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    expect(text).toContain('10 seats');
    expect(text).toContain('15 minutes');
    expect(text).toContain('server');
    expect(text).toContain('Stripe Test Mode');
    expect(text).toContain('refund and cancellation policy');
  });

  it('publishes no invented company, SLA, certification, or jurisdiction claim', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    // Positive invented claims must be absent; explicit "not claimed" disclaimers are required.
    for (const forbidden of [
      'SeatFlow Inc',
      '99.9%',
      'is certified',
      'are certified',
      'certified by',
      'guaranteed uptime',
    ]) {
      expect(text).not.toContain(forbidden);
    }
    expect(text).toContain('not confirmed');
    expect(text).toContain('No uptime');
  });

  it('publishes no secret-like runtime values', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    for (const secret of ['eyJhbGci', 'gsk_', 'sk_test', 'whsec_', 'BEGIN PRIVATE']) {
      expect(text).not.toContain(secret);
    }
  });
});
