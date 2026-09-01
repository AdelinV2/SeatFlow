import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  provideRouter,
  Router,
  RouterStateSnapshot,
  UrlTree,
} from '@angular/router';
import { UserProfile } from '../../models/user.model';
import { AuthService } from '../auth/auth.service';
import { UserContextService } from '../auth/user-context.service';
import { adminGuard } from './admin.guard';
import { authGuard } from './auth.guard';
import { guestGuard } from './guest.guard';
import { pendingChangesGuard, PendingChangesAware } from './pending-changes.guard';
import { staffGuard } from './staff.guard';

describe('functional route guards', () => {
  let userContext: UserContextService;
  let router: Router;
  let authService: jasmine.SpyObj<AuthService>;
  const route = {} as ActivatedRouteSnapshot;

  beforeEach(() => {
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['initialize']);
    authService.initialize.and.resolveTo();

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: authService },
      ],
    });
    userContext = TestBed.inject(UserContextService);
    router = TestBed.inject(Router);
  });

  it('authGuard preserves the protected return URL for guests', async () => {
    const result = await runGuard(authGuard, '/profile/tickets');

    expect(result).toBeInstanceOf(UrlTree);
    expect(router.serializeUrl(result as UrlTree)).toBe(
      '/auth/login?returnUrl=%2Fprofile%2Ftickets',
    );
    expect(authService.initialize).toHaveBeenCalled();
  });

  it('authGuard permits any authenticated user', async () => {
    userContext.setUser(createUser(['ROLE_CUSTOMER']));

    expect(await runGuard(authGuard, '/profile/tickets')).toBeTrue();
  });

  it('adminGuard permits only ROLE_ADMIN', async () => {
    userContext.setUser(createUser(['ROLE_ADMIN']));
    expect(await runGuard(adminGuard, '/admin/events')).toBeTrue();

    userContext.setUser(createUser(['ROLE_STAFF']));
    const denied = await runGuard(adminGuard, '/admin/events');
    expect(router.serializeUrl(denied as UrlTree)).toBe('/');
  });

  it('adminGuard sends unauthenticated users through login', async () => {
    const result = await runGuard(adminGuard, '/admin/events');

    expect(router.serializeUrl(result as UrlTree)).toBe('/auth/login?returnUrl=%2Fadmin%2Fevents');
  });

  it('staffGuard permits ROLE_STAFF and ROLE_ADMIN', async () => {
    userContext.setUser(createUser(['ROLE_STAFF']));
    expect(await runGuard(staffGuard, '/scanner')).toBeTrue();

    userContext.setUser(createUser(['ROLE_ADMIN']));
    expect(await runGuard(staffGuard, '/scanner')).toBeTrue();
  });

  it('staffGuard blocks authenticated customers', async () => {
    userContext.setUser(createUser(['ROLE_CUSTOMER']));

    const result = await runGuard(staffGuard, '/scanner');

    expect(router.serializeUrl(result as UrlTree)).toBe('/');
  });

  it('pendingChangesGuard allows clean components and consults dirty components', async () => {
    const cleanComponent: PendingChangesAware = { hasPendingChanges: () => false };
    expect(runPendingGuard(cleanComponent)).toBeTrue();

    const dirtyComponent: PendingChangesAware = {
      hasPendingChanges: () => true,
      confirmDiscardChanges: () => Promise.resolve(false),
    };
    expect(await runPendingGuard(dirtyComponent)).toBeFalse();
  });

  it('guestGuard allows unauthenticated guests to access auth pages', async () => {
    expect(await runGuard(guestGuard, '/auth/login')).toBeTrue();
  });

  it('guestGuard redirects authenticated users to events or returnUrl', async () => {
    userContext.setUser(createUser(['ROLE_CUSTOMER']));

    const result = await runGuard(guestGuard, '/auth/login');
    expect(router.serializeUrl(result as UrlTree)).toBe('/events');
  });

  async function runGuard(guard: typeof authGuard, url: string): Promise<boolean | UrlTree> {
    const state = { url } as RouterStateSnapshot;
    return TestBed.runInInjectionContext(() => guard(route, state)) as Promise<boolean | UrlTree>;
  }

  function runPendingGuard(component: PendingChangesAware): boolean | Promise<boolean> {
    return pendingChangesGuard(
      component,
      route,
      {} as RouterStateSnapshot,
      {} as RouterStateSnapshot,
    ) as boolean | Promise<boolean>;
  }

  function createUser(roles: string[]): UserProfile {
    return {
      id: 'user-123',
      email: 'alex@seatflow.test',
      name: 'Alex Morgan',
      roles,
    };
  }
});
