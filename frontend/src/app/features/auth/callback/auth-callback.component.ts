import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  inject,
  OnInit,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';

@Component({
  selector: 'app-auth-callback',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="min-h-[calc(100vh-8rem)] flex items-center justify-center px-4">
      <div class="flex flex-col items-center justify-center text-center p-8 rounded-3xl border border-[var(--color-border)] bg-[var(--color-surface)] shadow-xl max-w-md w-full animate-fade-in-up">
        <div class="relative mb-6">
          <div class="size-14 rounded-full border-4 border-indigo-500/20 border-t-indigo-600 animate-spin"></div>
        </div>
        <h2 class="text-xl font-bold text-[var(--color-text-primary)]">Finalizing Authentication</h2>
        <p class="mt-2 text-sm text-[var(--color-text-secondary)]">
          Verifying your security session. Redirecting you in a moment...
        </p>
      </div>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuthCallbackComponent implements OnInit {
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  async ngOnInit(): Promise<void> {
    try {
      await this.authService.initialize();
    } catch {
      // Ignore initialization errors and proceed with navigation
    }

    const rawReturnUrl = this.route.snapshot.queryParamMap.get('returnUrl');
    const targetUrl =
      rawReturnUrl &&
      rawReturnUrl.startsWith('/') &&
      !rawReturnUrl.startsWith('//') &&
      !rawReturnUrl.startsWith('/auth')
        ? rawReturnUrl
        : '/events';
    await this.router.navigateByUrl(targetUrl);
  }
}
