import { CommonModule, isPlatformBrowser } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  OnInit,
  output,
  PLATFORM_ID,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { EventSummary } from '../../../models/event.model';
import { CurrencyFormatPipe } from '../../../shared/pipes/currency-format.pipe';
import { DateFormatPipe } from '../../../shared/pipes/date-format.pipe';

@Component({
  selector: 'app-upcoming-carousel',
  standalone: true,
  imports: [CommonModule, RouterLink, CurrencyFormatPipe, DateFormatPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './upcoming-carousel.component.html',
  styleUrl: './upcoming-carousel.component.scss',
})
export class UpcomingEventsCarouselComponent implements OnInit {
  private readonly platformId = inject(PLATFORM_ID);
  private readonly destroyRef = inject(DestroyRef);

  readonly events = input<EventSummary[]>([]);
  readonly eventSelected = output<EventSummary>();

  readonly currentIndex = signal<number>(0);
  readonly isHovered = signal<boolean>(false);
  readonly hasUserInteracted = signal<boolean>(false);
  readonly slideDirection = signal<'next' | 'prev'>('next');
  readonly selectedEvent = signal<EventSummary | null>(null);

  private readonly intervalDurationMs = 6000;
  private autoScrollTimerId?: ReturnType<typeof setInterval>;

  // Fallback high-res events if database is empty during dev/demo
  readonly fallbackEvents: EventSummary[] = [
    {
      id: 'featured-1',
      title: 'Neon Odyssey World Tour 2026',
      description: 'The definitive electronic music visual experience with laser projection and surround sound.',
      category: 'CONCERT',
      bannerUrl: 'https://images.unsplash.com/photo-1514525253161-7a46d19cd819?auto=format&fit=crop&w=1600&q=80',
      eventDate: '2026-09-18T20:00:00Z',
      venueName: 'Grand Arena',
      minPrice: 65,
      maxPrice: 220,
      currency: 'USD',
      status: 'PUBLISHED',
    },
    {
      id: 'featured-2',
      title: 'Hamlet — Royal Shakespeare Co.',
      description: 'A breathtaking modern adaptation of Shakespeare’s magnum opus with international cast.',
      category: 'THEATRE',
      bannerUrl: 'https://images.unsplash.com/photo-1507676184212-d03ab07a01bf?auto=format&fit=crop&w=1600&q=80',
      eventDate: '2026-09-24T19:30:00Z',
      venueName: 'National Opera Hall',
      minPrice: 45,
      maxPrice: 160,
      currency: 'USD',
      status: 'PUBLISHED',
    },
    {
      id: 'featured-3',
      title: 'Beethoven: Symphony No. 9 Live',
      description: 'Full 100-piece philharmonic orchestra and 80-voice choir performing the Ode to Joy.',
      category: 'SYMPHONY',
      bannerUrl: 'https://images.unsplash.com/photo-1465847899084-d164df4dedc6?auto=format&fit=crop&w=1600&q=80',
      eventDate: '2026-10-02T19:00:00Z',
      venueName: 'Symphony Hall',
      minPrice: 50,
      maxPrice: 190,
      currency: 'USD',
      status: 'PUBLISHED',
    },
    {
      id: 'featured-4',
      title: 'International Stand-Up All-Stars',
      description: 'Top-tier comedians from London, New York and Berlin performing an uncensored comedy special.',
      category: 'COMEDY',
      bannerUrl: 'https://images.unsplash.com/photo-1585699324551-f6c309eedeca?auto=format&fit=crop&w=1600&q=80',
      eventDate: '2026-10-10T21:00:00Z',
      venueName: 'Comedy Underground',
      minPrice: 35,
      maxPrice: 85,
      currency: 'USD',
      status: 'PUBLISHED',
    },
  ];

  readonly displayEvents = computed<EventSummary[]>(() => {
    const list = this.events();
    return list.length > 0 ? list.slice(0, 6) : this.fallbackEvents;
  });

  readonly currentEvent = computed<EventSummary | null>(() => {
    const list = this.displayEvents();
    if (list.length === 0) return null;
    const idx = this.currentIndex() % list.length;
    return list[idx] ?? null;
  });

  constructor() {
    effect(() => {
      const len = this.displayEvents().length;
      if (len > 0 && this.currentIndex() >= len) {
        this.currentIndex.set(0);
      }
    });
  }

  ngOnInit(): void {
    this.startAutoScroll();

    this.destroyRef.onDestroy(() => {
      this.clearAutoScroll();
    });
  }

  startAutoScroll(): void {
    if (!isPlatformBrowser(this.platformId)) return;
    this.clearAutoScroll();

    this.autoScrollTimerId = setInterval(() => {
      if (!this.isHovered() && !this.hasUserInteracted() && !this.selectedEvent()) {
        this.slideDirection.set('next');
        this.advanceNextSlide();
      }
    }, this.intervalDurationMs);
  }

  onUserNext(): void {
    this.stopAutoScrollPermanently();
    this.slideDirection.set('next');
    this.advanceNextSlide();
  }

  onUserPrev(): void {
    this.stopAutoScrollPermanently();
    this.slideDirection.set('prev');
    this.retreatPrevSlide();
  }

  onUserGoToSlide(index: number): void {
    this.stopAutoScrollPermanently();
    this.slideDirection.set(index >= this.currentIndex() ? 'next' : 'prev');
    this.currentIndex.set(index);
  }

  onMouseEnter(): void {
    this.isHovered.set(true);
  }

  onMouseLeave(): void {
    this.isHovered.set(false);
  }

  openEventDetails(event: EventSummary): void {
    this.stopAutoScrollPermanently();
    this.selectedEvent.set(event);
    this.eventSelected.emit(event);
  }

  closeDetailsModal(): void {
    this.selectedEvent.set(null);
  }

  stopAutoScrollPermanently(): void {
    this.hasUserInteracted.set(true);
    this.clearAutoScroll();
  }

  private advanceNextSlide(): void {
    const len = this.displayEvents().length;
    if (len <= 1) return;
    this.currentIndex.update((i) => (i + 1) % len);
  }

  private retreatPrevSlide(): void {
    const len = this.displayEvents().length;
    if (len <= 1) return;
    this.currentIndex.update((i) => (i - 1 + len) % len);
  }

  private clearAutoScroll(): void {
    if (this.autoScrollTimerId) {
      clearInterval(this.autoScrollTimerId);
      this.autoScrollTimerId = undefined;
    }
  }
}
