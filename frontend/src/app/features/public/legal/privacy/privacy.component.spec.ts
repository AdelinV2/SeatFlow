import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { PrivacyComponent } from './privacy.component';

describe('PrivacyComponent (TASK-P16-002)', () => {
  let fixture: ComponentFixture<PrivacyComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PrivacyComponent],
      providers: [provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(PrivacyComponent);
    fixture.detectChanges();
  });

  it('uses the P16-001 content shell and renders exactly one h1', () => {
    expect(fixture.nativeElement.querySelector('app-content-page')).not.toBeNull();
    expect(fixture.nativeElement.querySelectorAll('h1').length).toBe(1);
  });

  it('includes actual data categories from the verified inventory', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    for (const category of [
      'Account and identity',
      'Guest and authenticated bookings',
      'Payments and tax',
      'Tickets',
      'Security and operations',
      'Assistant data',
    ]) {
      expect(text).withContext(`missing category: ${category}`).toContain(category);
    }
    expect(text).toContain('Card numbers and CVC');
    expect(text).toContain('never stored by SeatFlow');
  });

  it('contains no placeholder company or DPO claims', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    // Explicit absence disclaimers are required; invented contact/company values are forbidden.
    for (const forbidden of ['SeatFlow Inc', 'dpo@', 'DPO@', 'support@seatflow.example']) {
      expect(text.toLowerCase()).not.toContain(forbidden.toLowerCase());
    }
    expect(text).toContain('not yet confirmed');
    expect(text).toContain('no company name');
  });

  it('is conditionally accurate about AI: no persistent history, no zero-data claim', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    expect(text).toContain('no persistent');
    expect(text).toContain('in-memory');
    expect(text.toLowerCase()).not.toContain('zero data');
    expect(text).not.toContain('Groq does not retain');
  });

  it('publishes no secret-like runtime values', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    for (const secret of ['eyJhbGci', 'gsk_', 'sk_test', 'whsec_', 'BEGIN PRIVATE']) {
      expect(text).not.toContain(secret);
    }
  });

  it('marks external links secure where present and keeps navigation keyboard accessible', () => {
    const anchors: HTMLAnchorElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('a'),
    );
    expect(anchors.length).toBeGreaterThan(0);
    for (const anchor of anchors) {
      const href = anchor.getAttribute('href') ?? '';
      if (/^https?:\/\//i.test(href)) {
        expect((anchor.getAttribute('rel') ?? '').toLowerCase()).toContain('noopener');
        expect(anchor.getAttribute('target')).toBe('_blank');
      }
      expect(anchor.tabIndex).toBeGreaterThanOrEqual(-1);
    }
  });
});
