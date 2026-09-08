import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  APP_INITIALIZER,
  ApplicationConfig,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import {
  provideRouter,
  withComponentInputBinding,
  withInMemoryScrolling,
  withViewTransitions,
} from '@angular/router';
import { routes } from './app.routes';
import { authInterceptor } from './core/interceptors/auth.interceptor';
import { correlationInterceptor } from './core/interceptors/correlation.interceptor';
import { errorInterceptor } from './core/interceptors/error.interceptor';
import { PublicPageMetaService } from './core/meta/public-page-meta.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // TASK-P16-005: start the single route-metadata primitive once at startup.
    {
      provide: APP_INITIALIZER,
      multi: true,
      useFactory: (pageMeta: PublicPageMetaService) => () => pageMeta.start(),
      deps: [PublicPageMetaService],
    },
    provideRouter(
      routes,
      withComponentInputBinding(),
      withInMemoryScrolling({ scrollPositionRestoration: 'top' }),
      withViewTransitions({ skipInitialTransition: true }),
    ),
    provideHttpClient(
      withInterceptors([correlationInterceptor, authInterceptor, errorInterceptor]),
    ),
    provideAnimationsAsync(),
  ],
};
