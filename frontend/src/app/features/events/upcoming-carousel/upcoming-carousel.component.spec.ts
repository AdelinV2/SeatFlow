import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { EventSummary } from '../../../models/event.model';
import { UpcomingEventsCarouselComponent } from './upcoming-carousel.component';

describe('UpcomingEventsCarouselComponent', () => {
  let component: UpcomingEventsCarouselComponent;
  let fixture: ComponentFixture<UpcomingEventsCarouselComponent>;

  const mockEvents: EventSummary[] = [
    {
      id: 'e1',
      title: 'Neon Odyssey Tour',
      description: 'Epic concert',
      category: 'CONCERT',
      bannerUrl: 'https://example.com/e1.jpg',
      eventDate: '2026-09-18T20:00:00Z',
      minPrice: 65,
      maxPrice: 220,
      currency: 'USD',
      status: 'PUBLISHED',
    },
    {
      id: 'e2',
      title: 'Shakespeare Hamlet',
      description: 'Classic play',
      category: 'THEATRE',
      bannerUrl: 'https://example.com/e2.jpg',
      eventDate: '2026-09-24T19:30:00Z',
      minPrice: 45,
      maxPrice: 160,
      currency: 'USD',
      status: 'PUBLISHED',
    },
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [UpcomingEventsCarouselComponent],
      providers: [provideRouter([])],
    }).compileComponents();

    fixture = TestBed.createComponent(UpcomingEventsCarouselComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('events', mockEvents);
    fixture.detectChanges();
  });

  it('should create the upcoming carousel component', () => {
    expect(component).toBeTruthy();
    expect(component.displayEvents().length).toBe(2);
  });

  it('should advance and retreat slides on user actions and stop timer permanently', () => {
    expect(component.currentIndex()).toBe(0);
    expect(component.hasUserInteracted()).toBeFalse();

    component.onUserNext();
    expect(component.currentIndex()).toBe(1);
    expect(component.slideDirection()).toBe('next');
    expect(component.hasUserInteracted()).toBeTrue();

    component.onUserPrev();
    expect(component.currentIndex()).toBe(0);
    expect(component.slideDirection()).toBe('prev');
  });

  it('should jump to a specific slide on user selection', () => {
    component.onUserGoToSlide(1);
    expect(component.currentIndex()).toBe(1);
    expect(component.hasUserInteracted()).toBeTrue();
  });

  it('should pause on hover and resume on mouse leave', () => {
    expect(component.isHovered()).toBeFalse();

    component.onMouseEnter();
    expect(component.isHovered()).toBeTrue();

    component.onMouseLeave();
    expect(component.isHovered()).toBeFalse();
  });

  it('should open and close event details modal and stop timer permanently', () => {
    let emittedEvent: EventSummary | null | undefined;
    component.eventSelected.subscribe((e) => (emittedEvent = e));

    component.openEventDetails(mockEvents[0]);
    expect(component.selectedEvent()).toEqual(mockEvents[0]);
    expect(emittedEvent).toEqual(mockEvents[0]);
    expect(component.hasUserInteracted()).toBeTrue();

    component.closeDetailsModal();
    expect(component.selectedEvent()).toBeNull();
  });
});
