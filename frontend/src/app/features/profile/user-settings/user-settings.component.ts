import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatSnackBar } from '@angular/material/snack-bar';
import { UserContextService } from '../../../core/auth/user-context.service';
import { ThemeMode } from '../../../core/theme/theme.model';
import { ThemeService } from '../../../core/theme/theme.service';
import { UserProfile } from '../../../models/user.model';
import { UserApiService } from '../../../services/user-api.service';
import { GlassCardComponent } from '../../../shared/components/glass-card/glass-card.component';
import { SkeletonLoaderComponent } from '../../../shared/components/skeleton-loader/skeleton-loader.component';
import { TactileButtonComponent } from '../../../shared/components/tactile-button/tactile-button.component';

@Component({
  selector: 'app-user-settings',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    GlassCardComponent,
    TactileButtonComponent,
    SkeletonLoaderComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './user-settings.component.html',
  styleUrl: './user-settings.component.scss',
})
export class UserSettingsComponent implements OnInit {
  private readonly formBuilder = inject(FormBuilder);
  private readonly userApi = inject(UserApiService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);
  readonly userContext = inject(UserContextService);
  readonly themeService = inject(ThemeService);

  readonly userProfile = signal<UserProfile | null>(null);
  readonly isLoading = signal<boolean>(true);
  readonly isSaving = signal<boolean>(false);

  readonly profileForm = this.formBuilder.group({
    name: [''],
    phone: ['', [Validators.maxLength(50)]],
  });

  ngOnInit(): void {
    this.loadUserProfile();
  }

  private loadUserProfile(): void {
    this.isLoading.set(true);
    this.userApi
      .getProfile()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (profile) => {
          this.userProfile.set(profile);
          this.profileForm.patchValue({
            name: profile.name || this.userContext.userName(),
            phone: profile.phone || '',
          });
          this.isLoading.set(false);
        },
        error: () => {
          // Fallback to UserContext data if /api/users/me is unavailable
          this.profileForm.patchValue({
            name: this.userContext.userName(),
            phone: '',
          });
          this.isLoading.set(false);
        },
      });
  }

  saveProfile(): void {
    if (this.profileForm.invalid || this.isSaving()) {
      return;
    }

    const { name, phone } = this.profileForm.value;
    this.isSaving.set(true);

    this.userApi
      .updateProfile({
        name: name?.trim() || undefined,
        phone: phone?.trim() || undefined,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (updated) => {
          this.userProfile.set(updated);
          this.isSaving.set(false);
          this.snackBar.open('Profile settings saved successfully.', 'Close', {
            duration: 3500,
            panelClass: 'snack-success',
          });
        },
        error: () => {
          this.isSaving.set(false);
          this.snackBar.open('Unable to update profile. Please try again.', 'Close', {
            duration: 4000,
            panelClass: 'snack-error',
          });
        },
      });
  }

  setThemeMode(mode: ThemeMode): void {
    this.themeService.setMode(mode);
  }
}
