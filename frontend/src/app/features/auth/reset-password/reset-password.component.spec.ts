import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { ResetPasswordComponent } from './reset-password.component';

describe('ResetPasswordComponent', () => {
  let component: ResetPasswordComponent;
  let fixture: ComponentFixture<ResetPasswordComponent>;
  let authServiceSpy: jasmine.SpyObj<AuthService>;

  beforeEach(async () => {
    authServiceSpy = jasmine.createSpyObj('AuthService', ['updatePassword']);
    authServiceSpy.updatePassword.and.resolveTo();

    await TestBed.configureTestingModule({
      imports: [ResetPasswordComponent],
      providers: [
        { provide: AuthService, useValue: authServiceSpy },
        provideRouter([]),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ResetPasswordComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create the reset password component', () => {
    expect(component).toBeTruthy();
  });

  it('should validate password matching and minimum length', () => {
    expect(component.resetForm.valid).toBeFalse();

    component.resetForm.setValue({
      password: 'short',
      confirmPassword: 'short',
    });
    expect(component.resetForm.valid).toBeFalse();
    expect(component.resetForm.controls.password.errors?.['minlength']).toBeTruthy();

    component.resetForm.setValue({
      password: 'SecureNewPassword123!',
      confirmPassword: 'DifferentPassword123!',
    });
    expect(component.resetForm.valid).toBeFalse();
    expect(component.resetForm.controls.confirmPassword.errors?.['passwordMismatch']).toBeTrue();

    component.resetForm.setValue({
      password: 'SecureNewPassword123!',
      confirmPassword: 'SecureNewPassword123!',
    });
    expect(component.resetForm.valid).toBeTrue();
  });

  it('should toggle password visibility signals', () => {
    expect(component.showPassword()).toBeFalse();
    component.togglePasswordVisibility();
    expect(component.showPassword()).toBeTrue();

    expect(component.showConfirmPassword()).toBeFalse();
    component.toggleConfirmPasswordVisibility();
    expect(component.showConfirmPassword()).toBeTrue();
  });

  it('should not call authService when form is invalid', async () => {
    await component.onSubmit();

    expect(authServiceSpy.updatePassword).not.toHaveBeenCalled();
    expect(component.resetForm.controls.password.touched).toBeTrue();
  });

  it('should submit new password and update isSuccess signal', async () => {
    component.resetForm.setValue({
      password: 'BrandNewSecurePassword123!',
      confirmPassword: 'BrandNewSecurePassword123!',
    });

    await component.onSubmit();

    expect(authServiceSpy.updatePassword).toHaveBeenCalledWith('BrandNewSecurePassword123!');
    expect(component.isSuccess()).toBeTrue();
    expect(component.isLoading()).toBeFalse();
  });

  it('should handle update password error', async () => {
    authServiceSpy.updatePassword.and.rejectWith(new Error('Session expired'));

    component.resetForm.setValue({
      password: 'BrandNewSecurePassword123!',
      confirmPassword: 'BrandNewSecurePassword123!',
    });

    await component.onSubmit();

    expect(component.errorMessage()).toBe('Session expired');
    expect(component.isSuccess()).toBeFalse();
    expect(component.isLoading()).toBeFalse();
  });
});
