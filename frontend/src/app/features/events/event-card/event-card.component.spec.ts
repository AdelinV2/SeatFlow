import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { EventSummary } from '../../../models/event.model';
import { EventCardComponent } from './event-card.component';

describe('EventCardComponent', () => {
  let component: EventCardComponent;
  let fixture: ComponentFixture<EventCardComponent>;

  const mockEvent: EventSummary = {
    id: 'ev-1',
    title: 'Neon Horizon Live Tour',
    description: 'Electrifying electronic synthwave music show',
    category: 'CONCERT',
    bannerUrl: 'https://example.com/banner.jpg',
    eventDate: '2026-10-15T20:00:00Z',
    venueName: 'Metropolis Arena',
    minPrice: 50,
    maxPrice: 150,
    currency: 'USD',
    status: 'PUBLISHED',
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [EventCardComponent],
      providers: [provideRouter([])],
    }).compileComponents();

    fixture = TestBed.createComponent(EventCardComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('event', mockEvent);
    fixture.detectChanges();
  });

  it('should create the event card component', () => {
    expect(component).toBeTruthy();
  });

  it('should compute correct category badge classes for different categories', () => {
    expect(component.categoryBadgeClass()).toContain('border-violet-500');

    fixture.componentRef.setInput('event', { ...mockEvent, category: 'THEATRE' });
    fixture.detectChanges();
    expect(component.categoryBadgeClass()).toContain('border-amber-500');

    fixture.componentRef.setInput('event', { ...mockEvent, category: 'SPORTS' });
    fixture.detectChanges();
    expect(component.categoryBadgeClass()).toContain('border-emerald-500');

    fixture.componentRef.setInput('event', { ...mockEvent, category: 'FESTIVAL' });
    fixture.detectChanges();
    expect(component.categoryBadgeClass()).toContain('border-rose-500');

    fixture.componentRef.setInput('event', { ...mockEvent, category: 'COMEDY' });
    fixture.detectChanges();
    expect(component.categoryBadgeClass()).toContain('border-cyan-500');

    fixture.componentRef.setInput('event', { ...mockEvent, category: 'SYMPHONY' });
    fixture.detectChanges();
    expect(component.categoryBadgeClass()).toContain('border-indigo-500');

    fixture.componentRef.setInput('event', { ...mockEvent, category: 'OTHER' });
    fixture.detectChanges();
    expect(component.categoryBadgeClass()).toContain('border-slate-500');
  });

  it('should use bannerUrl when valid and fall back to defaultBanner on image error', () => {
    expect(component.displayBannerUrl()).toBe('https://example.com/banner.jpg');

    component.onImageError();
    fixture.detectChanges();
    expect(component.displayBannerUrl()).toBe(component.defaultBanner);
  });
});
