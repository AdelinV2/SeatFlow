import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { FaqComponent } from './faq.component';

describe('FaqComponent (TASK-P16-004)', () => {
  let fixture: ComponentFixture<FaqComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [FaqComponent],
      providers: [provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(FaqComponent);
    fixture.detectChanges();
  });

  it('uses the P16-001 content shell and renders exactly one h1', () => {
    expect(fixture.nativeElement.querySelector('app-content-page')).not.toBeNull();
    expect(fixture.nativeElement.querySelectorAll('h1').length).toBe(1);
  });

  it('covers the required FAQ topics without drifting into a second refund policy', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    for (const topic of [
      '15 minutes',
      '10 seats',
      'guest',
      'QR',
      'Stripe Test Mode',
      'tax preview',
      'refund',
      'password reset',
      'staff scanner',
      'AI assistant',
      'privacy',
      'demo issue',
    ]) {
      expect(text).withContext(`missing FAQ topic: ${topic}`).toContain(topic);
    }
    // Refund is summarized and linked, not restated as an independent rule.
    expect(text).toContain('at least 24 hours remain');
    expect(text).not.toContain('23:59:59');
  });

  it('links to authoritative legal, refund, tax, security, and contact pages', () => {
    const raw: string = fixture.nativeElement.innerHTML as string;
    for (const path of [
      '/legal/terms',
      '/legal/privacy',
      '/legal/cookies',
      '/legal/refunds',
      '/legal/tax',
      '/legal/security',
      '/support/contact',
    ]) {
      expect(raw).withContext(`links to ${path}`).toContain(path);
    }
  });

  it('describes the AI assistant as optional with no autonomous payment or durable history', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    expect(text).toContain('assistive');
    expect(text).toContain('cannot create');
    expect(text).toContain('in memory only');
    expect(text).not.toContain('autonomous payment');
  });

  it('makes no invented support, SLA, certification, or organizer claim', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    // The demo-issue answer must disclaim support limits explicitly.
    expect(text).toContain('no staffed support desk');
    expect(text).toContain('no response-time promise');
    const lowered = text.toLowerCase();
    // ...while never making a positive support/SLA/certification promise.
    for (const forbidden of [
      '24/7',
      'response within',
      'we will respond within',
      'guaranteed response',
      'service-level commitment',
      'sla commitment',
      'guarantee',
      'certified',
      'compliant',
      '99.9%',
      'contact event organizers',
      'organizer directory',
      'cookie preferences',
    ]) {
      expect(lowered).withContext(`forbidden claim: ${forbidden}`).not.toContain(forbidden);
    }
  });

  it('uses accessible accordion buttons with expanded state', () => {
    const buttons: HTMLButtonElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('button.faq-question'),
    );
    expect(buttons.length).toBeGreaterThan(5);
    for (const button of buttons) {
      expect(button.getAttribute('aria-expanded')).toMatch(/true|false/);
      const panelId = button.getAttribute('aria-controls') ?? '';
      expect(panelId).withContext('accordion controls a panel').toContain('faq-panel-');
      expect(fixture.nativeElement.querySelector(`#${CSS.escape(panelId)}`)).not.toBeNull();
    }
    // Toggling updates expanded state without throwing.
    const first = buttons[0];
    const before = first.getAttribute('aria-expanded');
    first.click();
    fixture.detectChanges();
    expect(first.getAttribute('aria-expanded')).not.toBe(before);
  });

  it('publishes no secret-like runtime values', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    for (const secret of ['eyJhbGci', 'gsk_', 'sk_test', 'whsec_', 'BEGIN PRIVATE', 'pi_']) {
      expect(text).not.toContain(secret);
    }
  });
});
