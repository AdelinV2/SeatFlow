import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { UserContextService } from '../auth/user-context.service';

export const adminGuard: CanActivateFn = async (_route, state) => {
  const authService = inject(AuthService);
  const userContext = inject(UserContextService);
  const router = inject(Router);

  await authService.initialize();

  if (!userContext.isAuthenticated()) {
    return router.createUrlTree(['/auth/login'], { queryParams: { returnUrl: state.url } });
  }

  return userContext.isAdmin() ? true : router.createUrlTree(['/']);
};
