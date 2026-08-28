import { isPlatformBrowser } from '@angular/common';
import {
  computed,
  DestroyRef,
  effect,
  inject,
  Injectable,
  PLATFORM_ID,
  signal,
} from '@angular/core';
import { ActiveTheme, ThemeMode } from './theme.model';

@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly platformId = inject(PLATFORM_ID);
  private readonly destroyRef = inject(DestroyRef);
  private readonly storageKey = 'seatflow_theme_mode';

  readonly mode = signal<ThemeMode>(this.getInitialMode());
  private readonly systemPrefersDark = signal(this.getSystemDarkPreference());

  readonly activeTheme = computed<ActiveTheme>(() => {
    const currentMode = this.mode();

    if (currentMode === 'system') {
      return this.systemPrefersDark() ? 'dark' : 'light';
    }

    return currentMode;
  });

  readonly isDark = computed(() => this.activeTheme() === 'dark');

  constructor() {
    if (!isPlatformBrowser(this.platformId)) {
      return;
    }

    const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');
    const handleSystemThemeChange = (event: MediaQueryListEvent): void => {
      this.systemPrefersDark.set(event.matches);
    };

    mediaQuery.addEventListener('change', handleSystemThemeChange);
    this.destroyRef.onDestroy(() => {
      mediaQuery.removeEventListener('change', handleSystemThemeChange);
    });

    effect(() => {
      const activeTheme = this.activeTheme();
      const root = document.documentElement;

      root.classList.toggle('dark', activeTheme === 'dark');
      root.classList.toggle('light', activeTheme === 'light');
      localStorage.setItem(this.storageKey, this.mode());
    });
  }

  setMode(mode: ThemeMode): void {
    this.mode.set(mode);
  }

  toggleTheme(): void {
    this.mode.update((currentMode) => {
      if (currentMode === 'dark') {
        return 'light';
      }

      if (currentMode === 'light') {
        return 'dark';
      }

      return this.systemPrefersDark() ? 'light' : 'dark';
    });
  }

  private getInitialMode(): ThemeMode {
    if (!isPlatformBrowser(this.platformId)) {
      return 'dark';
    }

    const storedMode = localStorage.getItem(this.storageKey);
    return storedMode === 'dark' || storedMode === 'light' || storedMode === 'system'
      ? storedMode
      : 'system';
  }

  private getSystemDarkPreference(): boolean {
    if (!isPlatformBrowser(this.platformId)) {
      return true;
    }

    return window.matchMedia('(prefers-color-scheme: dark)').matches;
  }
}
