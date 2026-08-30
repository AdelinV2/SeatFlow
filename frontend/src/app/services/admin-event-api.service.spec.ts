import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AdminEventApiService } from './admin-event-api.service';
import { EventDetail, PagedResult } from '../models/event.model';
import {
  ConfigurePricingRequest,
  CreateEventRequest,
  UpdateEventRequest,
} from '../models/admin-event.model';

describe('AdminEventApiService', () => {
  let service: AdminEventApiService;
  let httpMock: HttpTestingController;

  const mockEvent: EventDetail = {
    id: 'evt-123',
    venueId: 'venue-456',
    title: 'Neon Symphony Concert',
    description: 'A spectacular live concert',
    category: 'CONCERT',
    bannerUrl: 'https://example.com/banner.jpg',
    eventDate: '2026-11-20T20:00:00Z',
    status: 'DRAFT',
    pricingTiers: [],
    createdAt: '2026-08-29T10:00:00Z',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        AdminEventApiService,
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    service = TestBed.inject(AdminEventApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('should get admin events with query parameters', () => {
    const mockPagedResult: PagedResult<EventDetail> = {
      content: [mockEvent],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
      isFirst: true,
      isLast: true,
    };

    service.getAdminEvents({ status: 'DRAFT', search: 'Neon', page: 0, size: 10 }).subscribe((res) => {
      expect(res.content.length).toBe(1);
      expect(res.content[0].title).toBe('Neon Symphony Concert');
    });

    const req = httpMock.expectOne((r) => r.url === '/api/admin/events' && r.params.get('status') === 'DRAFT');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('search')).toBe('Neon');
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('size')).toBe('10');
    req.flush(mockPagedResult);
  });

  it('should get single event by id', () => {
    service.getEventById('evt-123').subscribe((event) => {
      expect(event.id).toBe('evt-123');
      expect(event.title).toBe('Neon Symphony Concert');
    });

    const req = httpMock.expectOne('/api/admin/events/evt-123');
    expect(req.request.method).toBe('GET');
    req.flush(mockEvent);
  });

  it('should create event via POST', () => {
    const createReq: CreateEventRequest = {
      title: 'Neon Symphony Concert',
      description: 'A spectacular live concert',
      category: 'CONCERT',
      bannerUrl: 'https://example.com/banner.jpg',
      eventDate: '2026-11-20T20:00:00Z',
      venueId: 'venue-456',
    };

    service.createEvent(createReq).subscribe((created) => {
      expect(created.id).toBe('evt-123');
      expect(created.status).toBe('DRAFT');
    });

    const req = httpMock.expectOne('/api/admin/events');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(createReq);
    req.flush(mockEvent);
  });

  it('should update event via PUT', () => {
    const updateReq: UpdateEventRequest = {
      title: 'Updated Symphony',
      status: 'PUBLISHED',
    };

    service.updateEvent('evt-123', updateReq).subscribe((updated) => {
      expect(updated.title).toBe('Updated Symphony');
    });

    const req = httpMock.expectOne('/api/admin/events/evt-123');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(updateReq);
    req.flush({ ...mockEvent, title: 'Updated Symphony', status: 'PUBLISHED' });
  });

  it('should configure pricing via POST', () => {
    const pricingReq: ConfigurePricingRequest = {
      pricingTiers: [
        { sectionId: 'sec-1', categoryName: 'Standard', price: 20.0, currency: 'USD' },
        { sectionId: 'sec-1', categoryName: 'Student', price: 15.0, currency: 'USD' },
        { sectionId: 'sec-2', categoryName: 'Standard', price: 20.0, currency: 'USD' },
      ],
    };

    service.configurePricing('evt-123', pricingReq).subscribe(() => {
      expect(true).toBeTrue();
    });

    const req = httpMock.expectOne('/api/admin/events/evt-123/pricing');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(pricingReq);
    req.flush([]);
  });
});
