import { computed, Injectable, signal } from '@angular/core';
import { UserProfile } from '../../models/user.model';

@Injectable({ providedIn: 'root' })
export class UserContextService {
  readonly currentUser = signal<UserProfile | null>(null);

  readonly isAuthenticated = computed(() => this.currentUser() !== null);
  readonly roles = computed(() => this.currentUser()?.roles ?? []);
  readonly userEmail = computed(() => this.currentUser()?.email ?? '');
  readonly userName = computed(
    () => this.currentUser()?.name?.trim() || this.currentUser()?.email || 'User',
  );

  readonly isCustomer = computed(() => this.roles().includes('ROLE_CUSTOMER'));
  readonly isStaff = computed(
    () => this.roles().includes('ROLE_STAFF') || this.roles().includes('ROLE_ADMIN'),
  );
  readonly isAdmin = computed(() => this.roles().includes('ROLE_ADMIN'));

  setUser(user: UserProfile | null): void {
    this.currentUser.set(user);
  }

  clearUser(): void {
    this.currentUser.set(null);
  }
}
