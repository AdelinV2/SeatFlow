import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, type CanActivateFn, type Route } from '@angular/router';
import { routes } from '../../app.routes';
import { AuthService } from '../../core/auth/auth.service';
import { UserContextService } from '../../core/auth/user-context.service';
import { adminGuard } from '../../core/guards/admin.guard';
import { authGuard } from '../../core/guards/auth.guard';
import { staffGuard } from '../../core/guards/staff.guard';
import { EventListComponent } from '../events/event-list/event-list.component';
import { NotFoundComponent } from './not-found/not-found.component';

/**
 * TASK-P16-001 route contract: the wildcard renders a real 404, existing
 * routes keep their components/guards, and no legal/support/status/api-docs
 * placeholder route is introduced before its component exists.
 */
describe('Public routes (TASK-P16-001)', () => {
  it('renders the not-found component on the wildcard route instead of redirecting', async () => {
    const wildcard = routes[routes.length - 1];
    expect(wildcard.path).toBe('**');
    expect((wildcard as { redirectTo?: string }).redirectTo).toBeUndefined();
    expect(wildcard.canActivate).toBeUndefined();
    expect(typeof wildcard.loadComponent).toBe('function');

    const loaded = (await wildcard.loadComponent?.()) as unknown;
    expect(loaded).toBe(NotFoundComponent as unknown);
  });

  it('keeps wildcard as the last route so concrete routes are never captured', () => {
    const wildcardIndex = routes.findIndex((route: Route) => route.path === '**');
    expect(wildcardIndex).toBe(routes.length - 1);
  });

  it('retains the existing home and events components', async () => {
    const home = routes.find((route: Route) => route.path === '');
    const events = routes.find((route: Route) => route.path === 'events');
    expect(home).toBeDefined();
    expect(events).toBeDefined();

    for (const route of [home, events]) {
      const loaded = (await route?.loadComponent?.()) as unknown;
      expect(loaded).toBe(EventListComponent as unknown);
    }
  });

  it('preserves auth/admin/staff guards after the route edit', () => {
    const protectedExpectations: Array<{ path: string; guard: CanActivateFn }> = [
      { path: 'profile/tickets', guard: authGuard },
      { path: 'profile/settings', guard: authGuard },
      { path: 'scanner', guard: staffGuard },
      { path: 'admin', guard: adminGuard },
      { path: 'admin/venues', guard: adminGuard },
      { path: 'admin/events', guard: adminGuard },
    ];
    for (const { path, guard } of protectedExpectations) {
      const route = routes.find((r: Route) => r.path === path);
      expect(route).withContext(`route ${path} exists`).toBeDefined();
      expect(route?.canActivate ?? []).withContext(`route ${path} keeps its guard`).toContain(guard);
    }
  });

  it('adds no placeholder support/status/api-docs route beyond TASK-P16-003', () => {
    const paths = routes.map((route: Route) => route.path ?? '');
    // TASK-P16-002 owns four legal routes; TASK-P16-003 adds refunds + tax; later tasks own the rest.
    for (const allowed of ['legal/terms', 'legal/privacy', 'legal/cookies', 'legal/security', 'legal/refunds', 'legal/tax']) {
      expect(paths).withContext(`P16-002/P16-003 route ${allowed} exists`).toContain(allowed);
    }
    expect(paths.some((p: string) => p.startsWith('support/'))).toBeFalse();
    expect(paths).not.toContain('status');
    expect(paths).not.toContain('api-docs');
  });

  it('navigates an unknown URL to the 404 instead of the home page', async () => {
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

    const router = TestBed.inject(Router);
    await router.navigateByUrl('/does-not-exist-p16-001');

    expect(router.url).toBe('/does-not-exist-p16-001');
    const activated =
      router.routerState.snapshot.root.firstChild?.component as unknown as {
        name?: string;
      };
    // Component class names may carry an AOT prefix (e.g. `_NotFoundComponent`).
    expect(activated?.name).toContain('NotFoundComponent');
  });
});
