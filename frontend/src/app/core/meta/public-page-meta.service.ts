import { Injectable, inject } from '@angular/core';
import { Meta, Title } from '@angular/platform-browser';
import { ActivatedRouteSnapshot, NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs/operators';

/**
 * Single metadata primitive for public informational pages (TASK-P16-005).
 *
 * Page `<title>` values come from Angular route `title` fields (applied by the
 * default `TitleStrategy` on navigation). This service owns only the two
 * document-head concerns that route titles cannot express:
 *
 * - `meta[name="description"]` from the deepest route `data.description`;
 * - `meta[name="robots"]` (`noindex, nofollow`) for routes with
 *   `data.noindex` (currently the 404 only).
 *
 * Design constraints:
 * - no per-component `document.title` / DOM mutations;
 * - no user, reservation, ticket, or auth data ever enters metadata — route
 *   `data` is static configuration, never a route parameter;
 * - SSR-safe: only `Title`, `Meta`, and `Router` are used (no `window` or
 *   direct `document` access). Started once via `APP_INITIALIZER`.
 */
@Injectable({ providedIn: 'root' })
export class PublicPageMetaService {
  private readonly router = inject(Router);
  private readonly title = inject(Title);
  private readonly meta = inject(Meta);

  private readonly defaultDescription =
    'SeatFlow makes discovering events and reserving the right seats effortless.';

  private started = false;

  /** Subscribe to navigation once; safe to call from `APP_INITIALIZER`. */
  start(): void {
    if (this.started) {
      return;
    }
    this.started = true;
    this.router.events
      .pipe(filter((event): event is NavigationEnd => event instanceof NavigationEnd))
      .subscribe(() => this.applyForCurrentRoute());
    this.applyForCurrentRoute();
  }

  /** Re-resolve metadata for the current router state (also used by tests). */
  applyForCurrentRoute(): void {
    let route: ActivatedRouteSnapshot | null = this.router.routerState.snapshot.root;
    let description: string | undefined;
    let pageTitle: string | undefined;
    let noindex = false;

    while (route) {
      // Route `title` lives on the snapshot (not in `data`); description and
      // noindex are static `data` entries, never route parameters.
      const snapshotTitle = route.title;
      if (typeof snapshotTitle === 'string' && snapshotTitle.length > 0) {
        pageTitle = snapshotTitle;
      }
      const data = route.data as { description?: unknown; noindex?: unknown };
      if (typeof data['description'] === 'string') {
        description = data['description'];
      }
      if (data['noindex'] === true) {
        noindex = true;
      }
      route = route.firstChild;
    }

    // Route `title` fields are normally applied by the default TitleStrategy,
    // but tests and non-navigation resolutions bypass it — apply idempotently.
    if (pageTitle) {
      this.title.setTitle(pageTitle);
    }
    this.meta.updateTag({ name: 'description', content: description ?? this.defaultDescription });
    if (noindex) {
      this.meta.updateTag({ name: 'robots', content: 'noindex, nofollow' });
    } else {
      this.meta.removeTag('name="robots"');
    }
  }
}
