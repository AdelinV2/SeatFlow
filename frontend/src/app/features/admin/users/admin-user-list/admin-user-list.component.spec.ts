import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AdminUserListComponent } from './admin-user-list.component';
import { AdminUserApiService } from '../../../../services/admin-user-api.service';
import { UserProfile } from '../../../../models/user.model';
import { PagedResult } from '../../../../models/event.model';

describe('AdminUserListComponent', () => {
  let component: AdminUserListComponent;
  let fixture: ComponentFixture<AdminUserListComponent>;
  let userApiSpy: jasmine.SpyObj<AdminUserApiService>;

  const mockUsers: UserProfile[] = [
    {
      id: '123e4567-e89b-12d3-a456-426614174000',
      email: 'alex.smith@example.com',
      name: 'Alex Smith',
      phone: '+1-555-0199',
      roles: ['ROLE_CUSTOMER'],
      createdAt: '2026-08-20T10:00:00Z',
    },
    {
      id: '223e4567-e89b-12d3-a456-426614174000',
      email: 'admin@seatflow.com',
      name: 'SeatFlow Admin',
      roles: ['ROLE_ADMIN'],
      createdAt: '2026-08-15T12:00:00Z',
    },
  ];

  const mockPaged: PagedResult<UserProfile> = {
    content: mockUsers,
    page: 0,
    size: 10,
    totalElements: 2,
    totalPages: 1,
    isFirst: true,
    isLast: true,
  };

  beforeEach(async () => {
    userApiSpy = jasmine.createSpyObj('AdminUserApiService', ['getUsers']);
    userApiSpy.getUsers.and.returnValue(of(mockPaged));

    await TestBed.configureTestingModule({
      imports: [AdminUserListComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AdminUserApiService, useValue: userApiSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AdminUserListComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create and load users list', () => {
    expect(component).toBeTruthy();
    expect(userApiSpy.getUsers).toHaveBeenCalled();
    expect(component.users().length).toBe(2);
    expect(component.totalElements()).toBe(2);
  });

  it('should extract user initials correctly', () => {
    expect(component.getUserInitials('alex@test.com', 'Alex Smith')).toBe('AS');
    expect(component.getUserInitials('maria@test.com', 'Maria')).toBe('MA');
    expect(component.getUserInitials('guest@test.com')).toBe('GU');
  });

  it('should filter users by search query', () => {
    component.searchQuery.set('alex');
    expect(component.filteredUsers().length).toBe(1);
    expect(component.filteredUsers()[0].name).toBe('Alex Smith');

    component.searchQuery.set('admin');
    expect(component.filteredUsers().length).toBe(1);
    expect(component.filteredUsers()[0].email).toBe('admin@seatflow.com');
  });

  it('should handle page and sort changes', () => {
    component.onSortChange('email');
    expect(component.sortField()).toBe('email');
    expect(userApiSpy.getUsers).toHaveBeenCalled();

    component.onPageSizeChange(20);
    expect(component.pageSize()).toBe(20);
  });
});
