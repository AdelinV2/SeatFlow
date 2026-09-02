import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { authInterceptor } from './auth.interceptor';
import { correlationInterceptor } from './correlation.interceptor';
import { errorInterceptor } from './error.interceptor';

describe('core HTTP interceptors', () => {
  let httpTesting: HttpTestingController;
  let authService: jasmine.SpyObj<AuthService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;
  let router: jasmine.SpyObj<Router>;

  beforeEach(() => {
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['getToken']);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate'], { url: '/checkout/res-123' });
    router.navigate.and.resolveTo(true);

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(
          withInterceptors([correlationInterceptor, authInterceptor, errorInterceptor]),
        ),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: authService },
        { provide: MatSnackBar, useValue: snackBar },
        { provide: Router, useValue: router },
      ],
    });

    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('adds a UUID correlation header to SeatFlow API requests', () => {
    const http = TestBed.inject(HttpClient);

    http.get('/api/users/me').subscribe();

    const request = httpTesting.expectOne('/api/users/me');
    expect(request.request.headers.get('X-Correlation-Id')).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    );
    request.flush({});
  });

  it('preserves an existing correlation header', () => {
    const http = TestBed.inject(HttpClient);

    http.get('/api/events', { headers: { 'X-Correlation-Id': 'upstream-id' } }).subscribe();

    const request = httpTesting.expectOne('/api/events');
    expect(request.request.headers.get('X-Correlation-Id')).toBe('upstream-id');
    request.flush([]);
  });

  it('adds the bearer token only to SeatFlow protected API requests and isolates external APIs', () => {
    const http = TestBed.inject(HttpClient);
    authService.getToken.and.returnValue('access-token');

    http.get('http://localhost:8080/api/users/me').subscribe();
    const apiRequest = httpTesting.expectOne('http://localhost:8080/api/users/me');
    expect(apiRequest.request.headers.get('Authorization')).toBe('Bearer access-token');
    expect(apiRequest.request.headers.has('X-Correlation-Id')).toBeTrue();
    apiRequest.flush({});

    http.get('/api/admin/events').subscribe();
    const adminRequest = httpTesting.expectOne('/api/admin/events');
    expect(adminRequest.request.headers.get('Authorization')).toBe('Bearer access-token');
    expect(adminRequest.request.headers.has('X-Correlation-Id')).toBeTrue();
    adminRequest.flush([]);

    // Purely public anonymous endpoints omit Authorization header to prevent stale token 401s
    http.get('/api/events').subscribe();
    const publicRequest = httpTesting.expectOne('/api/events');
    expect(publicRequest.request.headers.has('Authorization')).toBeFalse();
    expect(publicRequest.request.headers.has('X-Correlation-Id')).toBeTrue();
    publicRequest.flush([]);

    // External services must not receive SeatFlow authentication or correlation headers.
    http.get('https://api.stripe.com/v1/tokens').subscribe();
    const stripeRequest = httpTesting.expectOne('https://api.stripe.com/v1/tokens');
    expect(stripeRequest.request.headers.has('Authorization')).toBeFalse();
    expect(stripeRequest.request.headers.has('X-Correlation-Id')).toBeFalse();
    stripeRequest.flush({});

    http.get('https://tiles.example.test/map').subscribe();
    const externalRequest = httpTesting.expectOne('https://tiles.example.test/map');
    expect(externalRequest.request.headers.has('Authorization')).toBeFalse();
    expect(externalRequest.request.headers.has('X-Correlation-Id')).toBeFalse();
    externalRequest.flush({});
  });

  it('renders a helpful network error when status is 0', () => {
    const http = TestBed.inject(HttpClient);

    http.get('/api/events').subscribe({ error: () => undefined });
    httpTesting.expectOne('/api/events').error(new ProgressEvent('error'), { status: 0 });

    expect(snackBar.open).toHaveBeenCalledWith(
      'Unable to connect to SeatFlow server. Please check your internet connection.',
      'Close',
      { duration: 4500, panelClass: 'snack-warning' },
    );
  });

  it('maps validation details into contextual snackbar feedback', () => {
    const http = TestBed.inject(HttpClient);

    http.post('/api/reservations', {}).subscribe({ error: () => undefined });
    httpTesting.expectOne('/api/reservations').flush(
      {
        status: 400,
        error: 'Bad Request',
        errorCode: 'VALIDATION_FAILED',
        message: 'Request validation failed',
        path: '/api/reservations',
        timestamp: new Date().toISOString(),
        validationErrors: [{ field: 'seats', message: 'Select at least one seat' }],
      },
      { status: 400, statusText: 'Bad Request' },
    );

    expect(snackBar.open).toHaveBeenCalledWith('Select at least one seat', 'Close', {
      duration: 4500,
      panelClass: 'snack-warning',
    });
  });

  it('shows the backend conflict message', () => {
    const http = TestBed.inject(HttpClient);

    http.post('/api/reservations', {}).subscribe({ error: () => undefined });
    httpTesting.expectOne('/api/reservations').flush(
      {
        status: 409,
        error: 'Conflict',
        errorCode: 'SEAT_ALREADY_HELD',
        message: 'Seat A-12 is no longer available',
        path: '/api/reservations',
        timestamp: new Date().toISOString(),
      },
      { status: 409, statusText: 'Conflict' },
    );

    expect(snackBar.open).toHaveBeenCalledWith(
      'Conflict: Seat A-12 is no longer available',
      'Close',
      { duration: 5000, panelClass: 'snack-warning' },
    );
  });

  it('redirects unauthorized requests to login with the current return URL', () => {
    const http = TestBed.inject(HttpClient);

    http.get('/api/users/me').subscribe({ error: () => undefined });
    httpTesting.expectOne('/api/users/me').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(router.navigate).toHaveBeenCalledWith(['/auth/login'], {
      queryParams: { returnUrl: '/checkout/res-123' },
    });
  });

  it('renders server error codes without hiding the original HTTP error', () => {
    const http = TestBed.inject(HttpClient);
    let receivedStatus = 0;

    http.get('/api/events').subscribe({
      error: (error: { status: number }) => {
        receivedStatus = error.status;
      },
    });
    httpTesting.expectOne('/api/events').flush(
      {
        status: 503,
        error: 'Service Unavailable',
        errorCode: 'SERVICE_UNAVAILABLE',
        message: 'Please try again shortly',
        path: '/api/events',
        timestamp: new Date().toISOString(),
      },
      { status: 503, statusText: 'Service Unavailable' },
    );

    expect(snackBar.open).toHaveBeenCalledWith(
      'Server Error [SERVICE_UNAVAILABLE]: Please try again shortly',
      'Close',
      { duration: 6000, panelClass: 'snack-error' },
    );
    expect(receivedStatus).toBe(503);
  });
});
