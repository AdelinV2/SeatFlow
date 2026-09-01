import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { ApiErrorResponse } from '../../models/api-error.model';

function isApiErrorResponse(value: unknown): value is ApiErrorResponse {
  if (typeof value !== 'object' || value === null) {
    return false;
  }

  const candidate = value as Partial<ApiErrorResponse>;
  return typeof candidate.message === 'string' && typeof candidate.errorCode === 'string';
}

function resolveMessage(error: HttpErrorResponse, apiError: ApiErrorResponse | null): string {
  if (error.status === 0) {
    return 'Unable to connect to SeatFlow server. Please check your internet connection.';
  }

  if (
    error.error instanceof SyntaxError ||
    (typeof error.error === 'string' && error.error.startsWith('<!doctype')) ||
    error.message?.includes('Unexpected token')
  ) {
    return 'Invalid server response. Please verify the backend service is running.';
  }

  const validationMessage = apiError?.validationErrors?.[0]?.message;
  if (validationMessage) {
    return validationMessage;
  }

  if (apiError?.message) {
    return apiError.message;
  }

  if (error.statusText && error.statusText !== 'OK') {
    return error.statusText;
  }

  return 'An unexpected error occurred. Please try again.';
}

export const errorInterceptor: HttpInterceptorFn = (request, next) => {
  const router = inject(Router);
  const snackBar = inject(MatSnackBar);

  return next(request).pipe(
    catchError((error: HttpErrorResponse) => {
      const apiError = isApiErrorResponse(error.error) ? error.error : null;
      const message = resolveMessage(error, apiError);

      if (error.status === 401) {
        void router.navigate(['/auth/login'], {
          queryParams: { returnUrl: router.url },
        });
      } else if (error.status === 403) {
        snackBar.open('Access Denied: You do not have permission for this action.', 'Close', {
          duration: 4000,
          panelClass: 'snack-error',
        });
      } else if (error.status === 409) {
        snackBar.open(`Conflict: ${message}`, 'Close', {
          duration: 5000,
          panelClass: 'snack-warning',
        });
      } else if (error.status >= 500) {
        snackBar.open(`Server Error [${apiError?.errorCode ?? 'UNKNOWN'}]: ${message}`, 'Close', {
          duration: 6000,
          panelClass: 'snack-error',
        });
      } else if (error.status === 0 || error.status >= 400) {
        snackBar.open(message, 'Close', {
          duration: 4500,
          panelClass: 'snack-warning',
        });
      }

      return throwError(() => error);
    }),
  );
};
