import { DOCUMENT, isPlatformBrowser } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  PLATFORM_ID,
  signal,
} from '@angular/core';
import { Router, RouterLink, RouterLinkActive } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { UserContextService } from '../../../core/auth/user-context.service';
import { ThemeService } from '../../../core/theme/theme.service';

@Component({
  selector: 'app-header',
  standalone: true,
  imports: [RouterLink, RouterLinkActive],
  templateUrl: './header.component.html',
  styleUrl: './header.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '(document:keydown.escape)': 'closeMobileMenu()',
  },
})
export class HeaderComponent {
  private readonly platformId = inject(PLATFORM_ID);
  private readonly document = inject(DOCUMENT);
  private readonly router = inject(Router);

  readonly auth = inject(AuthService);
  readonly userContext = inject(UserContextService);
  readonly theme = inject(ThemeService);
  readonly mobileMenuOpen = signal(false);

  readonly userInitials = computed(() => {
    const name = this.userContext.userName().trim();
    if (!name) return 'U';
    const parts = name.split(/\s+/);
    if (parts.length >= 2) {
      return (parts[0][0] + parts[1][0]).toUpperCase();
    }
    return name.slice(0, 2).toUpperCase();
  });

  readonly userRoleBadge = computed(() => {
    if (this.userContext.isAdmin()) return 'Admin';
    if (this.userContext.isStaff()) return 'Staff';
    return null;
  });

  constructor() {
    if (isPlatformBrowser(this.platformId)) {
      effect(() => {
        this.document.body.style.overflow = this.mobileMenuOpen() ? 'hidden' : '';
      });
    }
  }

  toggleMobileMenu(): void {
    this.mobileMenuOpen.update((isOpen) => !isOpen);
  }

  closeMobileMenu(): void {
    this.mobileMenuOpen.set(false);
  }

  signIn(): void {
    this.closeMobileMenu();
    const currentUrl = this.router.url;
    const returnUrl =
      currentUrl && !currentUrl.startsWith('/auth') ? currentUrl : '/events';
    void this.router.navigate(['/auth/login'], {
      queryParams: { returnUrl },
    });
  }

  signOut(): void {
    this.closeMobileMenu();
    void this.auth.logout().catch(() => undefined);
  }
}
