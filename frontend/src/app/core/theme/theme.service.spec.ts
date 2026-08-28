import { TestBed } from '@angular/core/testing';
import { ThemeService } from './theme.service';

describe('ThemeService', () => {
  let mediaQueryMatches: boolean;
  let notifySystemThemeChange: ((event: MediaQueryListEvent) => void) | undefined;

  beforeEach(() => {
    mediaQueryMatches = false;
    notifySystemThemeChange = undefined;
    localStorage.clear();
    document.documentElement.classList.remove('dark', 'light');

    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: (query: string) => ({
        matches: mediaQueryMatches,
        media: query,
        onchange: null,
        addEventListener: (eventName: string, listener: (event: MediaQueryListEvent) => void) => {
          if (eventName === 'change') {
            notifySystemThemeChange = listener;
          }
        },
        removeEventListener: () => undefined,
        addListener: () => undefined,
        removeListener: () => undefined,
        dispatchEvent: () => true,
      }),
    });

    TestBed.configureTestingModule({});
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    localStorage.clear();
    document.documentElement.classList.remove('dark', 'light');
  });

  it('uses the stored mode and applies its root class', () => {
    localStorage.setItem('seatflow_theme_mode', 'light');

    const service = TestBed.inject(ThemeService);
    TestBed.tick();

    expect(service.mode()).toBe('light');
    expect(service.activeTheme()).toBe('light');
    expect(document.documentElement.classList.contains('light')).toBe(true);
    expect(document.documentElement.classList.contains('dark')).toBe(false);
  });

  it('resolves system mode and reacts to preference changes', () => {
    mediaQueryMatches = false;
    const service = TestBed.inject(ThemeService);
    TestBed.tick();

    expect(service.mode()).toBe('system');
    expect(service.activeTheme()).toBe('light');

    notifySystemThemeChange?.({ matches: true } as MediaQueryListEvent);
    TestBed.tick();

    expect(service.activeTheme()).toBe('dark');
    expect(service.isDark()).toBe(true);
    expect(document.documentElement.classList.contains('dark')).toBe(true);
  });

  it('sets, persists, and toggles an explicit theme mode', () => {
    const service = TestBed.inject(ThemeService);

    service.setMode('dark');
    TestBed.tick();

    expect(service.activeTheme()).toBe('dark');
    expect(localStorage.getItem('seatflow_theme_mode')).toBe('dark');

    service.toggleTheme();
    TestBed.tick();

    expect(service.mode()).toBe('light');
    expect(service.activeTheme()).toBe('light');
    expect(localStorage.getItem('seatflow_theme_mode')).toBe('light');
  });

  it('toggles away from the currently active system preference', () => {
    mediaQueryMatches = true;
    const service = TestBed.inject(ThemeService);

    service.toggleTheme();
    TestBed.tick();

    expect(service.mode()).toBe('light');
    expect(service.activeTheme()).toBe('light');
  });
});
