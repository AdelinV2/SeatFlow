import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { ForgotPasswordComponent } from './forgot-password.component';

describe('ForgotPasswordComponent', () => {
  let component: ForgotPasswordComponent;
  let fixture: ComponentFixture<ForgotPasswordComponent>;
  let authServiceSpy: jasmine.SpyObj<AuthService>;

  beforeEach(async () => {
    authServiceSpy = jasmine.createSpyObj('AuthService', ['resetPasswordForEmail']);
    authServiceSpy.resetPasswordForEmail.and.resolveTo();

    await TestBed.configureTestingModule({
      imports: [ForgotPasswordComponent],
      providers: [
        { provide: AuthService, useValue: authServiceSpy },
        provideRouter([]),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ForgotPasswordComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create the forgot password component', () => {
    expect(component).toBeTruthy();
  });

  it('should validate email input', () => {
    const emailControl = component.forgotForm.controls.email;

    expect(component.forgotForm.valid).toBeFalse();

    emailControl.setValue('invalid-email');
    expect(emailControl.valid).toBeFalse();

    emailControl.setValue('valid@seatflow.com');
    expect(emailControl.valid).toBeTrue();
  });

  it('should not call authService when form is invalid', async () => {
    await component.onSubmit();

    expect(authServiceSpy.resetPasswordForEmail).not.toHaveBeenCalled();
    expect(component.forgotForm.controls.email.touched).toBeTrue();
  });

  it('should dispatch password reset and update isSuccess signal', async () => {
    component.forgotForm.setValue({ email: 'user@seatflow.com' });

    await component.onSubmit();

    expect(authServiceSpy.resetPasswordForEmail).toHaveBeenCalledWith('user@seatflow.com');
    expect(component.isSuccess()).toBeTrue();
    expect(component.isLoading()).toBeFalse();
  });

  it('should handle reset dispatch errors', async () => {
    authServiceSpy.resetPasswordForEmail.and.rejectWith(new Error('Rate limit exceeded'));

    component.forgotForm.setValue({ email: 'user@seatflow.com' });

    await component.onSubmit();

    expect(component.errorMessage()).toBe('Rate limit exceeded');
    expect(component.isSuccess()).toBeFalse();
    expect(component.isLoading()).toBeFalse();
  });
});
