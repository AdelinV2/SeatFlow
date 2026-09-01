import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { EventDetail, EventSummary, PagedResult, VenueDetail } from '../models/event.model';
import { EventApiService } from './event-api.service';

describe('EventApiService', () => {
  let service: EventApiService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [EventApiService, provideHttpClient(), provideHttpClientTesting()],
    });

    service = TestBed.inject(EventApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('should get events without query parameters', () => {
    const mockResponse: PagedResult<EventSummary> = {
      content: [
        {
          id: 'ev-1',
          title: 'Concert A',
          category: 'CONCERT',
          bannerUrl: 'https://example.com/banner.jpg',
          eventDate: '2026-09-15T19:00:00Z',
          minPrice: 50,
          maxPrice: 150,
          currency: 'USD',
          status: 'PUBLISHED',
        },
      ],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      isFirst: true,
      isLast: true,
    };

    service.getEvents().subscribe((res) => {
      expect(res.content.length).toBe(1);
      expect(res.content[0].title).toBe('Concert A');
    });

    const req = httpMock.expectOne('/api/events');
    expect(req.request.method).toBe('GET');
    req.flush(mockResponse);
  });

  it('should pass category, search, and sort parameters to GET /api/events', () => {
    service
      .getEvents({ category: 'CONCERT', search: 'Rock', sort: 'eventDate,asc', page: 1, size: 10 })
      .subscribe();

    const req = httpMock.expectOne(
      (r) =>
        r.url === '/api/events' &&
        r.params.get('category') === 'CONCERT' &&
        r.params.get('search') === 'Rock' &&
        r.params.get('sort') === 'eventDate,asc' &&
        r.params.get('page') === '1' &&
        r.params.get('size') === '10',
    );
    expect(req.request.method).toBe('GET');
    req.flush({ content: [], page: 1, size: 10, totalElements: 0, totalPages: 0, isFirst: false, isLast: true });
  });

  it('should get single event detail by ID', () => {
    const mockDetail: EventDetail = {
      id: 'ev-123',
      venueId: 'v-456',
      title: 'Hamlet Theater Play',
      description: 'Classic Shakespeare production',
      category: 'THEATRE',
      bannerUrl: 'https://example.com/hamlet.jpg',
      eventDate: '2026-10-01T20:00:00Z',
      status: 'PUBLISHED',
      pricingTiers: [
        {
          id: 't-1',
          sectionId: 'sec-1',
          sectionName: 'VIP Box',
          categoryName: 'VIP',
          price: 120,
          currency: 'USD',
        },
      ],
      createdAt: '2026-08-01T12:00:00Z',
    };

    service.getEventById('ev-123').subscribe((res) => {
      expect(res.id).toBe('ev-123');
      expect(res.title).toBe('Hamlet Theater Play');
      expect(res.pricingTiers.length).toBe(1);
    });

    const req = httpMock.expectOne('/api/events/ev-123');
    expect(req.request.method).toBe('GET');
    req.flush(mockDetail);
  });

  it('should get venue details by venueId', () => {
    const mockVenue: VenueDetail = {
      id: 'v-456',
      name: 'National Grand Hall',
      address: '123 Boulevard St',
      city: 'Bucharest',
      country: 'Romania',
      capacity: 1200,
    };

    service.getVenueById('v-456').subscribe((res) => {
      expect(res.name).toBe('National Grand Hall');
      expect(res.city).toBe('Bucharest');
    });

    const req = httpMock.expectOne('/api/venues/v-456');
    expect(req.request.method).toBe('GET');
    req.flush(mockVenue);
  });

  it('should get event seat map by eventId', () => {
    service.getEventSeatMap('ev-123').subscribe((res) => {
      expect(res).toBeTruthy();
    });

    const req = httpMock.expectOne('/api/events/ev-123/seat-map');
    expect(req.request.method).toBe('GET');
    req.flush({ eventId: 'ev-123', sections: [] });
  });
});
