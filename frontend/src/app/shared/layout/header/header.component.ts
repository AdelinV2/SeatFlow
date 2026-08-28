import { DOCUMENT, isPlatformBrowser } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  PLATFORM_ID,
  signal,
} from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
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

  readonly auth = inject(AuthService);
  readonly userContext = inject(UserContextService);
  readonly theme = inject(ThemeService);
  readonly mobileMenuOpen = signal(false);

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
    void this.auth.login().catch(() => undefined);
  }

  signOut(): void {
    this.closeMobileMenu();
    void this.auth.logout().catch(() => undefined);
  }
}
