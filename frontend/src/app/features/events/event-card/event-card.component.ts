import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { EventCategory, EventSummary } from '../../../models/event.model';
import { CurrencyFormatPipe } from '../../../shared/pipes/currency-format.pipe';
import { DateFormatPipe } from '../../../shared/pipes/date-format.pipe';

@Component({
  selector: 'app-event-card',
  standalone: true,
  imports: [CommonModule, RouterLink, CurrencyFormatPipe, DateFormatPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './event-card.component.html',
  styleUrl: './event-card.component.scss',
})
export class EventCardComponent {
  readonly event = input.required<EventSummary>();

  readonly imageError = signal<boolean>(false);

  readonly defaultBanner = 'https://images.unsplash.com/photo-1501386761578-eac5c94b800a?auto=format&fit=crop&w=1200&q=80';

  readonly displayBannerUrl = computed(() => {
    if (this.imageError() || !this.event().bannerUrl) {
      return this.defaultBanner;
    }
    return this.event().bannerUrl;
  });

  readonly categoryBadgeClass = computed(() => {
    const cat = this.event().category;
    switch (cat) {
      case 'CONCERT':
        return 'border-violet-500/30 bg-violet-500/10 text-violet-600 dark:text-violet-400';
      case 'THEATRE':
        return 'border-amber-500/30 bg-amber-500/10 text-amber-600 dark:text-amber-400';
      case 'SPORTS':
        return 'border-emerald-500/30 bg-emerald-500/10 text-emerald-600 dark:text-emerald-400';
      case 'FESTIVAL':
        return 'border-rose-500/30 bg-rose-500/10 text-rose-600 dark:text-rose-400';
      case 'COMEDY':
        return 'border-cyan-500/30 bg-cyan-500/10 text-cyan-600 dark:text-cyan-400';
      case 'SYMPHONY':
        return 'border-indigo-500/30 bg-indigo-500/10 text-indigo-600 dark:text-indigo-400';
      default:
        return 'border-slate-500/30 bg-slate-500/10 text-slate-600 dark:text-slate-400';
    }
  });

  onImageError(): void {
    this.imageError.set(true);
  }
}
