import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ContactComponent } from './contact.component';

describe('ContactComponent (TASK-P16-004)', () => {
  let fixture: ComponentFixture<ContactComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ContactComponent],
      providers: [provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(ContactComponent);
    fixture.detectChanges();
  });

  it('uses the P16-001 content shell and renders exactly one h1', () => {
    expect(fixture.nativeElement.querySelector('app-content-page')).not.toBeNull();
    expect(fixture.nativeElement.querySelectorAll('h1').length).toBe(1);
  });

  it('renders no form backend and no fake success state', () => {
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
    const text: string = (fixture.nativeElement.textContent ?? '').toLowerCase();
    expect(text).not.toContain('message sent successfully');
    expect(text).not.toContain('we have received your message');
    expect(text).not.toContain('thank you for contacting');
  });

  it('distinguishes demo feedback, organizer limits, privacy, and security paths', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    expect(text).toContain('Portfolio and demo feedback');
    expect(text).toContain('does not operate a verified organizer-support directory');
    expect(text).toContain('no data protection officer');
    expect(text).toContain('suspected vulnerability');
  });

  it('shows the owner configuration gate instead of a hardcoded personal email', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    expect(text).toContain('Owner configuration required before production use');
    expect(text).toContain('SUPPORT_EMAIL');
    expect(text).toContain('env.example.js');
    // No invented personal mailbox is baked into the page.
    expect(text).not.toMatch(/[a-z0-9._%+-]+@gmail\.com/i);
    expect(text).not.toMatch(/[a-z0-9._%+-]+@yahoo\.com/i);
  });

  it('makes demo support limits explicit with no invented SLA or desk claim', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    expect(text).toContain('no staffed support desk');
    expect(text).toContain('no response-time promise');
    const lowered = text.toLowerCase();
    for (const forbidden of [
      '24/7',
      'response within 24 hours',
      'we will respond within',
      'guaranteed response',
      'support ticket queue',
      'contact event organizers',
      'organizer directory',
    ]) {
      expect(lowered).withContext(`forbidden claim: ${forbidden}`).not.toContain(forbidden);
    }
  });

  it('warns against sending secrets and links to privacy context', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    expect(text).toContain('Do not include passwords');
    const raw: string = fixture.nativeElement.innerHTML as string;
    for (const path of ['/legal/privacy', '/legal/cookies', '/legal/security', '/legal/refunds', '/support/faq', '/status']) {
      expect(raw).withContext(`links to ${path}`).toContain(path);
    }
  });
});
