import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { UserContextService } from '../auth/user-context.service';

export const guestGuard: CanActivateFn = async (route, _state) => {
  const authService = inject(AuthService);
  const userContext = inject(UserContextService);
  const router = inject(Router);

  await authService.initialize();

  if (userContext.isAuthenticated()) {
    const rawReturnUrl = route?.queryParamMap?.get('returnUrl');
    const targetUrl =
      rawReturnUrl &&
      rawReturnUrl.startsWith('/') &&
      !rawReturnUrl.startsWith('//') &&
      !rawReturnUrl.startsWith('/auth')
        ? rawReturnUrl
        : '/events';
    return router.createUrlTree([targetUrl]);
  }

  return true;
};
