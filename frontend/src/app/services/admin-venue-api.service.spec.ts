import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AdminVenueApiService } from './admin-venue-api.service';
import {
  CreateSectionRequest,
  CreateVenueRequest,
  UpdateVenueRequest,
  VenueLayout,
  VenueSectionLayout,
  VenueSectionSeat,
  VenueSummary,
} from '../models/venue.model';
import { PagedResult } from '../models/event.model';

describe('AdminVenueApiService', () => {
  let service: AdminVenueApiService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        AdminVenueApiService,
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });

    service = TestBed.inject(AdminVenueApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('should get venues list with query parameters', () => {
    const mockPagedResult: PagedResult<VenueSummary> = {
      content: [
        {
          id: 'v-1',
          name: 'Grand Theatre',
          address: 'Main Street 1',
          city: 'Bucharest',
          country: 'Romania',
          capacity: 1200,
        },
      ],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
      isFirst: true,
      isLast: true,
    };

    service.getVenues({ city: 'Bucharest', name: 'Grand', page: 0, size: 10 }).subscribe((res) => {
      expect(res.content.length).toBe(1);
      expect(res.content[0].name).toBe('Grand Theatre');
    });

    const req = httpMock.expectOne((r) => r.url === '/api/venues');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('city')).toBe('Bucharest');
    expect(req.request.params.get('name')).toBe('Grand');
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('size')).toBe('10');
    req.flush(mockPagedResult);
  });

  it('should fetch venue by ID', () => {
    const mockVenue: VenueSummary = {
      id: 'v-1',
      name: 'Grand Theatre',
      address: 'Main Street 1',
      city: 'Bucharest',
      country: 'Romania',
      capacity: 1200,
    };

    service.getVenueById('v-1').subscribe((venue) => {
      expect(venue.id).toBe('v-1');
      expect(venue.name).toBe('Grand Theatre');
    });

    const req = httpMock.expectOne('/api/venues/v-1');
    expect(req.request.method).toBe('GET');
    req.flush(mockVenue);
  });

  it('should get full venue layout', () => {
    const mockLayout: VenueLayout = {
      venueId: 'v-1',
      name: 'Grand Theatre',
      capacity: 1200,
      sections: [
        {
          sectionId: 'sec-1',
          name: 'Orchestra',
          rowCount: 2,
          colCount: 2,
          seats: [
            {
              seatId: 's-1',
              rowLabel: 'A',
              seatNumber: 1,
              gridX: 0,
              gridY: 0,
              isActive: true,
            },
          ],
        },
      ],
    };

    service.getVenueLayout('v-1').subscribe((layout) => {
      expect(layout.venueId).toBe('v-1');
      expect(layout.sections.length).toBe(1);
      expect(layout.sections[0].seats[0].rowLabel).toBe('A');
    });

    const req = httpMock.expectOne('/api/venues/v-1/layout');
    expect(req.request.method).toBe('GET');
    req.flush(mockLayout);
  });

  it('should create new venue via POST /api/admin/venues', () => {
    const createReq: CreateVenueRequest = {
      name: 'New Arena',
      address: 'Arena Blvd 5',
      city: 'Cluj-Napoca',
      capacity: 8000,
    };

    const mockResponse: VenueSummary = {
      id: 'v-new',
      ...createReq,
      country: 'Romania',
    };

    service.createVenue(createReq).subscribe((venue) => {
      expect(venue.id).toBe('v-new');
      expect(venue.name).toBe('New Arena');
    });

    const req = httpMock.expectOne('/api/admin/venues');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(createReq);
    req.flush(mockResponse);
  });

  it('should update existing venue via PUT /api/admin/venues/{id}', () => {
    const updateReq: UpdateVenueRequest = {
      name: 'Updated Arena',
      capacity: 9000,
    };

    const mockResponse: VenueSummary = {
      id: 'v-1',
      name: 'Updated Arena',
      address: 'Addr',
      city: 'City',
      country: 'Romania',
      capacity: 9000,
    };

    service.updateVenue('v-1', updateReq).subscribe((venue) => {
      expect(venue.name).toBe('Updated Arena');
      expect(venue.capacity).toBe(9000);
    });

    const req = httpMock.expectOne('/api/admin/venues/v-1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(updateReq);
    req.flush(mockResponse);
  });

  it('should create section via POST /api/admin/venues/{id}/sections', () => {
    const sectionReq: CreateSectionRequest = {
      name: 'Balcony',
      rowCount: 5,
      colCount: 10,
    };

    const mockResponse: VenueSectionLayout = {
      sectionId: 'sec-2',
      name: 'Balcony',
      rowCount: 5,
      colCount: 10,
      seats: [],
    };

    service.createSection('v-1', sectionReq).subscribe((sec) => {
      expect(sec.sectionId).toBe('sec-2');
      expect(sec.name).toBe('Balcony');
    });

    const req = httpMock.expectOne('/api/admin/venues/v-1/sections');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(sectionReq);
    req.flush(mockResponse);
  });

  it('should toggle seat active state via PATCH /api/admin/venues/{vId}/sections/{sId}/seats/{seatId}', () => {
    const mockUpdatedSeat: VenueSectionSeat = {
      seatId: 'seat-42',
      rowLabel: 'B',
      seatNumber: 4,
      gridX: 3,
      gridY: 1,
      isActive: false,
    };

    service.toggleSeat('v-1', 'sec-1', 'seat-42', false).subscribe((seat) => {
      expect(seat.isActive).toBeFalse();
    });

    const req = httpMock.expectOne('/api/admin/venues/v-1/sections/sec-1/seats/seat-42');
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ isActive: false });
    req.flush(mockUpdatedSeat);
  });

  it('should delete section via DELETE /api/admin/venues/{vId}/sections/{sId}', () => {
    service.deleteSection('v-1', 'sec-1').subscribe();

    const req = httpMock.expectOne('/api/admin/venues/v-1/sections/sec-1');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });
});
