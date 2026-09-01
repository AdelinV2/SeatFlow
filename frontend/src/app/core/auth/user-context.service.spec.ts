import { TestBed } from '@angular/core/testing';
import { UserProfile } from '../../models/user.model';
import { UserContextService } from './user-context.service';

describe('UserContextService', () => {
  let service: UserContextService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(UserContextService);
  });

  it('starts with an unauthenticated empty context', () => {
    expect(service.currentUser()).toBeNull();
    expect(service.isAuthenticated()).toBeFalse();
    expect(service.roles()).toEqual([]);
    expect(service.userName()).toBe('User');
  });

  it('exposes customer identity through computed signals', () => {
    service.setUser(createUser(['ROLE_CUSTOMER']));

    expect(service.isAuthenticated()).toBeTrue();
    expect(service.userEmail()).toBe('alex@seatflow.test');
    expect(service.userName()).toBe('Alex Morgan');
    expect(service.isCustomer()).toBeTrue();
    expect(service.isStaff()).toBeFalse();
    expect(service.isAdmin()).toBeFalse();
  });

  it('treats administrators as staff while preserving the admin role', () => {
    service.setUser(createUser(['ROLE_ADMIN']));

    expect(service.isAdmin()).toBeTrue();
    expect(service.isStaff()).toBeTrue();
    expect(service.isCustomer()).toBeFalse();
  });

  it('falls back to the email when a display name is absent or empty', () => {
    service.setUser({ ...createUser([]), name: undefined });
    expect(service.userName()).toBe('alex@seatflow.test');

    service.setUser({ ...createUser([]), name: '   ' });
    expect(service.userName()).toBe('alex@seatflow.test');
  });

  it('clears every derived value on sign-out', () => {
    service.setUser(createUser(['ROLE_STAFF']));
    service.clearUser();

    expect(service.currentUser()).toBeNull();
    expect(service.isAuthenticated()).toBeFalse();
    expect(service.roles()).toEqual([]);
    expect(service.isStaff()).toBeFalse();
  });

  function createUser(roles: string[]): UserProfile {
    return {
      id: 'user-123',
      email: 'alex@seatflow.test',
      name: 'Alex Morgan',
      roles,
    };
  }
});
