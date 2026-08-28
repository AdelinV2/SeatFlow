import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  inject,
  signal,
} from '@angular/core';
import {
  AbstractControl,
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';

export const passwordMatchValidator: ValidatorFn = (
  control: AbstractControl,
): ValidationErrors | null => {
  const password = control.get('password');
  const confirmPassword = control.get('confirmPassword');

  if (!password || !confirmPassword) {
    return null;
  }

  if (confirmPassword.errors && !confirmPassword.errors['passwordMismatch']) {
    return null;
  }

  if (password.value !== confirmPassword.value) {
    confirmPassword.setErrors({ passwordMismatch: true });
    return { passwordMismatch: true };
  } else {
    if (confirmPassword.errors?.['passwordMismatch']) {
      confirmPassword.setErrors(null);
    }
    return null;
  }
};

@Component({
  selector: 'app-register',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, RouterLink],
  templateUrl: './register.component.html',
  styleUrl: './register.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RegisterComponent {
  private readonly fb = inject(FormBuilder);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  readonly route = inject(ActivatedRoute);

  readonly isLoading = signal(false);
  readonly isGoogleLoading = signal(false);
  readonly isSuccess = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly showPassword = signal(false);
  readonly showConfirmPassword = signal(false);

  readonly registerForm = this.fb.nonNullable.group(
    {
      name: ['', [Validators.required, Validators.minLength(2)]],
      email: ['', [Validators.required, Validators.email]],
      password: ['', [Validators.required, Validators.minLength(8)]],
      confirmPassword: ['', [Validators.required]],
      agreeTerms: [false, [Validators.requiredTrue]],
    },
    { validators: [passwordMatchValidator] },
  );

  togglePasswordVisibility(): void {
    this.showPassword.update((val) => !val);
  }

  toggleConfirmPasswordVisibility(): void {
    this.showConfirmPassword.update((val) => !val);
  }

  async onSubmit(): Promise<void> {
    if (this.registerForm.invalid) {
      this.registerForm.markAllAsTouched();
      return;
    }

    this.isLoading.set(true);
    this.errorMessage.set(null);

    const { name, email, password } = this.registerForm.getRawValue();

    try {
      await this.authService.signUp(email, password, name);
      this.isSuccess.set(true);
    } catch (err: unknown) {
      const message =
        err instanceof Error
          ? err.message
          : 'Registration failed. Please try again.';
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
