import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { AuthService } from '../auth/auth.service';

function isSeatFlowApiRequest(url: string): boolean {
  if (url.startsWith('/api/') || url === '/api') {
    return true;
  }

  if (typeof window !== 'undefined' && url.startsWith(`${window.location.origin}/api`)) {
    return true;
  }

  if (url.startsWith('http://localhost:8080/api') || url.startsWith('http://127.0.0.1:8080/api')) {
    return true;
  }

  return false;
}

export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const token = inject(AuthService).getToken();

  if (!token || !isSeatFlowApiRequest(request.url) || request.headers.has('Authorization')) {
    return next(request);
  }

  return next(
    request.clone({
      setHeaders: { Authorization: `Bearer ${token}` },
    }),
  );
};

