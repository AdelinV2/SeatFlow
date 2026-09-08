import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Meta, Title } from '@angular/platform-browser';
import { provideRouter, Router, type Routes } from '@angular/router';
import { PublicPageMetaService } from './public-page-meta.service';

@Component({ selector: 'app-meta-dummy', standalone: true, template: '' })
class DummyComponent {}

/**
 * TASK-P16-005 metadata contract: descriptions and robots directives follow
 * static route configuration on navigation, titles stay static, and no route
 * parameter (ticket code, reservation id, auth value) ever enters metadata.
 */
describe('PublicPageMetaService', () => {
  const testRoutes: Routes = [
    {
      path: 'legal/cookies',
      title: 'Cookies & Storage — SeatFlow',
      data: { description: 'Cookies disclosure for tests.' },
      component: DummyComponent,
    },
    {
      path: 'gone',
      title: 'Page Not Found — SeatFlow',
      data: { noindex: true },
      component: DummyComponent,
    },
    {
      path: 'tickets/guest/:ticketCode',
      title: 'Guest Ticket — SeatFlow',
      component: DummyComponent,
    },
  ];

  let router: Router;
  let meta: Meta;
  let title: Title;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      providers: [provideRouter(testRoutes), PublicPageMetaService],
    }).compileComponents();

    router = TestBed.inject(Router);
    meta = TestBed.inject(Meta);
    title = TestBed.inject(Title);
    TestBed.inject(PublicPageMetaService).start();
  });

  it('applies the route description and title on navigation', async () => {
    await router.navigateByUrl('/legal/cookies');

    expect(title.getTitle()).toBe('Cookies & Storage — SeatFlow');
    expect(meta.getTag('name="description"')?.content).toBe(
      'Cookies disclosure for tests.',
    );
    expect(meta.getTag('name="robots"')).toBeNull();
  });

  it('marks noindex routes and clears the directive when leaving', async () => {
    await router.navigateByUrl('/gone');

    expect(title.getTitle()).toBe('Page Not Found — SeatFlow');
    expect(meta.getTag('name="robots"')?.content).toBe('noindex, nofollow');

    await router.navigateByUrl('/legal/cookies');

    expect(meta.getTag('name="robots"')).toBeNull();
  });

  it('never leaks route parameters into title or description metadata', async () => {
    await router.navigateByUrl('/tickets/guest/ABC123-SECRET');

    expect(title.getTitle()).toBe('Guest Ticket — SeatFlow');
    expect(title.getTitle()).not.toContain('ABC123');
    const description = meta.getTag('name="description"')?.content ?? '';
    expect(description).not.toContain('ABC123');
    expect(description.length).toBeGreaterThan(0);
  });
});
