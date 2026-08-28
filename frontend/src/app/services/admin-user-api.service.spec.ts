import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AdminUserApiService } from './admin-user-api.service';
import { UserProfile } from '../models/user.model';
import { PagedResult } from '../models/event.model';

describe('AdminUserApiService', () => {
  let service: AdminUserApiService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        AdminUserApiService,
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });

    service = TestBed.inject(AdminUserApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('should fetch users list with pagination and sort params', () => {
    const mockUsers: PagedResult<UserProfile> = {
      content: [
        {
          id: 'u-1',
          email: 'admin@seatflow.com',
          roles: ['ROLE_ADMIN'],
          createdAt: '2026-08-20T10:00:00Z',
        },
      ],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
      isFirst: true,
      isLast: true,
    };

    service
      .getUsers({ page: 0, size: 10, sort: 'createdAt', direction: 'desc' })
      .subscribe((res) => {
        expect(res.content.length).toBe(1);
        expect(res.content[0].email).toBe('admin@seatflow.com');
      });

    const req = httpMock.expectOne((r) => r.url === '/api/admin/users');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('size')).toBe('10');
    expect(req.request.params.get('sort')).toBe('createdAt');
    expect(req.request.params.get('direction')).toBe('desc');
    req.flush(mockUsers);
  });
});
