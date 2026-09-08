import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ApiDocsComponent } from './api-docs.component';

describe('ApiDocsComponent (TASK-P16-004)', () => {
  let fixture: ComponentFixture<ApiDocsComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ApiDocsComponent],
      providers: [provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(ApiDocsComponent);
    fixture.detectChanges();
  });

  it('uses the P16-001 content shell and renders exactly one h1', () => {
    expect(fixture.nativeElement.querySelector('app-content-page')).not.toBeNull();
    expect(fixture.nativeElement.querySelectorAll('h1').length).toBe(1);
  });

  it('covers API domains, auth, invariants, live-docs boundary, and protected surfaces', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    for (const topic of [
      'events and event sessions',
      'reservations',
      'payments',
      'tickets',
      'token-based sign-in',
      '10 seats',
      '15 minutes',
      'No live interactive documentation',
      'remain protected',
    ]) {
      expect(text).withContext(`missing API docs topic: ${topic}`).toContain(topic);
    }
  });

  it('exposes no internal hostnames, ports, discovery URLs, or actuator inventory', () => {
    const raw: string = fixture.nativeElement.innerHTML as string;
    const text: string = fixture.nativeElement.textContent ?? '';
    for (const forbidden of [
      'localhost:',
      '127.0.0.1',
      'host.docker.internal',
      'api-gateway:8080',
      'event-service:',
      '8761',
      ':8081',
      ':8082',
      ':8083',
      'eureka',
      'actuator',
      '/swagger',
      'swagger-ui',
      '/v3/api-docs',
      'discovery',
    ]) {
      expect(raw).withContext(`leaked endpoint: ${forbidden}`).not.toContain(forbidden);
      expect(text).withContext(`leaked endpoint: ${forbidden}`).not.toContain(forbidden);
    }
    // Relative gateway paths are allowed; absolute internal URLs are not.
    expect(text).toContain('/api/events');
  });

  it('publishes no tokens, secrets, or bypass instructions', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    const lowered = text.toLowerCase();
    // The page must disclaim bypass guidance ("no guidance for getting around
    // those checks"); it must never give positive bypass instructions.
    expect(lowered).toContain('no guidance for getting around');
    for (const forbidden of [
      'eyJhbGci',
      'bearer ',
      'gsk_',
      'sk_test',
      'whsec_',
      'clientsecret',
      'client_secret',
      'to bypass',
      'disable the guard',
      'skip authorization',
    ]) {
      expect(lowered).withContext(`forbidden value: ${forbidden}`).not.toContain(forbidden);
    }
  });

  it('links to Security, Terms, Refunds, FAQ, and Contact without external leakage', () => {
    const raw: string = fixture.nativeElement.innerHTML as string;
    for (const path of ['/legal/security', '/legal/terms', '/legal/refunds', '/support/faq', '/support/contact']) {
      expect(raw).withContext(`links to ${path}`).toContain(path);
    }
    const anchors: HTMLAnchorElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('a[href]'),
    );
    for (const anchor of anchors) {
      const href = anchor.getAttribute('href') ?? '';
      if (/^https?:\/\//i.test(href)) {
        expect(anchor.getAttribute('rel') ?? '').toContain('noopener');
      }
    }
  });
});
