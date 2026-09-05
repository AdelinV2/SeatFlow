import { ComponentFixture, TestBed } from '@angular/core/testing';
import { EventSummary } from '../../../models/event.model';
import { EventCalendarComponent } from './event-calendar.component';

describe('EventCalendarComponent', () => {
  let component: EventCalendarComponent;
  let fixture: ComponentFixture<EventCalendarComponent>;

  const mockEvents: EventSummary[] = [
    {
      id: 'e1',
      title: 'Rock Concert',
      category: 'CONCERT',
      bannerUrl: 'https://example.com/e1.jpg',
      eventDate: new Date(2026, 8, 15, 14, 0, 0).toISOString(),
      minPrice: 50,
      maxPrice: 100,
      currency: 'USD',
      status: 'PUBLISHED',
    },
    {
      id: 'e2',
      title: 'Comedy Night',
      category: 'COMEDY',
      bannerUrl: 'https://example.com/e2.jpg',
      eventDate: new Date(2026, 8, 15, 18, 0, 0).toISOString(),
      minPrice: 30,
      maxPrice: 60,
      currency: 'USD',
      status: 'PUBLISHED',
    },
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [EventCalendarComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(EventCalendarComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('events', mockEvents);
    fixture.detectChanges();
  });

  it('should create the calendar component with English weekday labels', () => {
    expect(component).toBeTruthy();
    expect(component.weekDayLabels).toEqual(['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN']);
  });

  it('should identify past days correctly', () => {
    const today = new Date();
    component.currentMonth.set(today);
    const days = component.calendarDays();

    const todayDay = days.find((d) => d.isCurrentMonth && d.dayNumber === today.getDate());
    expect(todayDay?.isToday).toBeTrue();
    expect(todayDay?.isPast).toBeFalse();

    if (today.getDate() > 1) {
      const yesterday = days.find((d) => d.isCurrentMonth && d.dayNumber === today.getDate() - 1);
      expect(yesterday?.isPast).toBeTrue();
      expect(yesterday?.isToday).toBeFalse();
    }
  });

  it('should display formatted month header', () => {
    component.currentMonth.set(new Date(2026, 8, 1)); // September 2026
    fixture.detectChanges();

    const header = component.formattedMonthHeader();
    expect(header.toLowerCase()).toContain('2026');
  });

  it('should navigate to previous and next month', () => {
    component.currentMonth.set(new Date(2026, 8, 1)); // September 2026

    component.nextMonth();
    expect(component.currentMonth().getMonth()).toBe(9); // October
    expect(component.currentMonth().getFullYear()).toBe(2026);

    component.prevMonth();
    expect(component.currentMonth().getMonth()).toBe(8); // September

    component.prevMonth();
    expect(component.currentMonth().getMonth()).toBe(7); // August
  });

  it('should calculate calendar grid days correctly', () => {
    component.currentMonth.set(new Date(2026, 8, 1)); // September 2026 (30 days)
    const days = component.calendarDays();

    expect(days.length).toBeGreaterThanOrEqual(35);
    expect(days.length % 7).toBe(0);

    const currentMonthDays = days.filter((d) => d.isCurrentMonth);
    expect(currentMonthDays.length).toBe(30);
  });

  it('should attach matching events to specific calendar days', () => {
    component.currentMonth.set(new Date(2026, 8, 1)); // September 2026
    const days = component.calendarDays();

    // Find September 15th
    const day15 = days.find((d) => d.isCurrentMonth && d.dayNumber === 15);
    expect(day15).toBeDefined();
    expect(day15?.events.length).toBe(2);
    expect(day15?.events[0].title).toBe('Rock Concert');
  });

  it('should emit dateSelected when selecting an unselected day', () => {
    let emittedDate: Date | null | undefined;
    component.dateSelected.subscribe((d) => (emittedDate = d));

    component.currentMonth.set(new Date(2026, 8, 1));
    const day = component.calendarDays().find((d) => d.isCurrentMonth && d.dayNumber === 15);
    expect(day).toBeDefined();

    component.selectDay(day!);
    expect(emittedDate).toEqual(day!.date);
  });

  it('should emit null (toggle deselect) when clicking an already selected day', () => {
    let emittedDate: Date | null | undefined = new Date();
    component.dateSelected.subscribe((d) => (emittedDate = d));

    const selectedDate = new Date(2026, 8, 15);
    fixture.componentRef.setInput('selectedDate', selectedDate);
    component.currentMonth.set(new Date(2026, 8, 1));

    const day = component.calendarDays().find((d) => d.isCurrentMonth && d.dayNumber === 15);
    expect(day?.isSelected).toBeTrue();

    component.selectDay(day!);
    expect(emittedDate).toBeNull();
  });

  it('should clear selection when clearSelection is called', () => {
    let emittedDate: Date | null | undefined = new Date();
    component.dateSelected.subscribe((d) => (emittedDate = d));

    component.clearSelection();
    expect(emittedDate).toBeNull();
  });

  it('should navigate to today when goToToday is called', () => {
    // Navigate away first
    component.currentMonth.set(new Date(2025, 0, 1));
    expect(component.isCurrentMonthToday()).toBeFalse();

    component.goToToday();
    const today = new Date();
    expect(component.currentMonth().getFullYear()).toBe(today.getFullYear());
    expect(component.currentMonth().getMonth()).toBe(today.getMonth());
    expect(component.isCurrentMonthToday()).toBeTrue();
  });

  it('should switch month when selecting a non-current month padding day', () => {
    component.currentMonth.set(new Date(2026, 8, 1)); // September 2026
    const days = component.calendarDays();
    const paddingDay = days.find((d) => !d.isCurrentMonth);
    expect(paddingDay).toBeDefined();

    component.selectDay(paddingDay!);
    expect(component.currentMonth().getMonth()).toBe(paddingDay!.date.getMonth());
  });

  it('should return correct category dot colors', () => {
    expect(component.getCategoryDotColor('CONCERT')).toBe('bg-purple-500');
    expect(component.getCategoryDotColor('THEATRE')).toBe('bg-amber-500');
    expect(component.getCategoryDotColor('SPORTS')).toBe('bg-emerald-500');
    expect(component.getCategoryDotColor('FESTIVAL')).toBe('bg-pink-500');
    expect(component.getCategoryDotColor('COMEDY')).toBe('bg-sky-500');
    expect(component.getCategoryDotColor('SYMPHONY')).toBe('bg-rose-500');
    expect(component.getCategoryDotColor('OTHER')).toBe('bg-indigo-500');
    expect(component.getCategoryDotColor(undefined)).toBe('bg-indigo-500');
  });

  it('should render stable fixed-width month header and SVG navigation buttons', () => {
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;

    const monthHeader = element.querySelector('h3');
    expect(monthHeader?.classList.contains('w-44')).toBeTrue();
    expect(monthHeader?.classList.contains('text-center')).toBeTrue();

    const prevBtn = element.querySelector('button[aria-label="Previous month"]');
    const nextBtn = element.querySelector('button[aria-label="Next month"]');
    expect(prevBtn?.querySelector('svg')).toBeTruthy();
    expect(nextBtn?.querySelector('svg')).toBeTruthy();
  });

  it('should render active filter chip with clear button when selectedDate is set', () => {
    fixture.componentRef.setInput('selectedDate', new Date(2026, 8, 15));
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    const clearBtn = element.querySelector('button[aria-label="Clear date filter"]');
    expect(clearBtn).toBeTruthy();
  });
});
