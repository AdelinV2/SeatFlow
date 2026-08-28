import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { NominatimGeocodingService } from './nominatim-geocoding.service';

describe('NominatimGeocodingService', () => {
  let service: NominatimGeocodingService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        NominatimGeocodingService,
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });

    service = TestBed.inject(NominatimGeocodingService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('should return empty list when searching with empty query', (done) => {
    service.searchAddress('   ').subscribe((res) => {
      expect(res).toEqual([]);
      done();
    });
    httpMock.expectNone('https://nominatim.openstreetmap.org/search');
  });

  it('should parse Nominatim search response and map full street address properly', () => {
    const rawNominatimData = [
      {
        place_id: 12345,
        display_name: 'Opera, 6, Piața Victoriei, Cetate, Timișoara, Timiș, 300030, România',
        lat: '45.7520162',
        lon: '21.2244805',
        address: {
          theatre: 'Opera',
          house_number: '6',
          road: 'Piața Victoriei',
          neighbourhood: 'Cetate',
          city: 'Timișoara',
          county: 'Timiș',
          postcode: '300030',
          country: 'România',
        },
      },
    ];

    service.searchAddress('Opera Timisoara').subscribe((results) => {
      expect(results.length).toBe(1);
      expect(results[0].placeId).toBe(12345);
      expect(results[0].street).toBe('Opera, Piața Victoriei 6, Cetate');
      expect(results[0].city).toBe('Timișoara');
      expect(results[0].country).toBe('România');
      expect(results[0].lat).toBeCloseTo(45.7520162);
      expect(results[0].lon).toBeCloseTo(21.2244805);
    });

    const req = httpMock.expectOne((r) =>
      r.url.startsWith('https://nominatim.openstreetmap.org/search')
    );
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('q')).toBe('Opera Timisoara');
    expect(req.request.params.get('format')).toBe('json');
    req.flush(rawNominatimData);
  });

  it('should reverse geocode coordinates and map result', () => {
    const rawReverse = {
      place_id: 67890,
      display_name: 'Piata Universitatii, Bucharest, Romania',
      lat: '44.4355',
      lon: '26.1025',
      address: {
        town: 'Bucharest',
        country: 'Romania',
      },
    };

    service.reverseGeocode(44.4355, 26.1025).subscribe((result) => {
      expect(result).toBeTruthy();
      expect(result?.displayName).toContain('Piata Universitatii');
      expect(result?.city).toBe('Bucharest');
    });

    const req = httpMock.expectOne((r) =>
      r.url.startsWith('https://nominatim.openstreetmap.org/reverse')
    );
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('lat')).toBe('44.4355');
    expect(req.request.params.get('lon')).toBe('26.1025');
    req.flush(rawReverse);
  });
});
