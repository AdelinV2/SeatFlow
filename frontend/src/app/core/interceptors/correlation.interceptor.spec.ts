import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { correlationInterceptor } from './correlation.interceptor';

describe('correlationInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([correlationInterceptor])),
        provideHttpClientTesting(),
      ],
    });

    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    TestBed.resetTestingModule();
  });

  it('adds a correlation id to SeatFlow API requests', () => {
    http.get('/api/users/me').subscribe();

    const request = httpMock.expectOne('/api/users/me');
    expect(request.request.headers.has('X-Correlation-Id')).toBeTrue();
    request.flush({});
  });

  it('does not add SeatFlow headers to external requests such as Nominatim', () => {
    const url = 'https://nominatim.openstreetmap.org/search?q=Opera&format=json';
    http.get(url).subscribe();

    const request = httpMock.expectOne(url);
    expect(request.request.headers.has('X-Correlation-Id')).toBeFalse();
    request.flush([]);
  });

  it('preserves an existing correlation id', () => {
    http.get('/api/users/me', {
      headers: { 'X-Correlation-Id': 'existing-id' },
    }).subscribe();

    const request = httpMock.expectOne('/api/users/me');
    expect(request.request.headers.get('X-Correlation-Id')).toBe('existing-id');
    request.flush({});
  });
});
