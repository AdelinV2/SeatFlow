import { isPlatformBrowser } from '@angular/common';
import {
  DestroyRef,
  Directive,
  ElementRef,
  inject,
  input,
  OnInit,
  PLATFORM_ID,
} from '@angular/core';

@Directive({
  selector: '[appScrollReveal]',
  standalone: true,
})
export class ScrollRevealDirective implements OnInit {
  private readonly el = inject(ElementRef);
  private readonly platformId = inject(PLATFORM_ID);
  private readonly destroyRef = inject(DestroyRef);

  readonly delay = input<number>(0);
  readonly threshold = input<number>(0.15);

  private observer?: IntersectionObserver;

  ngOnInit(): void {
    if (!isPlatformBrowser(this.platformId)) {
      return;
    }

    const element = this.el.nativeElement as HTMLElement;
    element.style.opacity = '0';
    element.style.transform = 'translateY(24px)';
    element.style.transition = `opacity 600ms cubic-bezier(0.16, 1, 0.3, 1) ${this.delay()}ms, transform 600ms cubic-bezier(0.16, 1, 0.3, 1) ${this.delay()}ms`;

    if ('IntersectionObserver' in window) {
      this.observer = new IntersectionObserver(
        (entries) => {
          entries.forEach((entry) => {
            if (entry.isIntersecting) {
              element.style.opacity = '1';
              element.style.transform = 'translateY(0)';
              this.observer?.unobserve(element);
            }
          });
        },
        { threshold: this.threshold() },
      );

      this.observer.observe(element);

      this.destroyRef.onDestroy(() => {
        this.observer?.disconnect();
        this.observer = undefined;
      });
    } else {
      element.style.opacity = '1';
      element.style.transform = 'translateY(0)';
    }
  }
}
