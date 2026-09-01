import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { VenueDetail } from '../models/event.model';
import { VenueApiService } from './venue-api.service';

describe('VenueApiService', () => {
  let service: VenueApiService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [VenueApiService, provideHttpClient(), provideHttpClientTesting()],
    });

    service = TestBed.inject(VenueApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('should fetch venue by ID', () => {
    const mockVenue: VenueDetail = {
      id: 'v-100',
      name: 'Grand Arena',
      address: '100 Main Blvd',
      city: 'Bucharest',
      country: 'Romania',
      capacity: 15000,
      latitude: 44.4323,
      longitude: 26.1063,
    };

    service.getVenueById('v-100').subscribe((venue) => {
      expect(venue.id).toBe('v-100');
      expect(venue.name).toBe('Grand Arena');
      expect(venue.capacity).toBe(15000);
    });

    const req = httpMock.expectOne('/api/venues/v-100');
    expect(req.request.method).toBe('GET');
    req.flush(mockVenue);
  });

  it('should fetch list of all venues', () => {
    const mockVenues: VenueDetail[] = [
      {
        id: 'v-1',
        name: 'Arena 1',
        address: 'Addr 1',
        city: 'City 1',
        country: 'Romania',
        capacity: 5000,
      },
      {
        id: 'v-2',
        name: 'Arena 2',
        address: 'Addr 2',
        city: 'City 2',
        country: 'Romania',
        capacity: 8000,
      },
    ];

    service.getVenues().subscribe((venues) => {
      expect(venues.length).toBe(2);
      expect(venues[0].id).toBe('v-1');
      expect(venues[1].id).toBe('v-2');
    });

    const req = httpMock.expectOne('/api/venues');
    expect(req.request.method).toBe('GET');
    req.flush(mockVenues);
  });
});
