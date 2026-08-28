import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AppComponent } from './app.component';
import { ActiveTheme } from './core/theme/theme.model';
import { ThemeService } from './core/theme/theme.service';

describe('AppComponent', () => {
  const activeTheme = signal<ActiveTheme>('dark');

  beforeEach(async () => {
    activeTheme.set('dark');

    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        provideRouter([]),
        {
          provide: ThemeService,
          useValue: { activeTheme },
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
});
