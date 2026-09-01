import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { EventSummary, PagedResult } from '../../../models/event.model';
import { EventApiService } from '../../../services/event-api.service';
import { EventListComponent } from './event-list.component';

describe('EventListComponent', () => {
  let component: EventListComponent;
  let fixture: ComponentFixture<EventListComponent>;
  let eventApiServiceSpy: jasmine.SpyObj<EventApiService>;

  const mockEvents: EventSummary[] = [
    {
      id: 'e1',
      title: 'Rock Legends Night',
      description: 'An epic rock concert',
      category: 'CONCERT',
      bannerUrl: 'https://example.com/e1.jpg',
      eventDate: new Date(2026, 8, 15, 14, 0, 0).toISOString(),
      venueName: 'Arena Hall',
      minPrice: 60,
      maxPrice: 180,
      currency: 'USD',
      status: 'PUBLISHED',
    },
    {
      id: 'e2',
      title: 'Hamlet Theater Play',
      description: 'Shakespeare classic drama',
      category: 'THEATRE',
      bannerUrl: 'https://example.com/e2.jpg',
      eventDate: new Date(2026, 8, 20, 19, 0, 0).toISOString(),
      venueName: 'City Theatre',
      minPrice: 40,
      maxPrice: 90,
      currency: 'USD',
      status: 'PUBLISHED',
    },
    {
      id: 'e3',
      title: 'Stand-up Comedy Live',
      description: 'Laughter all night long',
      category: 'COMEDY',
      bannerUrl: 'https://example.com/e3.jpg',
      eventDate: new Date(2026, 8, 15, 18, 0, 0).toISOString(),
      venueName: 'Comedy Club',
      minPrice: 25,
      maxPrice: 50,
      currency: 'USD',
      status: 'PUBLISHED',
    },
  ];

  const mockPagedResult: PagedResult<EventSummary> = {
    content: mockEvents,
    page: 0,
    size: 20,
    totalElements: 3,
    totalPages: 1,
    isFirst: true,
    isLast: true,
  };

  beforeEach(async () => {
    eventApiServiceSpy = jasmine.createSpyObj('EventApiService', ['getEvents']);
    eventApiServiceSpy.getEvents.and.returnValue(of(mockPagedResult));

    await TestBed.configureTestingModule({
      imports: [EventListComponent],
      providers: [
        { provide: EventApiService, useValue: eventApiServiceSpy },
        provideRouter([]),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(EventListComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create the event list component', () => {
    expect(component).toBeTruthy();
  });

  it('should load events on init', () => {
    expect(eventApiServiceSpy.getEvents).toHaveBeenCalled();
    expect(component.events().length).toBe(3);
    expect(component.isLoading()).toBeFalse();
  });

  it('should filter events by category', () => {
    component.setCategory('CONCERT');
    fixture.detectChanges();

    const filtered = component.filteredEvents();
    expect(filtered.length).toBe(1);
    expect(filtered[0].title).toBe('Rock Legends Night');
  });

  it('should filter events by search query in title and description', () => {
    component.onSearchInput('Shakespeare');
    fixture.detectChanges();

    const filtered = component.filteredEvents();
    expect(filtered.length).toBe(1);
    expect(filtered[0].title).toBe('Hamlet Theater Play');
  });

  it('should filter events by calendar date selection', () => {
    // Select Sep 15, 2026
    const filterDate = new Date(2026, 8, 15);
    component.onDateSelected(filterDate);
    fixture.detectChanges();

    const filtered = component.filteredEvents();
    expect(filtered.length).toBe(2);
    expect(filtered.map((e) => e.id)).toContain('e1');
    expect(filtered.map((e) => e.id)).toContain('e3');
  });

  it('should clear all active filters', () => {
    component.setCategory('THEATRE');
    component.onSearchInput('Hamlet');
    component.onDateSelected(new Date());

    expect(component.hasActiveFilters()).toBeTrue();

    component.clearAllFilters();

    expect(component.selectedCategory()).toBe('ALL');
    expect(component.searchQuery()).toBe('');
    expect(component.selectedCalendarDate()).toBeNull();
    expect(component.hasActiveFilters()).toBeFalse();
    expect(component.filteredEvents().length).toBe(3);
  });

  it('should navigate hero carousel slides', () => {
    expect(component.activeHeroIndex()).toBe(0);

    component.nextHero();
    expect(component.activeHeroIndex()).toBe(1);

    component.prevHero();
    expect(component.activeHeroIndex()).toBe(0);

    component.setHeroIndex(2);
    expect(component.activeHeroIndex()).toBe(2);
  });

  it('should handle API error gracefully', () => {
    eventApiServiceSpy.getEvents.and.returnValue(
      throwError(() => ({ error: { message: 'Server Unavailable' } })),
    );

    component.loadEvents();
    fixture.detectChanges();

    expect(component.errorMessage()).toBe('Server Unavailable');
    expect(component.isLoading()).toBeFalse();
  });
});
