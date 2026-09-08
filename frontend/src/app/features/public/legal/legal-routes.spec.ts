import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, type Route } from '@angular/router';
import { routes } from '../../../app.routes';
import { AuthService } from '../../../core/auth/auth.service';
import { UserContextService } from '../../../core/auth/user-context.service';
import { CookiesComponent } from './cookies/cookies.component';
import { PrivacyComponent } from './privacy/privacy.component';
import { RefundsComponent } from './refunds/refunds.component';
import { SecurityComponent } from './security/security.component';
import { TaxComponent } from './tax/tax.component';
import { TermsComponent } from './terms/terms.component';
import { NotFoundComponent } from '../not-found/not-found.component';

/**
 * TASK-P16-002 route contract as extended by TASK-P16-003: the six legal pages
 * are public lazy routes with no guards, render through the P16-001 shell
 * components, keep the wildcard 404 last, and introduce no consent banner or
 * P16-004/P16-005 placeholder route.
 */
describe('Legal routes (TASK-P16-002 + TASK-P16-003)', () => {
  const expected: Array<{ path: string; component: unknown }> = [
    { path: 'legal/terms', component: TermsComponent },
    { path: 'legal/privacy', component: PrivacyComponent },
    { path: 'legal/cookies', component: CookiesComponent },
    { path: 'legal/security', component: SecurityComponent },
    { path: 'legal/refunds', component: RefundsComponent },
    { path: 'legal/tax', component: TaxComponent },
  ];

  it('exposes the six legal pages as signed-out lazy routes without guards', async () => {
    for (const { path, component } of expected) {
      const route = routes.find((r: Route) => r.path === path);
      expect(route).withContext(`route ${path} exists`).toBeDefined();
      expect(route?.canActivate).withContext(`route ${path} has no guard`).toBeUndefined();
      expect(route?.canMatch).withContext(`route ${path} has no matcher guard`).toBeUndefined();
      expect(typeof route?.loadComponent).toBe('function');
      const loaded = (await route?.loadComponent?.()) as unknown;
      expect(loaded).toBe(component as unknown);
    }
  });

  it('keeps the wildcard 404 last after the legal routes', async () => {
    const wildcardIndex = routes.findIndex((route: Route) => route.path === '**');
    expect(wildcardIndex).toBe(routes.length - 1);
    const wildcard = routes[wildcardIndex];
    const loadedWildcard = (await wildcard.loadComponent?.()) as unknown;
    expect(loadedWildcard).toBe(NotFoundComponent as unknown);
    for (const { path } of expected) {
      expect(routes.findIndex((r: Route) => r.path === path)).toBeLessThan(wildcardIndex);
    }
  });

  it('adds no consent banner route and no P16-005 placeholder route', () => {
    const paths = routes.map((route: Route) => route.path ?? '');
    expect(paths).not.toContain('legal/consent');
    expect(paths).toContain('legal/tax');
    expect(paths).toContain('legal/refunds');
    // TASK-P16-004 owns the support/status/api-docs routes.
    expect(paths).toContain('support/faq');
    expect(paths).toContain('support/contact');
    expect(paths).toContain('status');
    expect(paths).toContain('api-docs');
  });

  it('navigates signed-out to each legal page', async () => {
    const authServiceSpy = jasmine.createSpyObj<AuthService>('AuthService', ['initialize']);
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

    const router = TestBed.inject(Router);
    for (const { path } of expected) {
      await router.navigateByUrl(`/${path}`);
      expect(router.url).toBe(`/${path}`);
    }
  });
});
