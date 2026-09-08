import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, type Route } from '@angular/router';
import { routes } from '../../app.routes';
import { AuthService } from '../../core/auth/auth.service';
import { UserContextService } from '../../core/auth/user-context.service';
import { ApiDocsComponent } from './api-docs/api-docs.component';
import { NotFoundComponent } from './not-found/not-found.component';
import { StatusComponent } from './status/status.component';
import { ContactComponent } from './support/contact/contact.component';
import { FaqComponent } from './support/faq/faq.component';

/**
 * TASK-P16-004 route contract: FAQ, contact, status, and API docs are public
 * signed-out lazy routes with no guards; the wildcard 404 stays last; no
 * protected-route guard is weakened.
 */
describe('Support/status/API-docs routes (TASK-P16-004)', () => {
  const expected: Array<{ path: string; component: unknown }> = [
    { path: 'support/faq', component: FaqComponent },
    { path: 'support/contact', component: ContactComponent },
    { path: 'status', component: StatusComponent },
    { path: 'api-docs', component: ApiDocsComponent },
  ];

  it('exposes the four P16-004 pages as signed-out lazy routes without guards', async () => {
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

  it('keeps the wildcard 404 last after the P16-004 routes', async () => {
    const wildcardIndex = routes.findIndex((route: Route) => route.path === '**');
    expect(wildcardIndex).toBe(routes.length - 1);
    const wildcard = routes[wildcardIndex];
    const loadedWildcard = (await wildcard.loadComponent?.()) as unknown;
    expect(loadedWildcard).toBe(NotFoundComponent as unknown);
    for (const { path } of expected) {
      expect(routes.findIndex((r: Route) => r.path === path)).toBeLessThan(wildcardIndex);
    }
  });

  it('navigates signed-out to each P16-004 page', async () => {
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
