import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';
import { AdminEventListComponent } from './admin-event-list.component';
import { AdminEventApiService } from '../../../../services/admin-event-api.service';
import { AdminVenueApiService } from '../../../../services/admin-venue-api.service';
import { EventDetail, PagedResult } from '../../../../models/event.model';
import { VenueSummary } from '../../../../models/venue.model';

describe('AdminEventListComponent', () => {
  let component: AdminEventListComponent;
  let fixture: ComponentFixture<AdminEventListComponent>;
  let adminEventApi: jasmine.SpyObj<AdminEventApiService>;
  let adminVenueApi: jasmine.SpyObj<AdminVenueApiService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  const mockEvent1: EventDetail = {
    id: 'evt-1',
    venueId: 'ven-1',
    title: 'Neon Symphony',
    description: 'Electric Symphony Live',
    category: 'CONCERT',
    bannerUrl: 'https://example.com/banner1.jpg',
    eventDate: '2026-11-20T20:00:00Z',
    status: 'DRAFT',
    pricingTiers: [{ sectionId: 'sec-1', price: 50, currency: 'USD' }],
    createdAt: '2026-08-29T10:00:00Z',
  };

  const mockEvent2: EventDetail = {
    id: 'evt-2',
    venueId: 'ven-2',
    title: 'Hamlet Opera',
    description: 'Shakespeare Drama',
    category: 'THEATRE',
    bannerUrl: 'https://example.com/banner2.jpg',
    eventDate: '2026-12-05T19:00:00Z',
    status: 'PUBLISHED',
    pricingTiers: [{ sectionId: 'sec-2', price: 100, currency: 'USD' }],
    createdAt: '2026-08-29T10:00:00Z',
  };

  const mockVenue: VenueSummary = {
    id: 'ven-1',
    name: 'Grand Symphony Hall',
    address: '123 Music Ave',
    city: 'Vienna',
    country: 'Austria',
    capacity: 2000,
  };

  beforeEach(async () => {
    const eventApiSpy = jasmine.createSpyObj('AdminEventApiService', [
      'getAdminEvents',
      'updateEvent',
    ]);
    const venueApiSpy = jasmine.createSpyObj('AdminVenueApiService', ['getVenues']);
    const snackBarSpy = jasmine.createSpyObj('MatSnackBar', ['open']);

    const pagedEvents: PagedResult<EventDetail> = {
      content: [mockEvent1, mockEvent2],
      page: 0,
      size: 10,
      totalElements: 2,
      totalPages: 1,
      isFirst: true,
      isLast: true,
    };

    const pagedVenues: PagedResult<VenueSummary> = {
      content: [mockVenue],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
      isFirst: true,
      isLast: true,
    };

    eventApiSpy.getAdminEvents.and.returnValue(of(pagedEvents));
    venueApiSpy.getVenues.and.returnValue(of(pagedVenues));

    await TestBed.configureTestingModule({
      imports: [AdminEventListComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AdminEventApiService, useValue: eventApiSpy },
        { provide: AdminVenueApiService, useValue: venueApiSpy },
        { provide: MatSnackBar, useValue: snackBarSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AdminEventListComponent);
    component = fixture.componentInstance;
    adminEventApi = TestBed.inject(AdminEventApiService) as jasmine.SpyObj<AdminEventApiService>;
    adminVenueApi = TestBed.inject(AdminVenueApiService) as jasmine.SpyObj<AdminVenueApiService>;
    snackBar = TestBed.inject(MatSnackBar) as jasmine.SpyObj<MatSnackBar>;
    fixture.detectChanges();
  });

  it('should create and load events on init', () => {
    expect(component).toBeTruthy();
    expect(adminEventApi.getAdminEvents).toHaveBeenCalled();
    expect(adminVenueApi.getVenues).toHaveBeenCalled();
    expect(component.events().length).toBe(2);
    expect(component.isLoading()).toBeFalse();
  });

  it('should filter events by status', () => {
    component.selectedStatus.set('DRAFT');
    const filtered = component.filteredEvents();
    expect(filtered.length).toBe(1);
    expect(filtered[0].id).toBe('evt-1');
  });

  it('should filter events by search query', () => {
    component.searchQuery.set('Hamlet');
    const filtered = component.filteredEvents();
    expect(filtered.length).toBe(1);
    expect(filtered[0].title).toBe('Hamlet Opera');
  });

  it('should filter events by category', () => {
    component.selectedCategory.set('CONCERT');
    const filtered = component.filteredEvents();
    expect(filtered.length).toBe(1);
    expect(filtered[0].category).toBe('CONCERT');
  });

  it('should open publish modal and publish event', () => {
    adminEventApi.updateEvent.and.returnValue(of({ ...mockEvent1, status: 'PUBLISHED' }));

    component.openPublishModal(mockEvent1);
    expect(component.modalState()).toEqual({ type: 'PUBLISH', event: mockEvent1 });

    component.confirmAction();
    expect(adminEventApi.updateEvent).toHaveBeenCalledWith('evt-1', { status: 'PUBLISHED' });
    expect(snackBar.open).toHaveBeenCalled();
    expect(component.modalState()).toBeNull();
  });

  it('should warn when publishing draft without pricing tiers', () => {
    const unpricedEvent: EventDetail = {
      ...mockEvent1,
      pricingTiers: [],
    };

    component.openPublishModal(unpricedEvent);
    expect(snackBar.open).toHaveBeenCalledWith(
      'Configure section pricing before publishing this event.',
      'Close',
      jasmine.any(Object)
    );
    expect(component.modalState()).toBeNull();
  });

  it('should open cancel modal and cancel event', () => {
    adminEventApi.updateEvent.and.returnValue(of({ ...mockEvent2, status: 'CANCELLED' }));

    component.openCancelModal(mockEvent2);
    expect(component.modalState()).toEqual({ type: 'CANCEL', event: mockEvent2 });

    component.confirmAction();
    expect(adminEventApi.updateEvent).toHaveBeenCalledWith('evt-2', { status: 'CANCELLED' });
    expect(snackBar.open).toHaveBeenCalled();
  });

  it('should handle update error gracefully', () => {
    adminEventApi.updateEvent.and.returnValue(throwError(() => ({ error: { message: 'Transition error' } })));

    component.openCancelModal(mockEvent2);
    component.confirmAction();

    expect(snackBar.open).toHaveBeenCalledWith('Transition error', 'Close', jasmine.any(Object));
    expect(component.actionInProgressId()).toBeNull();
  });
});
