import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import {
  provideRouter,
  Router,
  type CanActivateFn,
  type Route,
} from '@angular/router';
import { routes } from '../../app.routes';
import { AuthService } from '../../core/auth/auth.service';
import { UserContextService } from '../../core/auth/user-context.service';
import { adminGuard } from '../../core/guards/admin.guard';
import { authGuard } from '../../core/guards/auth.guard';
import { staffGuard } from '../../core/guards/staff.guard';
import { FooterComponent } from '../../shared/layout/footer/footer.component';

/**
 * TASK-P16-005 regression suite: public Phase 16 routes resolve signed-out,
 * protected guards are intact, every footer destination maps to a configured
 * route (never the wildcard), titles/metadata are distinct and honest, and the
 * NOT_REQUIRED consent decision cannot drift back into fake preference UI.
 */
describe('Footer links, route metadata, and consent hooks (TASK-P16-005)', () => {
  const publicPaths = [
    '/',
    '/events',
    '/legal/terms',
    '/legal/privacy',
    '/legal/tax',
    '/legal/refunds',
    '/legal/cookies',
    '/legal/security',
    '/support/faq',
    '/support/contact',
    '/status',
    '/api-docs',
  ];

  const expectedFooterHrefs = [
    '/events',
    '/profile/tickets',
    '/scanner',
    '/support/faq',
    '/support/contact',
    '/legal/terms',
    '/legal/privacy',
    '/legal/tax',
    '/legal/refunds',
    '/legal/cookies',
    '/legal/security',
    '/status',
    '/api-docs',
  ];

  function findRoute(path: string): Route | undefined {
    return routes.find((route: Route) => route.path === path);
  }

  function configureRouterTestingModule(): void {
    const authServiceSpy = jasmine.createSpyObj<AuthService>('AuthService', [
      'initialize',
    ]);
    authServiceSpy.initialize.and.resolveTo();

    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes),
        provideHttpClient(),
        provideHttpClientTesting(),
        UserContextService,
        { provide: AuthService, useValue: authServiceSpy },
      ],
    });
  }

  describe('public routes resolve signed-out', () => {
    it('navigates to every Phase 16 public destination without redirecting', async () => {
      configureRouterTestingModule();
      const router = TestBed.inject(Router);

      for (const url of publicPaths) {
        await router.navigateByUrl(url);
        expect(router.url).withContext(`signed-out navigation to ${url}`).toBe(url);
      }
    });

    it('renders the real 404 for unknown URLs and marks it noindex', async () => {
      configureRouterTestingModule();
      const router = TestBed.inject(Router);

      await router.navigateByUrl('/not-a-real-route-p16-005');

      expect(router.url).toBe('/not-a-real-route-p16-005');
      const activated =
        router.routerState.snapshot.root.firstChild?.component as unknown as {
          name?: string;
        };
      expect(activated?.name).toContain('NotFoundComponent');
      expect(document.title).toBe('Page Not Found — SeatFlow');

      const wildcard = findRoute('**');
      expect(wildcard?.data?.['noindex']).toBeTrue();
    });

    it('keeps public Phase 16 routes guard-free', () => {
      for (const url of publicPaths) {
        const path = url.replace(/^\//, '');
        const route = findRoute(path);
        expect(route).withContext(`route ${url} exists`).toBeDefined();
        expect(route?.canActivate)
          .withContext(`route ${url} stays public`)
          .toBeUndefined();
        expect(route?.canMatch)
          .withContext(`route ${url} has no matcher guard`)
          .toBeUndefined();
      }
    });
  });

  describe('protected routes stay protected', () => {
    it('does not weaken auth/staff/admin guards', () => {
      const protectedExpectations: Array<{ path: string; guard: CanActivateFn }> = [
        { path: 'profile/tickets', guard: authGuard },
        { path: 'scanner', guard: staffGuard },
        { path: 'admin', guard: adminGuard },
      ];
      for (const { path, guard } of protectedExpectations) {
        const route = findRoute(path);
        expect(route).withContext(`route ${path} exists`).toBeDefined();
        expect(route?.canActivate ?? [])
          .withContext(`route ${path} keeps its guard`)
          .toContain(guard);
      }
    });
  });

  describe('footer link integrity', () => {
    let fixture: ComponentFixture<FooterComponent>;

    beforeEach(async () => {
      TestBed.resetTestingModule();
      await TestBed.configureTestingModule({
        imports: [FooterComponent],
        providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
      }).compileComponents();

      fixture = TestBed.createComponent(FooterComponent);
      fixture.detectChanges();
    });

    function footerHrefs(): string[] {
      const anchors = Array.from(
        fixture.nativeElement.querySelectorAll('a'),
      ) as HTMLAnchorElement[];
      return anchors.map((anchor) => anchor.getAttribute('href') ?? '');
    }

    it('renders every expected internal destination', () => {
      const hrefs = footerHrefs();
      for (const expected of expectedFooterHrefs) {
        expect(hrefs).withContext(`footer links to ${expected}`).toContain(expected);
      }
    });

    it('maps every footer destination to a configured route, never the wildcard', () => {
      for (const href of footerHrefs()) {
        expect(href)
          .withContext('footer uses deliberate internal destinations only')
          .toMatch(/^\//);
        const path = href.split('?')[0].replace(/^\//, '');
        const route = findRoute(path);
        expect(route)
          .withContext(`footer destination ${href} is a configured route`)
          .toBeDefined();
        expect(path).withContext(`${href} must not rely on **`).not.toBe('**');
        expect(typeof route?.loadComponent)
          .withContext(`${href} lazy-loads its own component`)
          .toBe('function');
      }
    });

    it('preserves category query parameters on event catalog links', () => {
      const hrefs = footerHrefs();
      for (const category of ['CONCERT', 'THEATRE', 'SPORTS', 'FESTIVAL']) {
        expect(hrefs)
          .withContext(`footer keeps category=${category}`)
          .toContain(`/events?category=${category}`);
      }
    });

    it('exposes no admin destination and no misleading guest lookup', () => {
      const hrefs = footerHrefs();
      expect(hrefs).not.toContain('/auth/login');
      expect(hrefs.some((href) => href.startsWith('/admin')))
        .withContext('footer must not expose admin links')
        .toBeFalse();
      const text: string = fixture.nativeElement.textContent ?? '';
      expect(text).not.toContain('Guest Ticket Lookup');
    });
  });

  describe('route titles and descriptions', () => {
    it('gives every Phase 16 page plus home/events/404 a distinct static title', () => {
      const titledPaths = [
        '',
        'events',
        'legal/terms',
        'legal/privacy',
        'legal/cookies',
        'legal/security',
        'legal/refunds',
        'legal/tax',
        'support/faq',
        'support/contact',
        'status',
        'api-docs',
        '**',
      ];
      const titles = titledPaths.map((path) => {
        const route = findRoute(path);
        expect(route).withContext(`route ${path || '/'} exists`).toBeDefined();
        expect(typeof route?.title)
          .withContext(`route ${path || '/'} has a title`)
          .toBe('string');
        return route?.title as string;
      });
      expect(new Set(titles).size)
        .withContext('titles must be distinct per page')
        .toBe(titles.length);
    });

    it('keeps titles and descriptions free of certification, SLA, and guarantee claims', () => {
      const forbidden = [
        'GDPR compliant',
        'certified',
        'PCI',
        'ISO 27001',
        'SOC 2',
        '99.9%',
        '24/7',
        'guarantee',
      ];
      for (const route of routes) {
        const haystacks = [
          typeof route.title === 'string' ? route.title : '',
          typeof route.data?.['description'] === 'string'
            ? (route.data?.['description'] as string)
            : '',
        ];
        for (const haystack of haystacks) {
          for (const needle of forbidden) {
            expect(haystack)
              .withContext(`route ${route.path} metadata must not claim "${needle}"`)
              .not.toContain(needle);
          }
        }
      }
    });

    it('keeps the status title static instead of encoding live probe results', () => {
      const statusRoute = findRoute('status');
      expect(statusRoute?.title).toBe('Platform Status — SeatFlow');
    });
  });

  describe('cookie and privacy hooks (NOT_REQUIRED decision)', () => {
    let fixture: ComponentFixture<FooterComponent>;

    beforeEach(async () => {
      TestBed.resetTestingModule();
      await TestBed.configureTestingModule({
        imports: [FooterComponent],
        providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
      }).compileComponents();

      fixture = TestBed.createComponent(FooterComponent);
      fixture.detectChanges();
    });

    it('labels the cookies route as disclosure, not preferences, with no fake action', () => {
      const text: string = fixture.nativeElement.textContent ?? '';
      expect(text).not.toContain('Cookie Preferences');
      expect(text).toContain('Cookies & Storage');

      const buttons = fixture.nativeElement.querySelectorAll('button');
      expect(buttons.length).withContext('footer has no fake preference button').toBe(0);
    });

    it('shows no consent banner on first render', () => {
      const text: string = (fixture.nativeElement.textContent ?? '').toLowerCase();
      expect(text).not.toContain('we use cookies');
      expect(text).not.toContain('accept cookies');
      expect(text).not.toContain('consent');
    });

    it('introduces no new tracking or storage element in the footer', () => {
      const embedded = fixture.nativeElement.querySelectorAll('script,img,iframe');
      expect(embedded.length).toBe(0);
    });
  });
});
