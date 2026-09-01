import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { RegisterComponent } from './register.component';

describe('RegisterComponent', () => {
  let component: RegisterComponent;
  let fixture: ComponentFixture<RegisterComponent>;
  let authServiceSpy: jasmine.SpyObj<AuthService>;

  beforeEach(async () => {
    authServiceSpy = jasmine.createSpyObj('AuthService', ['signUp', 'signInWithOAuth']);
    authServiceSpy.signUp.and.resolveTo();
    authServiceSpy.signInWithOAuth.and.resolveTo();

    await TestBed.configureTestingModule({
      imports: [RegisterComponent],
      providers: [
        { provide: AuthService, useValue: authServiceSpy },
        provideRouter([]),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(RegisterComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create the register component', () => {
    expect(component).toBeTruthy();
  });

  it('should validate form constraints and password matching', () => {
    expect(component.registerForm.valid).toBeFalse();

    component.registerForm.patchValue({
      name: 'A',
      email: 'invalid-email',
      password: 'short',
      confirmPassword: 'other',
      agreeTerms: false,
    });
    expect(component.registerForm.valid).toBeFalse();

    component.registerForm.patchValue({
      name: 'Maria Ionescu',
      email: 'maria@seatflow.com',
      password: 'SecurePassword123!',
      confirmPassword: 'MismatchedPassword123!',
      agreeTerms: true,
    });
    expect(component.registerForm.valid).toBeFalse();
    expect(component.registerForm.controls.confirmPassword.errors?.['passwordMismatch']).toBeTrue();

    component.registerForm.patchValue({
      confirmPassword: 'SecurePassword123!',
    });
    expect(component.registerForm.valid).toBeTrue();
  });

  it('should toggle password visibility signals', () => {
    expect(component.showPassword()).toBeFalse();
    component.togglePasswordVisibility();
    expect(component.showPassword()).toBeTrue();

    expect(component.showConfirmPassword()).toBeFalse();
    component.toggleConfirmPasswordVisibility();
    expect(component.showConfirmPassword()).toBeTrue();
  });

  it('should submit registration and update isSuccess signal', async () => {
    component.registerForm.setValue({
      name: 'Maria Ionescu',
      email: 'maria@seatflow.com',
      password: 'SecurePassword123!',
      confirmPassword: 'SecurePassword123!',
      agreeTerms: true,
    });

    await component.onSubmit();

    expect(authServiceSpy.signUp).toHaveBeenCalledWith(
      'maria@seatflow.com',
      'SecurePassword123!',
      'Maria Ionescu',
    );
    expect(component.isSuccess()).toBeTrue();
    expect(component.isLoading()).toBeFalse();
  });

  it('should handle registration failure gracefully', async () => {
    authServiceSpy.signUp.and.rejectWith(new Error('User already exists'));

    component.registerForm.setValue({
      name: 'Maria Ionescu',
      email: 'maria@seatflow.com',
      password: 'SecurePassword123!',
      confirmPassword: 'SecurePassword123!',
      agreeTerms: true,
    });

    await component.onSubmit();

    expect(component.errorMessage()).toBe('User already exists');
    expect(component.isSuccess()).toBeFalse();
    expect(component.isLoading()).toBeFalse();
  });

  it('should trigger Google OAuth on registration page', async () => {
    await component.signInWithGoogle();

    expect(authServiceSpy.signInWithOAuth).toHaveBeenCalledWith('google');
  });

  it('should handle Google OAuth error on registration page', async () => {
    authServiceSpy.signInWithOAuth.and.rejectWith(new Error('Google popup closed'));

    await component.signInWithGoogle();

    expect(component.errorMessage()).toBe('Google popup closed');
    expect(component.isGoogleLoading()).toBeFalse();
  });
});
