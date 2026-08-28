import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { AuthCallbackComponent } from './auth-callback.component';

describe('AuthCallbackComponent', () => {
  let component: AuthCallbackComponent;
  let fixture: ComponentFixture<AuthCallbackComponent>;
  let authServiceSpy: jasmine.SpyObj<AuthService>;
  let router: Router;

  beforeEach(async () => {
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
                get: (key: string) => (key === 'returnUrl' ? '/events/ev-123' : null),
              },
            },
          },
        },
      ],
    }).compileComponents();

    router = TestBed.inject(Router);
    spyOn(router, 'navigateByUrl').and.resolveTo(true);

    fixture = TestBed.createComponent(AuthCallbackComponent);
    component = fixture.componentInstance;
  });

  it('should initialize auth and redirect to returnUrl', async () => {
    await component.ngOnInit();

    expect(authServiceSpy.initialize).toHaveBeenCalled();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/events/ev-123');
  });
});
