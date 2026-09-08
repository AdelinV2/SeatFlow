import { NgClass, isPlatformBrowser } from '@angular/common';
import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  input,
  PLATFORM_ID,
  viewChild,
} from '@angular/core';
import {
  ContentPageCalloutTone,
  ContentPageTocEntry,
} from './content-page.model';

/**
 * Converts an arbitrary label/id into a URL-safe anchor slug.
 * Exported for unit testing the uniqueness contract.
 */
export function slugifyContentAnchor(value: string): string {
  const slug = value
    .toLowerCase()
    .trim()
    .replace(/[^a-z0-9\s-]/g, '')
    .replace(/[\s_]+/g, '-')
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '');
  return slug || 'section';
}

/**
 * Deduplicates TOC anchor ids (`overview`, `overview-2`, `overview-3`, ...)
 * so projected sections can never produce duplicate fragment ids.
 * Exported for unit testing the uniqueness contract.
 */
export function toUniqueTocEntries(
  entries: readonly ContentPageTocEntry[],
): ContentPageTocEntry[] {
  const seen = new Map<string, number>();
  return entries.map((entry) => {
    const base = slugifyContentAnchor(entry.id || entry.label);
    const count = seen.get(base) ?? 0;
    seen.set(base, count + 1);
    return { ...entry, id: count === 0 ? base : `${base}-${count + 1}` };
  });
}

/**
 * Reusable shell for long-form public informational pages (legal, support,
 * status, API docs). Renders inside the global app shell, so the standard
 * header/footer remain visible. Intentionally free of any authenticated-user
 * dependency so signed-out visitors can always render it.
 *
 * Body content is projected (`<ng-content />`); there is no `[innerHTML]`
 * path and therefore no sanitization surface.
 *
 * On navigation the page title receives focus (same pattern as the 404 page)
 * so keyboard and screen-reader users land on the new page heading. SSR-safe:
 * focusing only happens in the browser.
 */
@Component({
  selector: 'app-content-page',
  standalone: true,
  imports: [NgClass],
  templateUrl: './content-page.component.html',
  styleUrl: './content-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ContentPageComponent implements AfterViewInit {
  private readonly platformId = inject(PLATFORM_ID);
  private readonly heading = viewChild<ElementRef<HTMLElement>>('heading');

  readonly eyebrow = input<string>('');
  readonly title = input.required<string>();
  readonly lead = input<string>('');
  readonly lastUpdated = input<string>('');
  readonly toc = input<readonly ContentPageTocEntry[]>([]);
  readonly calloutTitle = input<string>('');
  readonly calloutMessage = input<string>('');
  readonly calloutTone = input<ContentPageCalloutTone>('info');

  readonly normalizedToc = computed(() => toUniqueTocEntries(this.toc()));
  readonly hasToc = computed(() => this.normalizedToc().length > 0);
  readonly hasCallout = computed(() => this.calloutMessage().trim().length > 0);
  readonly calloutHeading = computed(() => {
    if (this.calloutTitle().trim()) {
      return this.calloutTitle().trim();
    }
    return this.calloutTone() === 'warning' ? 'Please note' : 'Note';
  });

  ngAfterViewInit(): void {
    if (isPlatformBrowser(this.platformId)) {
      this.heading()?.nativeElement.focus({ preventScroll: true });
    }
  }
}
