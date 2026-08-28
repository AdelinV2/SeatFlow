import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { UserContextService } from '../../../core/auth/user-context.service';
import { ThemeService } from '../../../core/theme/theme.service';
import { HeaderComponent } from './header.component';

describe('HeaderComponent', () => {
  let fixture: ComponentFixture<HeaderComponent>;
  let userContext: UserContextService;
  let authService: jasmine.SpyObj<AuthService>;
  let toggleTheme: jasmine.Spy;
  const isDark = signal(true);

  beforeEach(async () => {
    isDark.set(true);
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['login', 'logout']);
    authService.login.and.resolveTo();
    authService.logout.and.resolveTo();
    toggleTheme = jasmine
      .createSpy('toggleTheme')
      .and.callFake(() => isDark.update((dark) => !dark));

    await TestBed.configureTestingModule({
      imports: [HeaderComponent],
      providers: [
        provideRouter([]),
        UserContextService,
        { provide: AuthService, useValue: authService },
        {
          provide: ThemeService,
          useValue: { isDark, toggleTheme },
        },
      ],
    }).compileComponents();

    userContext = TestBed.inject(UserContextService);
    fixture = TestBed.createComponent(HeaderComponent);
  });

  it('renders the public catalog and sign-in action for guests', () => {
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';

    expect(text).toContain('Catalog');
    expect(text).toContain('Sign in');
    expect(text).not.toContain('Admin Portal');
    expect(text).not.toContain('Staff Scanner');
  });

  it('renders customer, staff, and admin links from reactive roles', () => {
    userContext.setUser({
      id: 'admin-123',
      email: 'admin@seatflow.test',
      name: 'Ada Admin',
      roles: ['ROLE_ADMIN'],
    });
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';

    expect(text).toContain('My Tickets');
    expect(text).toContain('Staff Scanner');
    expect(text).toContain('Admin Portal');
    expect(text).toContain('Ada Admin');
  });

  it('opens and closes the mobile navigation drawer', () => {
    fixture.detectChanges();
    const toggle = (fixture.nativeElement as HTMLElement).querySelector(
      '[aria-controls="mobile-navigation"]',
    ) as HTMLButtonElement;

    toggle.click();
    fixture.detectChanges();

    expect(fixture.componentInstance.mobileMenuOpen()).toBeTrue();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('#mobile-navigation'),
    ).not.toBeNull();

    fixture.componentInstance.closeMobileMenu();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('#mobile-navigation')).toBeNull();
  });

  it('toggles the live theme with an accessible label', () => {
    fixture.detectChanges();
    const themeButton = (fixture.nativeElement as HTMLElement).querySelector(
      '[aria-label="Switch to light theme"]',
    ) as HTMLButtonElement;

    themeButton.click();
    fixture.detectChanges();

    expect(toggleTheme).toHaveBeenCalled();
    expect(themeButton.getAttribute('aria-label')).toBe('Switch to dark theme');
  });

  it('delegates sign-in navigation and sign-out to AuthService', () => {
    const router = TestBed.inject(Router);
    spyOn(router, 'navigate');

    fixture.detectChanges();
    fixture.componentInstance.signIn();
    expect(router.navigate).toHaveBeenCalledWith(['/auth/login']);

    userContext.setUser({
      id: 'user-123',
      email: 'alex@seatflow.test',
      roles: ['ROLE_CUSTOMER'],
    });
    fixture.detectChanges();
    fixture.componentInstance.signOut();

    expect(authService.logout).toHaveBeenCalled();
  });
});
