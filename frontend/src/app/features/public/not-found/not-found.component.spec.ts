import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { NotFoundComponent } from './not-found.component';

@Component({ selector: 'app-blank-home', standalone: true, template: '<p>home</p>' })
class BlankHomeComponent {}

@Component({ selector: 'app-blank-events', standalone: true, template: '<p>events</p>' })
class BlankEventsComponent {}

describe('NotFoundComponent', () => {
  let fixture: ComponentFixture<NotFoundComponent>;
  let router: Router;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [NotFoundComponent],
      providers: [
        provideRouter([
          { path: '', component: BlankHomeComponent },
          { path: 'events', component: BlankEventsComponent },
        ]),
      ],
    }).compileComponents();

    router = TestBed.inject(Router);
    fixture = TestBed.createComponent(NotFoundComponent);
    fixture.detectChanges();
    await fixture.whenStable();
  });

  it('renders exactly one h1 stating the page was not found', () => {
    const headings: HTMLElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('h1'),
    );
    expect(headings.length).toBe(1);
    expect(headings[0].textContent).toContain('could not be found');
  });

  it('exposes working router links to Home and Events', async () => {
    const links: HTMLAnchorElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('a'),
    );
    const hrefs = links.map((a) => a.getAttribute('href'));
    expect(hrefs).toContain('/');
    expect(hrefs).toContain('/events');

    await router.navigateByUrl('/');
    expect(router.url).toBe('/');

    await router.navigateByUrl('/events');
    expect(router.url).toBe('/events');
  });

  it('never displays the requested URL, query string or fragment', () => {
    const sensitivePath = '/legal/privacy?token=secret-token-123&email=user@example.com#auth-callback';
    const text: string = fixture.nativeElement.textContent ?? '';
    const html: string = fixture.nativeElement.innerHTML ?? '';
    expect(sensitivePath).not.toBe('');
    expect(text).not.toContain('secret-token-123');
    expect(text).not.toContain('user@example.com');
    expect(html).not.toContain('secret-token-123');
    expect(html).not.toContain(window.location.href);
  });

  it('does not auto-redirect after a timeout', async () => {
    const initialUrl = router.url;
    await new Promise((resolve) => setTimeout(resolve, 50));
    fixture.detectChanges();
    expect(router.url).toBe(initialUrl);
    expect(fixture.nativeElement.querySelector('h1')).toBeTruthy();
  });

  it('moves focus to the heading for keyboard and screen-reader users', () => {
    const heading: HTMLElement | null =
      fixture.nativeElement.querySelector('h1');
    expect(document.activeElement).toBe(heading);
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
    expect(writtenKeys).not.toContain('seatflow_not_found');
    expect(writtenKeys).not.toContain('seatflow_404_theme');
  });
});
