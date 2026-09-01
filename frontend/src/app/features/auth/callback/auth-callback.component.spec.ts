import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { UserContextService } from '../../../core/auth/user-context.service';
import { AuthCallbackComponent } from './auth-callback.component';

describe('AuthCallbackComponent', () => {
  let component: AuthCallbackComponent;
  let fixture: ComponentFixture<AuthCallbackComponent>;
  let authServiceSpy: jasmine.SpyObj<AuthService>;
  let userContext: UserContextService;
  let router: Router;
  let queryParamsMap: Map<string, string>;

  beforeEach(async () => {
    queryParamsMap = new Map([['returnUrl', '/events/ev-123']]);
    authServiceSpy = jasmine.createSpyObj('AuthService', ['initialize']);
    authServiceSpy.initialize.and.resolveTo();

    await TestBed.configureTestingModule({
      imports: [AuthCallbackComponent],
      providers: [
        { provide: AuthService, useValue: authServiceSpy },
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              queryParamMap: {
                get: (key: string) => queryParamsMap.get(key) ?? null,
              },
            },
          },
        },
      ],
    }).compileComponents();

    router = TestBed.inject(Router);
    userContext = TestBed.inject(UserContextService);
    spyOn(router, 'navigateByUrl').and.resolveTo(true);
    jasmine.clock().install();
  });

  afterEach(() => {
    jasmine.clock().uninstall();
    userContext.clearUser();
    window.location.hash = '';
  });

  it('should initialize auth and redirect to returnUrl when already authenticated', async () => {
    userContext.setUser({
      id: 'user-1',
      email: 'test@example.com',
      name: 'Test User',
      roles: ['ROLE_CUSTOMER'],
    });

    fixture = TestBed.createComponent(AuthCallbackComponent);
    component = fixture.componentInstance;

    await component.ngOnInit();

    expect(authServiceSpy.initialize).toHaveBeenCalled();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/events/ev-123', { replaceUrl: true });
    expect(component.isSuccess()).toBeTrue();
  });

  it('should fallback to /events when returnUrl is omitted', async () => {
    queryParamsMap.clear();
    userContext.setUser({
      id: 'user-1',
      email: 'test@example.com',
      name: 'Test User',
      roles: ['ROLE_CUSTOMER'],
    });

    fixture = TestBed.createComponent(AuthCallbackComponent);
    component = fixture.componentInstance;

    await component.ngOnInit();

    expect(router.navigateByUrl).toHaveBeenCalledWith('/events', { replaceUrl: true });
  });

  it('should redirect to /auth/reset-password on password recovery query param', async () => {
    queryParamsMap.set('type', 'recovery');

    fixture = TestBed.createComponent(AuthCallbackComponent);
    component = fixture.componentInstance;

    await component.ngOnInit();

    expect(router.navigateByUrl).toHaveBeenCalledWith('/auth/reset-password', { replaceUrl: true });
  });

  it('should display error message when URL contains error params', async () => {
    queryParamsMap.set('error_description', 'The verification link has expired');

    fixture = TestBed.createComponent(AuthCallbackComponent);
    component = fixture.componentInstance;

    await component.ngOnInit();

    expect(component.errorMessage()).toBe('The verification link has expired');
    expect(component.isVerifying()).toBeFalse();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('should display error when auth service initialization rejects', async () => {
    authServiceSpy.initialize.and.rejectWith(new Error('Network error during init'));

    fixture = TestBed.createComponent(AuthCallbackComponent);
    component = fixture.componentInstance;

    await component.ngOnInit();

    expect(component.errorMessage()).toBe('Network error during init');
    expect(component.isVerifying()).toBeFalse();
  });

  it('should trigger redirect when userContext becomes authenticated reactively', async () => {
    fixture = TestBed.createComponent(AuthCallbackComponent);
    component = fixture.componentInstance;

    await component.ngOnInit();

    // Initially not authenticated
    expect(component.isSuccess()).toBeFalse();

    // User context updates asynchronously (e.g. from onAuthStateChange event)
    userContext.setUser({
      id: 'user-2',
      email: 'late@seatflow.com',
      name: 'Late User',
      roles: ['ROLE_CUSTOMER'],
    });

    // Run change detection so effect triggers
    fixture.detectChanges();
    TestBed.flushEffects();

    expect(router.navigateByUrl).toHaveBeenCalledWith('/events/ev-123', { replaceUrl: true });
    expect(component.isSuccess()).toBeTrue();
  });

  it('should show timeout error message if authentication does not resolve within timeout', async () => {
    fixture = TestBed.createComponent(AuthCallbackComponent);
    component = fixture.componentInstance;

    await component.ngOnInit();

    expect(component.errorMessage()).toBeNull();

    // Advance timer past the 4000ms threshold
    jasmine.clock().tick(4500);

    expect(component.errorMessage()).toContain('Authentication timed out');
    expect(component.isVerifying()).toBeFalse();
  });
});
