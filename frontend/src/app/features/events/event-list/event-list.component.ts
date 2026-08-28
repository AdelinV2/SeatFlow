import { CommonModule, isPlatformBrowser } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  OnInit,
  PLATFORM_ID,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { EventCategory, EventSummary } from '../../../models/event.model';
import { EventApiService } from '../../../services/event-api.service';
import { CurrencyFormatPipe } from '../../../shared/pipes/currency-format.pipe';
import { DateFormatPipe } from '../../../shared/pipes/date-format.pipe';
import { ScrollRevealDirective } from '../../../shared/directives/scroll-reveal.directive';
import { EventCalendarComponent } from '../event-calendar/event-calendar.component';
import { EventCardComponent } from '../event-card/event-card.component';
import { UpcomingEventsCarouselComponent } from '../upcoming-carousel/upcoming-carousel.component';

export interface CategoryOption {
  readonly value: EventCategory | 'ALL';
  readonly label: string;
  readonly icon?: string;
}

@Component({
  selector: 'app-event-list',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    EventCalendarComponent,
    EventCardComponent,
    UpcomingEventsCarouselComponent,
    ScrollRevealDirective,
    CurrencyFormatPipe,
    DateFormatPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './event-list.component.html',
  styleUrl: './event-list.component.scss',
})
export class EventListComponent implements OnInit {
  private readonly eventApiService = inject(EventApiService);
  private readonly platformId = inject(PLATFORM_ID);
  private readonly destroyRef = inject(DestroyRef);

  readonly events = signal<EventSummary[]>([]);
  readonly isLoading = signal<boolean>(true);
  readonly errorMessage = signal<string | null>(null);

  readonly selectedCategory = signal<EventCategory | 'ALL'>('ALL');
  readonly searchQuery = signal<string>('');
  readonly selectedCalendarDate = signal<Date | null>(null);
  readonly sortOption = signal<string>('eventDate');

  readonly activeHeroIndex = signal<number>(0);
  private heroTimerId?: ReturnType<typeof setInterval>;

  readonly categories: CategoryOption[] = [
    { value: 'ALL', label: 'All Events' },
    { value: 'CONCERT', label: 'Concerts' },
    { value: 'THEATRE', label: 'Theatre' },
    { value: 'SPORTS', label: 'Sports' },
    { value: 'FESTIVAL', label: 'Festivals' },
    { value: 'COMEDY', label: 'Comedy' },
    { value: 'SYMPHONY', label: 'Symphony' },
    { value: 'OTHER', label: 'Other' },
  ];

  readonly featuredEvents = computed(() => {
    return this.events().slice(0, 5);
  });

  readonly activeHeroEvent = computed<EventSummary | null>(() => {
    const list = this.featuredEvents();
    if (list.length === 0) return null;
    const index = this.activeHeroIndex() % list.length;
    return list[index] ?? null;
  });

  readonly filteredEvents = computed<EventSummary[]>(() => {
    let result = [...this.events()];

    // Category filter
    const cat = this.selectedCategory();
    if (cat !== 'ALL') {
      result = result.filter((e) => e.category === cat);
    }

    // Calendar date filter
    const calDate = this.selectedCalendarDate();
    if (calDate) {
      const calDateString = calDate.toDateString();
      result = result.filter((e) => {
        if (!e.eventDate) return false;
        const d = new Date(e.eventDate);
        return !isNaN(d.getTime()) && d.toDateString() === calDateString;
      });
    }

    // Text search query
    const query = this.searchQuery().trim().toLowerCase();
    if (query) {
      result = result.filter((e) => {
        const titleMatch = e.title?.toLowerCase().includes(query);
        const descMatch = e.description?.toLowerCase().includes(query);
        const venueMatch = e.venueName?.toLowerCase().includes(query);
        return titleMatch || descMatch || venueMatch;
      });
    }

    // Sorting
    const sort = this.sortOption();
    if (sort === 'eventDate') {
      result.sort((a, b) => new Date(a.eventDate).getTime() - new Date(b.eventDate).getTime());
    } else if (sort === 'title') {
      result.sort((a, b) => a.title.localeCompare(b.title));
    } else if (sort === 'priceLow') {
      result.sort((a, b) => a.minPrice - b.minPrice);
    } else if (sort === 'priceHigh') {
      result.sort((a, b) => (b.maxPrice || b.minPrice || 0) - (a.maxPrice || a.minPrice || 0));
    }

    return result;
  });

  readonly hasActiveFilters = computed<boolean>(() => {
    return (
      this.selectedCategory() !== 'ALL' ||
      this.selectedCalendarDate() !== null ||
      this.searchQuery().trim().length > 0
    );
  });

  ngOnInit(): void {
    this.loadEvents();
    this.startHeroAutoRotation();

    this.destroyRef.onDestroy(() => {
      this.clearHeroTimer();
    });
  }

  loadEvents(): void {
    this.isLoading.set(true);
    this.errorMessage.set(null);

    this.eventApiService.getEvents({ size: 100, sort: 'eventDate' }).subscribe({
      next: (page) => {
        this.events.set(page.content || []);
        this.isLoading.set(false);
      },
      error: (err) => {
        let msg = 'Failed to load events. Please ensure the backend services are running.';
        if (err?.error?.message && typeof err.error.message === 'string') {
          msg = err.error.message;
        } else if (err?.status === 0) {
          msg = 'Unable to connect to the SeatFlow API Gateway. Please check your connection.';
        } else if (
          err?.message &&
          !err.message.includes('<!doctype') &&
          !err.message.includes('is not valid JSON')
        ) {
          msg = err.message;
        }
        this.errorMessage.set(msg);
        this.isLoading.set(false);
      },
    });
  }

  setCategory(category: EventCategory | 'ALL'): void {
    this.selectedCategory.set(category);
  }

  onSearchInput(value: string): void {
    this.searchQuery.set(value);
  }

  onDateSelected(date: Date | null): void {
    this.selectedCalendarDate.set(date);
  }

  clearAllFilters(): void {
    this.selectedCategory.set('ALL');
    this.searchQuery.set('');
    this.selectedCalendarDate.set(null);
  }

  // Hero carousel navigation
  prevHero(): void {
    const len = this.featuredEvents().length;
    if (len <= 1) return;
    this.activeHeroIndex.update((i) => (i - 1 + len) % len);
    this.resetHeroTimer();
  }

  nextHero(): void {
    const len = this.featuredEvents().length;
    if (len <= 1) return;
    this.activeHeroIndex.update((i) => (i + 1) % len);
    this.resetHeroTimer();
  }

  setHeroIndex(index: number): void {
    this.activeHeroIndex.set(index);
    this.resetHeroTimer();
  }

  private startHeroAutoRotation(): void {
    if (!isPlatformBrowser(this.platformId)) return;
    this.clearHeroTimer();
    this.heroTimerId = setInterval(() => {
      const len = this.featuredEvents().length;
      if (len > 1) {
        this.activeHeroIndex.update((i) => (i + 1) % len);
      }
    }, 6000);
  }

  private resetHeroTimer(): void {
    this.startHeroAutoRotation();
  }

  private clearHeroTimer(): void {
    if (this.heroTimerId) {
      clearInterval(this.heroTimerId);
      this.heroTimerId = undefined;
    }
  }
}
