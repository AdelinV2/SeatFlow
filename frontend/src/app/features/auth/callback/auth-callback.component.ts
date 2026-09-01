import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { UserContextService } from '../../../core/auth/user-context.service';

@Component({
  selector: 'app-auth-callback',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <div class="min-h-[calc(100vh-8rem)] flex items-center justify-center px-4">
      <div
        class="flex flex-col items-center justify-center text-center p-8 rounded-3xl border border-[var(--color-border)] bg-[var(--color-surface)] shadow-xl max-w-md w-full animate-fade-in-up"
      >
        @if (errorMessage()) {
          <div
            class="size-14 rounded-2xl bg-rose-500/10 border border-rose-500/20 text-rose-400 flex items-center justify-center mb-6"
          >
            <svg class="size-7" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
              <path
                stroke-linecap="round"
                stroke-linejoin="round"
                d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"
              />
            </svg>
          </div>
          <h2 class="text-xl font-bold text-[var(--color-text-primary)]">Authentication Issue</h2>
          <p class="mt-2 text-sm text-[var(--color-text-secondary)]">
            {{ errorMessage() }}
          </p>
          <div class="mt-6 w-full flex flex-col gap-3">
            <a
              routerLink="/auth/login"
              class="w-full py-3 px-4 rounded-xl bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-semibold transition-all shadow-lg shadow-indigo-500/25 flex items-center justify-center gap-2"
            >
              Return to Sign In
            </a>
          </div>
        } @else if (isSuccess()) {
          <div
            class="size-14 rounded-2xl bg-emerald-500/10 border border-emerald-500/20 text-emerald-400 flex items-center justify-center mb-6"
          >
            <svg class="size-7" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7" />
            </svg>
          </div>
          <h2 class="text-xl font-bold text-[var(--color-text-primary)]">Authentication Successful</h2>
          <p class="mt-2 text-sm text-[var(--color-text-secondary)]">
            Redirecting you to SeatFlow...
          </p>
          <div class="mt-6 w-full">
            <a
              [routerLink]="redirectUrl()"
              class="w-full py-3 px-4 rounded-xl bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-semibold transition-all shadow-lg shadow-indigo-500/25 flex items-center justify-center gap-2"
            >
              Continue to SeatFlow
            </a>
          </div>
        } @else {
          <div class="relative mb-6">
            <div
              class="size-14 rounded-full border-4 border-indigo-500/20 border-t-indigo-600 animate-spin"
            ></div>
          </div>
          <h2 class="text-xl font-bold text-[var(--color-text-primary)]">Finalizing Authentication</h2>
          <p class="mt-2 text-sm text-[var(--color-text-secondary)]">
            Verifying your security session. Redirecting you in a moment...
          </p>
        }
      </div>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuthCallbackComponent implements OnInit {
  private readonly authService = inject(AuthService);
  private readonly userContext = inject(UserContextService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);

  readonly isVerifying = signal(true);
  readonly errorMessage = signal<string | null>(null);
  readonly isSuccess = signal(false);
  readonly redirectUrl = signal<string>('/events');

  private redirected = false;

  constructor() {
    effect(() => {
      if (this.userContext.isAuthenticated() && !this.redirected) {
        this.isVerifying.set(false);
        this.isSuccess.set(true);
        void this.performRedirect();
      }
    });
  }

  async ngOnInit(): Promise<void> {
    this.resolveTargetUrl();

    // 1. Check for error parameters in query params and URL hash
    const urlError = this.extractUrlError();
    if (urlError) {
      this.isVerifying.set(false);
      this.errorMessage.set(urlError);
      return;
    }

    // 2. Check for password recovery flow
    if (this.isPasswordRecoveryFlow()) {
      this.redirected = true;
      await this.router.navigateByUrl('/auth/reset-password', { replaceUrl: true });
      return;
    }

    // 3. Initialize auth service
    try {
      await this.authService.initialize();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to initialize session.';
      this.isVerifying.set(false);
      this.errorMessage.set(msg);
      return;
    }

    // 4. If already authenticated, redirect immediately
    if (this.userContext.isAuthenticated()) {
      this.isVerifying.set(false);
      this.isSuccess.set(true);
      await this.performRedirect();
      return;
    }

    // 5. Fallback safety timer: if after 4 seconds authentication hasn't resolved, display clear status
    const timeoutId = setTimeout(() => {
      if (!this.userContext.isAuthenticated() && !this.redirected) {
        this.isVerifying.set(false);
        this.errorMessage.set(
          'Authentication timed out or no active session was found. Please sign in again.',
        );
      }
    }, 4000);

    this.destroyRef.onDestroy(() => {
      clearTimeout(timeoutId);
    });
  }

  private resolveTargetUrl(): void {
    const rawReturnUrl = this.route.snapshot.queryParamMap.get('returnUrl');
    const targetUrl =
      rawReturnUrl &&
      rawReturnUrl.startsWith('/') &&
      !rawReturnUrl.startsWith('//') &&
      !rawReturnUrl.startsWith('/auth')
        ? rawReturnUrl
        : '/events';
    this.redirectUrl.set(targetUrl);
  }

  private isPasswordRecoveryFlow(): boolean {
    const queryType = this.route.snapshot.queryParamMap.get('type');
    if (queryType === 'recovery') return true;

    if (typeof window !== 'undefined' && window.location.hash) {
      const hashParams = new URLSearchParams(window.location.hash.replace(/^#/, ''));
      if (hashParams.get('type') === 'recovery') return true;
    }
    return false;
  }

  private extractUrlError(): string | null {
    // Check query params
    const qError =
      this.route.snapshot.queryParamMap.get('error_description') ??
      this.route.snapshot.queryParamMap.get('error');
    if (qError) return qError;

    // Check hash fragment
    if (typeof window !== 'undefined' && window.location.hash) {
      const hashParams = new URLSearchParams(window.location.hash.replace(/^#/, ''));
      const hError = hashParams.get('error_description') ?? hashParams.get('error');
      if (hError) return hError;
    }

    return null;
  }

  async performRedirect(): Promise<void> {
    if (this.redirected) return;
    this.redirected = true;
    const target = this.redirectUrl();
    try {
      await this.router.navigateByUrl(target, { replaceUrl: true });
    } catch {
      setTimeout(async () => {
        await this.router.navigateByUrl(target, { replaceUrl: true });
      }, 100);
    }
  }
}
