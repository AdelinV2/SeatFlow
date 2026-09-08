import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import {
  ContentPageComponent,
  slugifyContentAnchor,
  toUniqueTocEntries,
} from './content-page.component';

@Component({
  standalone: true,
  imports: [ContentPageComponent],
  template: `
    <app-content-page
      eyebrow="Legal"
      title="Privacy Notice"
      lead="How SeatFlow handles your data."
      lastUpdated="2026-09-08"
      [toc]="toc"
      calloutTitle=""
      calloutMessage="Demo deployment."
      calloutTone="warning"
    >
      <section id="overview"><h2>Overview</h2></section>
      <section id="overview-2"><h2>Details</h2></section>
    </app-content-page>
  `,
})
class TestHostComponent {
  toc = [
    { id: 'Overview', label: 'Overview' },
    { id: 'overview', label: 'Overview again' },
    { id: 'contact', label: 'Contact' },
  ];
}

describe('ContentPageComponent', () => {
  let fixture: ComponentFixture<TestHostComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [TestHostComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(TestHostComponent);
    fixture.detectChanges();
  });

  it('renders exactly one h1 with the supplied title', () => {
    const headings: HTMLElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('h1'),
    );
    expect(headings.length).toBe(1);
    expect(headings[0].textContent).toContain('Privacy Notice');
  });

  it('renders eyebrow, lead and last-updated text', () => {
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('Legal');
    expect(text).toContain('How SeatFlow handles your data.');
    expect(text).toContain('2026-09-08');
  });

  it('renders projected body sections', () => {
    expect(fixture.nativeElement.querySelector('#overview')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('#overview-2')).toBeTruthy();
  });

  it('deduplicates TOC anchor ids so every href is unique', () => {
    const links: HTMLAnchorElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('.content-page__toc-link'),
    );
    const hrefs = links.map((a) => a.getAttribute('href'));
    expect(links.length).toBe(3);
    expect(new Set(hrefs).size).toBe(3);
    expect(hrefs).toEqual(['#overview', '#overview-2', '#contact']);
  });

  it('exposes keyboard-reachable TOC links with meaningful text', () => {
    const links: HTMLAnchorElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('.content-page__toc-link'),
    );
    for (const link of links) {
      expect((link.textContent ?? '').trim().length).toBeGreaterThan(0);
      expect(link.tabIndex).toBeGreaterThanOrEqual(0);
    }
  });

  it('renders a labelled callout that does not rely on color alone', () => {
    const callout: HTMLElement | null =
      fixture.nativeElement.querySelector('[role="note"]');
    expect(callout).toBeTruthy();
    expect(callout?.textContent).toContain('Please note');
    expect(callout?.textContent).toContain('Demo deployment.');
  });

  it('renders without any authenticated-user providers', () => {
    // The host above provides no AuthService/UserContext. Successful creation
    // proves the shell has no signed-in dependency.
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('renders under both light and dark theme host classes without new storage keys', () => {
    const setItemSpy = spyOn(Storage.prototype, 'setItem');
    for (const theme of ['light', 'dark']) {
      document.documentElement.classList.remove('light', 'dark');
      document.documentElement.classList.add(theme);
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('h1')).toBeTruthy();
    }
    document.documentElement.classList.remove('light', 'dark');
    const writtenKeys = setItemSpy.calls
      .allArgs()
      .map((args) => String(args[0]));
    expect(writtenKeys).not.toContain('seatflow_content_page');
    expect(writtenKeys).not.toContain('seatflow_legal_theme');
  });

  it('uses semantic landmarks for the TOC', () => {
    expect(
      fixture.nativeElement.querySelector('nav[aria-label="On this page"]'),
    ).toBeTruthy();
  });
});

describe('toUniqueTocEntries', () => {
  it('suffixes repeated slugs instead of emitting duplicate ids', () => {
    const result = toUniqueTocEntries([
      { id: 'Privacy', label: 'Privacy' },
      { id: 'privacy', label: 'Privacy again' },
      { id: 'privacy', label: 'Privacy a third time' },
    ]);
    expect(result.map((e) => e.id)).toEqual([
      'privacy',
      'privacy-2',
      'privacy-3',
    ]);
  });

  it('falls back to a non-empty slug for symbol-only ids', () => {
    const result = toUniqueTocEntries([{ id: '!!!', label: '!!!' }]);
    expect(result[0].id.length).toBeGreaterThan(0);
  });
});

describe('slugifyContentAnchor', () => {
  it('lowercases, trims and hyphenates labels', () => {
    expect(slugifyContentAnchor('  Data Retention & Rights ')).toBe(
      'data-retention-rights',
    );
  });
});
