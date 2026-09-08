import { isPlatformBrowser } from '@angular/common';
import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  inject,
  PLATFORM_ID,
  viewChild,
} from '@angular/core';
import { RouterLink } from '@angular/router';

/**
 * Public 404 page. Deliberately displays no part of the requested URL so that
 * sensitive query/path data can never leak through screenshots, and never
 * auto-redirects so users can distinguish a missing page from valid content.
 *
 * NOTE (P16-005 boundary): route title/meta + `noindex` handling is owned by
 * P16-005. This component intentionally performs no direct DOM meta hacks so
 * later tasks can introduce a single metadata primitive without conflicts.
 */
@Component({
  selector: 'app-not-found',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './not-found.component.html',
  styleUrl: './not-found.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NotFoundComponent implements AfterViewInit {
  private readonly platformId = inject(PLATFORM_ID);
  private readonly heading = viewChild<ElementRef<HTMLElement>>('heading');

  ngAfterViewInit(): void {
    if (isPlatformBrowser(this.platformId)) {
      this.heading()?.nativeElement.focus({ preventScroll: true });
    }
  }
}
