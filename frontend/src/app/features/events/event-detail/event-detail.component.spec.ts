import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { EventDetail, VenueDetail } from '../../../models/event.model';
import { EventApiService } from '../../../services/event-api.service';
import { NominatimGeocodingService } from '../../../services/nominatim-geocoding.service';
import { VenueApiService } from '../../../services/venue-api.service';
import { EventDetailComponent } from './event-detail.component';

describe('EventDetailComponent', () => {
  let component: EventDetailComponent;
  let fixture: ComponentFixture<EventDetailComponent>;
  let eventApiServiceSpy: jasmine.SpyObj<EventApiService>;
  let geocodingServiceSpy: jasmine.SpyObj<NominatimGeocodingService>;
  let venueApiServiceSpy: jasmine.SpyObj<VenueApiService>;

  const mockEventDetail: EventDetail = {
    id: 'ev-999',
    venueId: 'v-100',
    title: 'Symphony No. 9 Live',
    description: 'Beethoven masterpiece performed live with choir.',
    category: 'SYMPHONY',
    bannerUrl: 'https://example.com/symphony.jpg',
    eventDate: '2026-11-15T19:30:00Z',
    status: 'PUBLISHED',
    pricingTiers: [
      {
        id: 't-1',
        sectionId: 'sec-1',
        categoryName: 'Standard',
        price: 45,
        currency: 'USD',
      },
      {
        id: 't-2',
        sectionId: 'sec-2',
        categoryName: 'Standard',
        price: 150,
        currency: 'USD',
      },
    ],
    createdAt: '2026-08-01T10:00:00Z',
  };

  const mockVenueDetail: VenueDetail = {
    id: 'v-100',
    name: 'Philharmonic Grand Hall',
    address: '45 Concert Blvd',
    city: 'Vienna',
    country: 'Austria',
    capacity: 2000,
    latitude: 48.2082,
    longitude: 16.3738,
    sections: [
      { id: 'sec-1', name: 'Balcony' },
      { id: 'sec-2', name: 'Orchestra Front' },
    ],
  };

  beforeEach(async () => {
    eventApiServiceSpy = jasmine.createSpyObj('EventApiService', ['getEventById']);
    geocodingServiceSpy = jasmine.createSpyObj('NominatimGeocodingService', [
      'searchAddress',
      'geocodeBestMatch',
    ]);
    venueApiServiceSpy = jasmine.createSpyObj('VenueApiService', ['getVenueById']);

    eventApiServiceSpy.getEventById.and.returnValue(of(mockEventDetail));
    geocodingServiceSpy.searchAddress.and.returnValue(of([]));
    geocodingServiceSpy.geocodeBestMatch.and.returnValue(of(null));
    venueApiServiceSpy.getVenueById.and.returnValue(of(mockVenueDetail));

    await TestBed.configureTestingModule({
      imports: [EventDetailComponent],
      providers: [
        { provide: EventApiService, useValue: eventApiServiceSpy },
        { provide: NominatimGeocodingService, useValue: geocodingServiceSpy },
        { provide: VenueApiService, useValue: venueApiServiceSpy },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: {
                get: (key: string) => (key === 'id' ? 'ev-999' : null),
              },
            },
          },
        },
        provideRouter([]),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(EventDetailComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('id', 'ev-999');
    fixture.detectChanges();
  });

  it('should create the event detail component', () => {
    expect(component).toBeTruthy();
  });

  it('should load event and enriched venue on init', () => {
    expect(eventApiServiceSpy.getEventById).toHaveBeenCalledWith('ev-999');
    expect(venueApiServiceSpy.getVenueById).toHaveBeenCalledWith('v-100');

    expect(component.event()).toEqual(mockEventDetail);
    expect(component.venue()).toEqual(mockVenueDetail);
    expect(component.isLoading()).toBeFalse();
  });

  it('should calculate min and max prices accurately', () => {
    expect(component.minPrice()).toBe(45);
    expect(component.maxPrice()).toBe(150);
    expect(component.currency()).toBe('USD');
  });

  it('should group identical tier names by their venue section', () => {
    expect(component.pricingSections().map((section) => section.name)).toEqual([
      'Balcony',
      'Orchestra Front',
    ]);
    expect(component.pricingSections().every((section) => section.tiers[0].categoryName === 'Standard')).toBeTrue();
  });

  it('should geocode older venues when stored coordinates are missing', () => {
    venueApiServiceSpy.getVenueById.and.returnValue(
      of({ ...mockVenueDetail, latitude: undefined, longitude: undefined }),
    );
    geocodingServiceSpy.geocodeBestMatch.and.returnValue(
      of({
        placeId: 123,
        displayName: 'Philharmonic Grand Hall, Vienna',
        street: '45 Concert Blvd',
        lat: 48.2082,
        lon: 16.3738,
      }),
    );

    component.loadEvent('ev-999');
    fixture.detectChanges();

    expect(geocodingServiceSpy.geocodeBestMatch).toHaveBeenCalled();
    expect(component.venueCoordinates()).toEqual({ lat: 48.2082, lng: 16.3738 });
  });

  it('should resolve venue coordinates', () => {
    const coords = component.venueCoordinates();
    expect(coords.lat).toBe(48.2082);
    expect(coords.lng).toBe(16.3738);
  });

  it('should handle 404 not found error', () => {
    eventApiServiceSpy.getEventById.and.returnValue(
      throwError(() => ({ status: 404, error: { message: 'Not Found' } })),
    );

    component.loadEvent('non-existent-id');
    fixture.detectChanges();

    expect(component.errorMessage()).toContain('could not be found');
    expect(component.isLoading()).toBeFalse();
  });
});
