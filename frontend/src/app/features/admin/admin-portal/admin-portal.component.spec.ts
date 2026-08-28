import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AdminPortalComponent } from './admin-portal.component';
import { AdminVenueApiService } from '../../../services/admin-venue-api.service';
import { AdminUserApiService } from '../../../services/admin-user-api.service';
import { UserContextService } from '../../../core/auth/user-context.service';
import { VenueSummary } from '../../../models/venue.model';
import { UserProfile } from '../../../models/user.model';
import { PagedResult } from '../../../models/event.model';

describe('AdminPortalComponent', () => {
  let component: AdminPortalComponent;
  let fixture: ComponentFixture<AdminPortalComponent>;
  let venueApiSpy: jasmine.SpyObj<AdminVenueApiService>;
  let userApiSpy: jasmine.SpyObj<AdminUserApiService>;

  const mockVenues: VenueSummary[] = [
    {
      id: 'v-1',
      name: 'Grand Theatre',
      address: 'Main St 1',
      city: 'Bucharest',
      country: 'Romania',
      capacity: 1200,
      totalConfiguredSeats: 1150,
    },
    {
      id: 'v-2',
      name: 'Arena Central',
      address: 'Central Blvd',
      city: 'Cluj-Napoca',
      country: 'Romania',
      capacity: 5000,
      totalConfiguredSeats: 4800,
    },
  ];

  const mockUsers: UserProfile[] = [
    {
      id: 'u-1',
      email: 'admin@seatflow.com',
      roles: ['ROLE_ADMIN'],
    },
  ];

  const mockVenuePaged: PagedResult<VenueSummary> = {
    content: mockVenues,
    page: 0,
    size: 10,
    totalElements: 2,
    totalPages: 1,
    isFirst: true,
    isLast: true,
  };

  const mockUserPaged: PagedResult<UserProfile> = {
    content: mockUsers,
    page: 0,
    size: 5,
    totalElements: 1,
    totalPages: 1,
    isFirst: true,
    isLast: true,
  };

  beforeEach(async () => {
    venueApiSpy = jasmine.createSpyObj('AdminVenueApiService', ['getVenues']);
    userApiSpy = jasmine.createSpyObj('AdminUserApiService', ['getUsers']);

    venueApiSpy.getVenues.and.returnValue(of(mockVenuePaged));
    userApiSpy.getUsers.and.returnValue(of(mockUserPaged));

    await TestBed.configureTestingModule({
      imports: [AdminPortalComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AdminVenueApiService, useValue: venueApiSpy },
        { provide: AdminUserApiService, useValue: userApiSpy },
        {
          provide: UserContextService,
          useValue: {
            userName: () => 'System Admin',
            isAdmin: () => true,
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AdminPortalComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create and load dashboard metrics', () => {
    expect(component).toBeTruthy();
    expect(venueApiSpy.getVenues).toHaveBeenCalled();
    expect(userApiSpy.getUsers).toHaveBeenCalled();

    expect(component.totalVenues()).toBe(2);
    expect(component.totalUsers()).toBe(1);
    expect(component.totalCapacity()).toBe(6200);
    expect(component.totalConfiguredSeats()).toBe(5950);
  });
});
