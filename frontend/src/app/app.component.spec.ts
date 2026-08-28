import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AppComponent } from './app.component';
import { AuthService } from './core/auth/auth.service';
import { UserContextService } from './core/auth/user-context.service';
import { ActiveTheme } from './core/theme/theme.model';
import { ThemeService } from './core/theme/theme.service';

describe('AppComponent', () => {
  const activeTheme = signal<ActiveTheme>('dark');
  const isDark = signal(true);

  beforeEach(async () => {
    activeTheme.set('dark');
    isDark.set(true);

    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        provideRouter([]),
        UserContextService,
        {
          provide: AuthService,
          useValue: {
            login: jasmine.createSpy('login').and.resolveTo(),
            logout: jasmine.createSpy('logout').and.resolveTo(),
          },
        },
        {
          provide: ThemeService,
          useValue: {
            activeTheme,
            isDark,
            toggleTheme: jasmine.createSpy('toggleTheme'),
          },
        },
      ],
    }).compileComponents();
  });

  it('creates the root shell', () => {
    const fixture = TestBed.createComponent(AppComponent);

    expect(fixture.componentInstance).toBeTruthy();
  });

  it('reflects the active theme on the application shell', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    const shell = fixture.nativeElement.querySelector('.app-shell') as HTMLElement;
    expect(shell.dataset['theme']).toBe('dark');

    activeTheme.set('light');
    fixture.detectChanges();

    expect(shell.dataset['theme']).toBe('light');
  });

  it('renders the routed content outlet inside the main landmark', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    const main = fixture.nativeElement.querySelector('main') as HTMLElement;
    expect(main.querySelector('router-outlet')).not.toBeNull();
  });

  it('renders the global header and footer around routed content', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('app-header')).not.toBeNull();
    expect(element.querySelector('app-footer')).not.toBeNull();
    expect(element.textContent).toContain('All Systems Operational');
  });
});
