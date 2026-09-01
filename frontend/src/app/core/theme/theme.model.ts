export type ThemeMode = 'dark' | 'light' | 'system';

export type ActiveTheme = 'dark' | 'light';

export interface ThemeConfig {
  mode: ThemeMode;
  active: ActiveTheme;
}
