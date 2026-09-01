import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { LoginComponent } from './login.component';

describe('LoginComponent', () => {
  let component: LoginComponent;
  let fixture: ComponentFixture<LoginComponent>;
  let authServiceSpy: jasmine.SpyObj<AuthService>;
  let router: Router;

  beforeEach(async () => {
    authServiceSpy = jasmine.createSpyObj('AuthService', ['login', 'signInWithOAuth']);
    authServiceSpy.login.and.resolveTo();
    authServiceSpy.signInWithOAuth.and.resolveTo();

    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        { provide: AuthService, useValue: authServiceSpy },
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              queryParamMap: {
                get: (key: string) => (key === 'returnUrl' ? '/checkout/res-123' : null),
              },
            },
          },
        },
      ],
    }).compileComponents();

    router = TestBed.inject(Router);
    spyOn(router, 'navigateByUrl').and.resolveTo(true);

    fixture = TestBed.createComponent(LoginComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create the login component', () => {
    expect(component).toBeTruthy();
  });

  it('should validate form field constraints', () => {
    const emailControl = component.loginForm.controls.email;
    const passwordControl = component.loginForm.controls.password;

    expect(component.loginForm.valid).toBeFalse();

    emailControl.setValue('invalid-email');
    expect(emailControl.valid).toBeFalse();
    expect(emailControl.errors?.['email']).toBeTrue();

    emailControl.setValue('valid@seatflow.com');
    expect(emailControl.valid).toBeTrue();

    passwordControl.setValue('12345');
    expect(passwordControl.valid).toBeFalse();
    expect(passwordControl.errors?.['minlength']).toBeTruthy();

    passwordControl.setValue('SecurePass123!');
    expect(passwordControl.valid).toBeTrue();
    expect(component.loginForm.valid).toBeTrue();
  });

  it('should toggle password visibility signal', () => {
    expect(component.showPassword()).toBeFalse();
    component.togglePasswordVisibility();
    expect(component.showPassword()).toBeTrue();
    component.togglePasswordVisibility();
    expect(component.showPassword()).toBeFalse();
  });

  it('should not call authService.login when submitting invalid form', async () => {
    await component.onSubmit();

    expect(authServiceSpy.login).not.toHaveBeenCalled();
    expect(component.loginForm.controls.email.touched).toBeTrue();
    expect(component.loginForm.controls.password.touched).toBeTrue();
  });

  it('should submit valid credentials and redirect to returnUrl', async () => {
    component.loginForm.setValue({
      email: 'user@seatflow.com',
      password: 'StrongPassword123!',
    });

    await component.onSubmit();

    expect(authServiceSpy.login).toHaveBeenCalledWith('user@seatflow.com', 'StrongPassword123!');
    expect(router.navigateByUrl).toHaveBeenCalledWith('/checkout/res-123');
    expect(component.isLoading()).toBeFalse();
  });

  it('should display error message when login fails', async () => {
    authServiceSpy.login.and.rejectWith(new Error('Invalid login credentials'));

    component.loginForm.setValue({
      email: 'user@seatflow.com',
      password: 'WrongPassword123!',
    });

    await component.onSubmit();

    expect(component.errorMessage()).toBe('Invalid login credentials');
    expect(component.isLoading()).toBeFalse();
  });

  it('should trigger Google OAuth login', async () => {
    await component.signInWithGoogle();

    expect(authServiceSpy.signInWithOAuth).toHaveBeenCalledWith('google');
  });

  it('should handle Google OAuth error', async () => {
    authServiceSpy.signInWithOAuth.and.rejectWith(new Error('OAuth failed'));

    await component.signInWithGoogle();

    expect(component.errorMessage()).toBe('OAuth failed');
    expect(component.isGoogleLoading()).toBeFalse();
  });
});
