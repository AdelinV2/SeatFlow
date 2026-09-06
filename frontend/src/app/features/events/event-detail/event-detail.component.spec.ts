import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { EventDetail, EventSession, VenueDetail } from '../../../models/event.model';
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

  const mockSessions: EventSession[] = [
    {
      id: 'sess-1',
      eventId: 'ev-999',
      startsAt: '2026-11-15T19:30:00Z',
      endsAt: '2026-11-15T21:30:00Z',
      status: 'SCHEDULED',
      timezone: 'Europe/Vienna',
    },
    {
      id: 'sess-2',
      eventId: 'ev-999',
      startsAt: '2026-11-16T19:30:00Z',
      endsAt: '2026-11-16T21:30:00Z',
      status: 'SCHEDULED',
      timezone: 'Europe/Vienna',
    },
  ];

  const mockEventDetail: EventDetail = {
    id: 'ev-999',
    venueId: 'v-100',
    title: 'Symphony No. 9 Live',
    description: 'Beethoven masterpiece performed live with choir.',
    category: 'SYMPHONY',
    bannerUrl: 'https://example.com/symphony.jpg',
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
    sessions: mockSessions,
    createdAt: '2026-01-01T00:00:00Z',
  };

  const mockVenueDetail: VenueDetail = {
    id: 'v-100',
    name: 'Philharmonic Grand Hall',
    address: '45 Concert Blvd, Vienna, Austria',
    city: 'Vienna',
    country: 'Austria',
    capacity: 2500,
    sections: [
      { id: 'sec-1', name: 'Balcony', rowCount: 10, colCount: 20 },
      { id: 'sec-2', name: 'Orchestra Front', rowCount: 15, colCount: 30 },
    ],
    latitude: 48.2082,
    longitude: 16.3738,
  };

  beforeEach(async () => {
    eventApiServiceSpy = jasmine.createSpyObj('EventApiService', [
      'getEventById',
      'getEventSessions',
    ]);
    geocodingServiceSpy = jasmine.createSpyObj('NominatimGeocodingService', [
      'searchAddress',
      'geocodeBestMatch',
    ]);
    venueApiServiceSpy = jasmine.createSpyObj('VenueApiService', ['getVenueById']);

    eventApiServiceSpy.getEventById.and.returnValue(of(mockEventDetail));
    eventApiServiceSpy.getEventSessions.and.returnValue(of(mockSessions));
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
              queryParamMap: {
                get: (_key: string) => null,
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

  it('should load event, sessions, and enriched venue on init', () => {
    expect(eventApiServiceSpy.getEventById).toHaveBeenCalledWith('ev-999');
    expect(eventApiServiceSpy.getEventSessions).toHaveBeenCalledWith('ev-999');
    expect(venueApiServiceSpy.getVenueById).toHaveBeenCalledWith('v-100');

    expect(component.event()).toEqual(mockEventDetail);
    expect(component.venue()).toEqual(mockVenueDetail);
    expect(component.sessions().length).toBe(2);
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

  it('should allow selecting a showtime session and reflect in state', () => {
    expect(component.selectedSession()).toBeNull();
    component.onSessionSelected(mockSessions[0]);
    expect(component.selectedSession()?.id).toBe('sess-1');
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

  describe('deep-link sale-window guard (TASK-P12-006 REV-004)', () => {
    const upcomingSession: EventSession = {
      id: 'sess-upcoming',
      eventId: 'ev-999',
      startsAt: '2027-06-01T19:30:00Z',
      endsAt: '2027-06-01T21:30:00Z',
      saleStartsAt: '2027-01-01T00:00:00Z',
      status: 'SCHEDULED',
      timezone: 'Europe/Vienna',
    };
    const closedSession: EventSession = {
      id: 'sess-closed',
      eventId: 'ev-999',
      startsAt: '2027-06-01T19:30:00Z',
      endsAt: '2027-06-01T21:30:00Z',
      saleEndsAt: '2020-01-01T00:00:00Z',
      status: 'SCHEDULED',
      timezone: 'Europe/Vienna',
    };
    // Already started but not yet ended at test time (REV-004 FIX-2).
    const startedSession: EventSession = {
      id: 'sess-started',
      eventId: 'ev-999',
      startsAt: new Date(Date.now() - 30 * 60 * 1000).toISOString(),
      endsAt: new Date(Date.now() + 90 * 60 * 1000).toISOString(),
      status: 'SCHEDULED',
      timezone: 'Europe/Vienna',
    };

    function loadWithDeepLink(sessionId: string, sessions: EventSession[]): void {
      eventApiServiceSpy.getEventSessions.and.returnValue(of(sessions));
      const route = TestBed.inject(ActivatedRoute);
      spyOn(route.snapshot.queryParamMap, 'get').and.returnValue(sessionId);
      component.loadEvent('ev-999');
      fixture.detectChanges();
    }

    it('rejects a direct link to a sale-upcoming session with a warning', () => {
      loadWithDeepLink('sess-upcoming', [upcomingSession, ...mockSessions]);

      expect(component.selectedSession()).toBeNull();
      expect(component.sessionWarning()).toContain('not available');
    });

    it('rejects a direct link to a sale-closed session with a warning', () => {
      loadWithDeepLink('sess-closed', [closedSession, ...mockSessions]);

      expect(component.selectedSession()).toBeNull();
      expect(component.sessionWarning()).toContain('not available');
    });

    it('rejects a direct link to an already-started session with a warning', () => {
      loadWithDeepLink('sess-started', [startedSession, ...mockSessions]);

      expect(component.selectedSession()).toBeNull();
      expect(component.sessionWarning()).toContain('not available');
    });

    it('preserves a direct link to a bookable session', () => {
      loadWithDeepLink('sess-1', mockSessions);

      expect(component.selectedSession()?.id).toBe('sess-1');
      expect(component.sessionWarning()).toBeNull();
    });
  });
});
