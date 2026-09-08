import { DOCUMENT, isPlatformBrowser } from '@angular/common';
import {
  Directive,
  ElementRef,
  HostListener,
  PLATFORM_ID,
  booleanAttribute,
  inject,
  input,
} from '@angular/core';

/**
 * In-page fragment navigation that the Angular router must not hijack.
 *
 * Plain `<a href="#section">` clicks are intercepted by the router's
 * view-transition handler and resolved against the app root, which navigates
 * away to `/#section` (the home page) instead of scrolling within the current
 * page. This directive handles a primary click instead: it prevents the
 * router navigation, smooth-scrolls to the target element (honoring the
 * global `scroll-margin-top` offsets and `prefers-reduced-motion`), and
 * reflects the fragment in the URL via `history.replaceState` without
 * triggering a route change.
 *
 * Modified clicks (Ctrl/Cmd+click, Shift+click, middle-click) keep native
 * behavior so "open in new tab" still works, and the `href` is preserved for
 * semantics, keyboard focus, and no-JS rendering.
 */
@Directive({
  selector: 'a[appFragmentScroll]',
  standalone: true,
})
export class FragmentScrollDirective {
  private readonly el = inject(ElementRef);
  private readonly document = inject(DOCUMENT);
  private readonly platformId = inject(PLATFORM_ID);

  /** Explicit fragment id (without the leading `#`). Defaults to the anchor's own `href` hash. */
  readonly target = input<string>('', { alias: 'appFragmentScroll' });
  /** Move keyboard focus to the target after scrolling (for skip links). */
  readonly focusTarget = input(false, {
    alias: 'appFragmentScrollFocus',
    transform: booleanAttribute,
  });

  @HostListener('click', ['$event'])
  onClick(event: MouseEvent): void {
    if (
      event.defaultPrevented ||
      event.button !== 0 ||
      event.ctrlKey ||
      event.metaKey ||
      event.shiftKey ||
      event.altKey
    ) {
      return;
    }
    const id = this.target() || this.hrefFragment();
    if (!id) {
      return;
    }
    event.preventDefault();
    if (!isPlatformBrowser(this.platformId)) {
      return;
    }
    const target = this.document.getElementById(id);
    if (!target) {
      return;
    }
    const view = this.document.defaultView;
    const reduceMotion =
      view?.matchMedia('(prefers-reduced-motion: reduce)').matches ?? false;
    target.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'start' });
    if (this.focusTarget()) {
      target.focus({ preventScroll: true });
    }
    if (view) {
      // Keep the current path: a bare `#id` would resolve against
      // `<base href="/">` and rewrite the address bar to the app root.
      const { pathname, search } = view.location;
      view.history.replaceState(null, '', `${pathname}${search}#${id}`);
    }
  }

  private hrefFragment(): string {
    const href =
      (this.el.nativeElement as HTMLAnchorElement).getAttribute('href') ?? '';
    return href.startsWith('#') ? href.slice(1) : '';
  }
}
