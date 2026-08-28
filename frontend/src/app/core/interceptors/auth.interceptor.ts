import { HttpInterceptorFn, HttpRequest } from '@angular/common/http';
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

/**
 * Determines if an endpoint is purely public and does not require an Authorization header.
 * By omitting the Authorization header on public read endpoints (such as public event catalog,
 * venue layout, and guest tickets), we prevent unauthenticated/expired session 401 errors
 * during public browsing.
 */
function isPublicAnonymousEndpoint(request: HttpRequest<unknown>): boolean {
  if (request.method !== 'GET') {
    return false;
  }

  const path = request.url.replace(/^(https?:\/\/[^/]+)/, '');

  // Admin routes ALWAYS require authentication
  if (path.startsWith('/api/admin/') || path.startsWith('/api/admin')) {
    return false;
  }

  // Public event catalog and seat maps: /api/events, /api/events/{id}, /api/events/{id}/seat-map
  if (path.startsWith('/api/events')) {
    return true;
  }

  // Public venue layouts: /api/venues, /api/venues/{id}, /api/venues/{id}/layout
  if (path.startsWith('/api/venues')) {
    return true;
  }

  // Public seat availability: /api/reservations/events/{id}/availability
  if (path.includes('/api/reservations/events/')) {
    return true;
  }

  // Public guest ticket lookup: /api/tickets/guest/{ticketCode}
  if (path.includes('/api/tickets/guest/')) {
    return true;
  }

  return false;
}

export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const token = inject(AuthService).getToken();

  if (
    !token ||
    !isSeatFlowApiRequest(request.url) ||
    request.headers.has('Authorization') ||
    isPublicAnonymousEndpoint(request)
  ) {
    return next(request);
  }

  return next(
    request.clone({
      setHeaders: { Authorization: `Bearer ${token}` },
    }),
  );
};

