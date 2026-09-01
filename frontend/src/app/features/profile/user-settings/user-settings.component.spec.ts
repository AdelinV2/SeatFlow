import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ReactiveFormsModule } from '@angular/forms';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';
import { UserContextService } from '../../../core/auth/user-context.service';
import { ThemeService } from '../../../core/theme/theme.service';
import { UserProfile } from '../../../models/user.model';
import { UserApiService } from '../../../services/user-api.service';
import { UserSettingsComponent } from './user-settings.component';

describe('UserSettingsComponent', () => {
  let component: UserSettingsComponent;
  let fixture: ComponentFixture<UserSettingsComponent>;
  let userApiSpy: jasmine.SpyObj<UserApiService>;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;
  let userContextSpy: jasmine.SpyObj<UserContextService>;
  let themeServiceSpy: jasmine.SpyObj<ThemeService>;

  const mockProfile: UserProfile = {
    id: 'user-uuid-001',
    email: 'alex@example.com',
    name: 'Alex Smith',
    roles: ['ROLE_CUSTOMER'],
    phone: '+1-555-0199',
    createdAt: '2026-08-20T10:00:00Z',
  };

  beforeEach(async () => {
    userApiSpy = jasmine.createSpyObj('UserApiService', ['getProfile', 'updateProfile']);
    snackBarSpy = jasmine.createSpyObj('MatSnackBar', ['open']);
    userContextSpy = jasmine.createSpyObj('UserContextService', [
      'userName',
      'userEmail',
      'roles',
    ]);
    themeServiceSpy = jasmine.createSpyObj('ThemeService', ['setMode', 'mode']);

    userApiSpy.getProfile.and.returnValue(of(mockProfile));
    userContextSpy.userName.and.returnValue('Alex Smith');
    userContextSpy.userEmail.and.returnValue('alex@example.com');
    userContextSpy.roles.and.returnValue(['ROLE_CUSTOMER']);
    themeServiceSpy.mode.and.returnValue('dark');

    await TestBed.configureTestingModule({
      imports: [UserSettingsComponent, ReactiveFormsModule],
      providers: [
        { provide: UserApiService, useValue: userApiSpy },
        { provide: MatSnackBar, useValue: snackBarSpy },
        { provide: UserContextService, useValue: userContextSpy },
        { provide: ThemeService, useValue: themeServiceSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(UserSettingsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('loads user profile and sets form values', () => {
    expect(userApiSpy.getProfile).toHaveBeenCalled();
    expect(component.profileForm.controls.name.value).toBe('Alex Smith');
    expect(component.profileForm.controls.phone.value).toBe('+1-555-0199');
    expect(component.isLoading()).toBeFalse();
  });

  it('updates profile phone and displays success toast', () => {
    const updatedProfile: UserProfile = {
      ...mockProfile,
      phone: '+1-555-9999',
    };
    userApiSpy.updateProfile.and.returnValue(of(updatedProfile));

    component.profileForm.patchValue({ phone: '+1-555-9999' });
    component.saveProfile();

    expect(userApiSpy.updateProfile).toHaveBeenCalledWith({
      name: 'Alex Smith',
      phone: '+1-555-9999',
    });
    expect(component.userProfile()?.phone).toBe('+1-555-9999');
    expect(snackBarSpy.open).toHaveBeenCalled();
  });

  it('handles update profile error', () => {
    userApiSpy.updateProfile.and.returnValue(throwError(() => new Error('Server error')));

    component.profileForm.patchValue({ phone: '+1-555-9999' });
    component.saveProfile();

    expect(component.isSaving()).toBeFalse();
    expect(snackBarSpy.open).toHaveBeenCalled();
  });

  it('sets theme mode via ThemeService', () => {
    component.setThemeMode('light');
    expect(themeServiceSpy.setMode).toHaveBeenCalledWith('light');
  });
});
