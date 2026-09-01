import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  inject,
  signal,
} from '@angular/core';
import {
  FormBuilder,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, RouterLink],
  templateUrl: './login.component.html',
  styleUrl: './login.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LoginComponent {
  private readonly fb = inject(FormBuilder);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  readonly route = inject(ActivatedRoute);

  readonly isLoading = signal(false);
  readonly isGoogleLoading = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly showPassword = signal(false);

  readonly loginForm = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(8)]],
  });

  togglePasswordVisibility(): void {
    this.showPassword.update((val) => !val);
  }

  async onSubmit(): Promise<void> {
    if (this.loginForm.invalid) {
      this.loginForm.markAllAsTouched();
      return;
    }

    this.isLoading.set(true);
    this.errorMessage.set(null);

    const { email, password } = this.loginForm.getRawValue();

    try {
      await this.authService.login(email, password);
      const rawReturnUrl = this.route.snapshot.queryParamMap.get('returnUrl');
      const targetUrl =
        rawReturnUrl &&
        rawReturnUrl.startsWith('/') &&
        !rawReturnUrl.startsWith('//') &&
        !rawReturnUrl.startsWith('/auth')
          ? rawReturnUrl
          : '/events';
      await this.router.navigateByUrl(targetUrl);
    } catch (err: unknown) {
      const message =
        err instanceof Error
          ? err.message
          : 'Authentication failed. Please check your credentials.';
      this.errorMessage.set(message);
    } finally {
      this.isLoading.set(false);
    }
  }

  async signInWithGoogle(): Promise<void> {
    this.isGoogleLoading.set(true);
    this.errorMessage.set(null);

    try {
      await this.authService.signInWithOAuth('google');
    } catch (err: unknown) {
      let message =
        err instanceof Error
          ? err.message
          : 'Google sign in failed.';
      if (message.toLowerCase().includes('provider is not enabled') || message.toLowerCase().includes('unsupported provider')) {
        message = 'Google Sign-In is not enabled on this Supabase project. Please sign in with email and password, or enable the Google Provider in the Supabase Dashboard.';
      }
      this.errorMessage.set(message);
      this.isGoogleLoading.set(false);
    }
  }
}
