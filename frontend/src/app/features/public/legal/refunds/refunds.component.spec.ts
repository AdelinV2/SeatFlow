import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RefundsComponent } from './refunds.component';

describe('RefundsComponent (TASK-P16-003)', () => {
  let fixture: ComponentFixture<RefundsComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RefundsComponent],
      providers: [provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(RefundsComponent);
    fixture.detectChanges();
  });

  it('uses the P16-001 content shell and renders exactly one h1', () => {
    expect(fixture.nativeElement.querySelector('app-content-page')).not.toBeNull();
    expect(fixture.nativeElement.querySelectorAll('h1').length).toBe(1);
  });

  it('states the exact 24 hours rule with full-reservation scope', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    expect(text).toContain('at least 24 hours remain');
    expect(text).toContain('Exactly 24 hours (24:00:00)');
    expect(text).toContain('below 24 hours is not eligible');
    expect(text).toContain('full reservation refund');
  });

  it('does not advertise partial or per-ticket refunds', () => {
    const text: string = (fixture.nativeElement.textContent ?? '').toLowerCase();
    expect(text).toContain('no partial refund');
    expect(text).toContain('no per-ticket refund');
    expect(text).not.toContain('partially refund');
  });

  it('never describes a submitted request as completed money movement', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    expect(text).toContain('request submitted');
    expect(text).toContain('processing');
    expect(text).toContain('completed or failed');
    expect(text).toContain('never counts as completed just because a request was submitted');
  });

  it('directs guests to support/contact instead of a fake self-service action', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    expect(text).toContain('no guest refund self-service button');
    const links: HTMLAnchorElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('a[href]'),
    );
    const hrefs = links.map((a) => a.getAttribute('href') ?? a.getAttribute('ng-reflect-router-link') ?? '');
    const raw = fixture.nativeElement.innerHTML as string;
    expect(raw).toContain('/support/contact');
    expect(hrefs.length).toBeGreaterThan(0);
  });

  it('discloses the portfolio demo and Stripe Test Mode with no real transfer', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    expect(text).toContain('Stripe Test Mode');
    expect(text).toContain('portfolio/demo deployment');
    expect(text).toContain('move no real money');
  });

  it('publishes no secret-like runtime values', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    for (const secret of ['eyJhbGci', 'gsk_', 'sk_test', 'whsec_', 'BEGIN PRIVATE', 'pi_']) {
      expect(text).not.toContain(secret);
    }
  });

  it('links to Terms, Privacy, Tax, Support, and Events', () => {
    const raw: string = fixture.nativeElement.innerHTML as string;
    for (const path of ['/legal/terms', '/legal/privacy', '/legal/tax', '/support/contact', '/events']) {
      expect(raw).withContext(`links to ${path}`).toContain(path);
    }
  });
});
