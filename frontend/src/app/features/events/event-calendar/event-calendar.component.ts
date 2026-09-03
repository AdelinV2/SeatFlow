import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
  signal,
} from '@angular/core';
import { CalendarDay, EventCategory, EventSummary } from '../../../models/event.model';
import { DateFormatPipe } from '../../../shared/pipes/date-format.pipe';

@Component({
  selector: 'app-event-calendar',
  standalone: true,
  imports: [CommonModule, DateFormatPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './event-calendar.component.html',
  styleUrl: './event-calendar.component.scss',
})
export class EventCalendarComponent {
  readonly events = input<EventSummary[]>([]);
  readonly selectedDate = input<Date | null>(null);
  readonly dateSelected = output<Date | null>();

  readonly currentMonth = signal<Date>(new Date());
  readonly weekDayLabels = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];

  readonly formattedMonthHeader = computed(() => {
    const d = this.currentMonth();
    return d.toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
  });

  readonly isCurrentMonthToday = computed(() => {
    const today = new Date();
    const cur = this.currentMonth();
    return today.getFullYear() === cur.getFullYear() && today.getMonth() === cur.getMonth();
  });

  readonly calendarDays = computed<CalendarDay[]>(() => {
    const month = this.currentMonth();
    const year = month.getFullYear();
    const monthIndex = month.getMonth();

    const firstDayOfMonth = new Date(year, monthIndex, 1);
    const lastDayOfMonth = new Date(year, monthIndex + 1, 0);

    // Monday-based week index (0 = Monday, 6 = Sunday)
    let startDay = firstDayOfMonth.getDay() - 1;
    if (startDay === -1) {
      startDay = 6;
    }

    const days: CalendarDay[] = [];
    const today = new Date();
    const eventList = this.events();
    const selected = this.selectedDate();

    // Previous month padding
    const prevMonthLastDay = new Date(year, monthIndex, 0).getDate();
    for (let i = startDay - 1; i >= 0; i--) {
      const date = new Date(year, monthIndex - 1, prevMonthLastDay - i);
      days.push(this.createCalendarDay(date, false, today, selected, eventList));
    }

    // Current month days
    for (let i = 1; i <= lastDayOfMonth.getDate(); i++) {
      const date = new Date(year, monthIndex, i);
      days.push(this.createCalendarDay(date, true, today, selected, eventList));
    }

    // Next month padding to fill complete weeks
    const remaining = (7 - (days.length % 7)) % 7;
    for (let i = 1; i <= remaining; i++) {
      const date = new Date(year, monthIndex + 1, i);
      days.push(this.createCalendarDay(date, false, today, selected, eventList));
    }

    return days;
  });

  private createCalendarDay(
    date: Date,
    isCurrentMonth: boolean,
    today: Date,
    selected: Date | null,
    events: EventSummary[],
  ): CalendarDay {
    const startOfToday = new Date(today.getFullYear(), today.getMonth(), today.getDate());
    const startOfDate = new Date(date.getFullYear(), date.getMonth(), date.getDate());
    const isToday = startOfDate.getTime() === startOfToday.getTime();
    const isPast = startOfDate.getTime() < startOfToday.getTime();
    const isSelected = selected ? date.toDateString() === selected.toDateString() : false;

    const dayEvents = events.filter((e) => {
      if (!e.eventDate) return false;
      const eventDate = new Date(e.eventDate);
      return !isNaN(eventDate.getTime()) && eventDate.toDateString() === date.toDateString();
    });

    return {
      date,
      dayNumber: date.getDate(),
      isCurrentMonth,
      isToday,
      isPast,
      isSelected,
      events: dayEvents,
    };
  }

  prevMonth(): void {
    this.currentMonth.update((d) => new Date(d.getFullYear(), d.getMonth() - 1, 1));
  }

  nextMonth(): void {
    this.currentMonth.update((d) => new Date(d.getFullYear(), d.getMonth() + 1, 1));
  }

  goToToday(): void {
    this.currentMonth.set(new Date());
  }

  selectDay(day: CalendarDay): void {
    if (!day.isCurrentMonth) {
      this.currentMonth.set(new Date(day.date.getFullYear(), day.date.getMonth(), 1));
    }
    if (day.isSelected) {
      this.dateSelected.emit(null); // Deselect on second click
    } else {
      this.dateSelected.emit(day.date);
    }
  }

  clearSelection(): void {
    this.dateSelected.emit(null);
  }

  getCategoryDotColor(category?: EventCategory | string): string {
    switch (category) {
      case 'CONCERT':
        return 'bg-purple-500';
      case 'THEATRE':
        return 'bg-amber-500';
      case 'SPORTS':
        return 'bg-emerald-500';
      case 'FESTIVAL':
        return 'bg-pink-500';
      case 'COMEDY':
        return 'bg-sky-500';
      case 'SYMPHONY':
        return 'bg-rose-500';
      default:
        return 'bg-indigo-500';
    }
  }
}
