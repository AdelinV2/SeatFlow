import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, ActivatedRoute, Router } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of } from 'rxjs';
import { AdminVenueEditorComponent } from './admin-venue-editor.component';
import { AdminVenueApiService } from '../../../../services/admin-venue-api.service';
import { NominatimGeocodingService } from '../../../../services/nominatim-geocoding.service';
import { VenueSummary } from '../../../../models/venue.model';

describe('AdminVenueEditorComponent', () => {
  let component: AdminVenueEditorComponent;
  let fixture: ComponentFixture<AdminVenueEditorComponent>;
  let venueApiSpy: jasmine.SpyObj<AdminVenueApiService>;
  let geocodingSpy: jasmine.SpyObj<NominatimGeocodingService>;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;
  let router: Router;

  const mockVenue: VenueSummary = {
    id: 'v-edit',
    name: 'Teatrul National',
    address: 'Bulevardul Nicolae Balcescu 2',
    city: 'Bucharest',
    country: 'Romania',
    capacity: 1200,
  };

  beforeEach(async () => {
    venueApiSpy = jasmine.createSpyObj('AdminVenueApiService', [
      'getVenueById',
      'createVenue',
      'updateVenue',
    ]);
    geocodingSpy = jasmine.createSpyObj('NominatimGeocodingService', [
      'searchAddress',
      'geocodeBestMatch',
      'reverseGeocode',
    ]);
    snackBarSpy = jasmine.createSpyObj('MatSnackBar', ['open']);

    venueApiSpy.getVenueById.and.returnValue(of(mockVenue));
    venueApiSpy.createVenue.and.returnValue(of(mockVenue));
    venueApiSpy.updateVenue.and.returnValue(of(mockVenue));
    geocodingSpy.searchAddress.and.returnValue(of([]));
    geocodingSpy.geocodeBestMatch.and.returnValue(of(null));
    geocodingSpy.reverseGeocode.and.returnValue(of(null));

    await TestBed.configureTestingModule({
      imports: [AdminVenueEditorComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AdminVenueApiService, useValue: venueApiSpy },
        { provide: NominatimGeocodingService, useValue: geocodingSpy },
        { provide: MatSnackBar, useValue: snackBarSpy },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: {
                get: (key: string) => (key === 'id' ? 'v-edit' : null),
              },
            },
          },
        },
      ],
    }).compileComponents();

    router = TestBed.inject(Router);
    spyOn(router, 'navigate');

    fixture = TestBed.createComponent(AdminVenueEditorComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create and load existing venue in edit mode', () => {
    expect(component).toBeTruthy();
    expect(component.isEditMode()).toBeTrue();
    expect(venueApiSpy.getVenueById).toHaveBeenCalledWith('v-edit');
    expect(component.venueForm.value.name).toBe('Teatrul National');
    expect(component.venueForm.value.capacity).toBe(1200);
  });

  it('should validate required form fields', () => {
    component.venueForm.patchValue({
      name: '',
      capacity: 0,
      address: '',
      city: '',
    });

    expect(component.venueForm.invalid).toBeTrue();
  });

  it('should submit update in edit mode', () => {
    component.venueForm.patchValue({
      name: 'Updated Teatru',
      capacity: 1500,
      address: 'Strada Noua 10',
      city: 'Bucharest',
      country: 'Romania',
      latitude: 44.43,
      longitude: 26.10,
    });

    component.onSubmit();

    expect(venueApiSpy.updateVenue).toHaveBeenCalled();
    expect(snackBarSpy.open).toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['/admin/venues']);
  });

  it('should apply selected address suggestion to form', () => {
    const suggestion = {
      placeId: 100,
      displayName: 'Ateneul Roman, Str. Benjamin Franklin 1, Bucharest, Romania',
      street: 'Ateneul Roman, Str. Benjamin Franklin 1',
      lat: 44.4413,
      lon: 26.0972,
      city: 'Bucharest',
      country: 'Romania',
    };

    component.selectAddressSuggestion(suggestion);

    expect(component.venueForm.value.address).toContain('Ateneul Roman');
    expect(component.venueForm.value.latitude).toBe(44.4413);
    expect(component.venueForm.value.longitude).toBe(26.0972);
    expect(component.searchSuggestions().length).toBe(0);
  });

  it('should update address, city, country and coordinates when pin moves on map', () => {
    geocodingSpy.reverseGeocode.and.returnValue(
      of({
        placeId: 200,
        displayName: 'Opera Timisoara, Piata Victoriei 6, Timisoara, Romania',
        street: 'Opera, Piata Victoriei 6',
        city: 'Timisoara',
        country: 'Romania',
        lat: 45.7520,
        lon: 21.2244,
      })
    );

    // Call private method directly for testing pin movement
    (component as unknown as { onLocationChanged: (lat: number, lng: number) => void }).onLocationChanged(45.7520, 21.2244);

    expect(component.venueForm.value.latitude).toBe(45.752);
    expect(component.venueForm.value.longitude).toBe(21.2244);
    expect(component.venueForm.value.address).toBe('Opera, Piata Victoriei 6');
    expect(component.venueForm.value.city).toBe('Timisoara');
    expect(component.venueForm.value.country).toBe('Romania');
  });
});
