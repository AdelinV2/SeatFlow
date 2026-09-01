import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { AdminUserApiService } from '../../../../services/admin-user-api.service';
import { UserProfile } from '../../../../models/user.model';
import { DateFormatPipe } from '../../../../shared/pipes/date-format.pipe';
import { SkeletonLoaderComponent } from '../../../../shared/components/skeleton-loader/skeleton-loader.component';

@Component({
  selector: 'app-admin-user-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    FormsModule,
    DateFormatPipe,
    SkeletonLoaderComponent,
  ],
  templateUrl: './admin-user-list.component.html',
  styleUrl: './admin-user-list.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminUserListComponent implements OnInit {
  private readonly userApi = inject(AdminUserApiService);

  readonly users = signal<UserProfile[]>([]);
  readonly isLoading = signal<boolean>(true);
  readonly totalElements = signal<number>(0);
  readonly totalPages = signal<number>(1);
  readonly currentPage = signal<number>(0);
  readonly pageSize = signal<number>(10);
  readonly sortField = signal<string>('createdAt');
  readonly sortDirection = signal<'asc' | 'desc'>('desc');
  readonly searchQuery = signal<string>('');

  readonly filteredUsers = computed(() => {
    const query = this.searchQuery().toLowerCase().trim();
    if (!query) return this.users();

    return this.users().filter(
      (u) =>
        u.email?.toLowerCase().includes(query) ||
        u.name?.toLowerCase().includes(query) ||
        u.phone?.toLowerCase().includes(query) ||
        u.id?.toLowerCase().includes(query)
    );
  });

  ngOnInit(): void {
    this.loadUsers();
  }

  loadUsers(): void {
    this.isLoading.set(true);

    this.userApi
      .getUsers({
        page: this.currentPage(),
        size: this.pageSize(),
        sort: this.sortField(),
        direction: this.sortDirection(),
      })
      .subscribe({
        next: (res) => {
          this.users.set(res.content || []);
          this.totalElements.set(res.totalElements || res.content?.length || 0);
          this.totalPages.set(res.totalPages || 1);
          this.isLoading.set(false);
        },
        error: () => {
          this.users.set([]);
          this.isLoading.set(false);
        },
      });
  }

  onPageChange(newPage: number): void {
    if (newPage >= 0 && newPage < this.totalPages()) {
      this.currentPage.set(newPage);
      this.loadUsers();
    }
  }

  onPageSizeChange(newSize: number): void {
    this.pageSize.set(newSize);
    this.currentPage.set(0);
    this.loadUsers();
  }

  onSortChange(field: string): void {
    if (this.sortField() === field) {
      this.sortDirection.set(this.sortDirection() === 'asc' ? 'desc' : 'asc');
    } else {
      this.sortField.set(field);
      this.sortDirection.set('desc');
    }
    this.currentPage.set(0);
    this.loadUsers();
  }

  getUserInitials(email: string, name?: string): string {
    if (name) {
      const parts = name.trim().split(/\s+/);
      if (parts.length >= 2) {
        return (parts[0][0] + parts[1][0]).toUpperCase();
      }
      return name.slice(0, 2).toUpperCase();
    }
    if (email) {
      return email.slice(0, 2).toUpperCase();
    }
    return 'U';
  }
}
