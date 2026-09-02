import { HttpInterceptorFn } from '@angular/common/http';

function generateCorrelationId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }

  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (character) => {
    const randomNibble = (Math.random() * 16) | 0;
    const value = character === 'x' ? randomNibble : (randomNibble & 0x3) | 0x8;
    return value.toString(16);
  });
}

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

export const correlationInterceptor: HttpInterceptorFn = (request, next) => {
  if (!isSeatFlowApiRequest(request.url) || request.headers.has('X-Correlation-Id')) {
    return next(request);
  }

  return next(
    request.clone({
      setHeaders: { 'X-Correlation-Id': generateCorrelationId() },
    }),
  );
};
