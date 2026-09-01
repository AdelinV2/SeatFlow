import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AdminVenueListComponent } from './admin-venue-list.component';
import { AdminVenueApiService } from '../../../../services/admin-venue-api.service';
import { VenueSummary } from '../../../../models/venue.model';
import { PagedResult } from '../../../../models/event.model';

describe('AdminVenueListComponent', () => {
  let component: AdminVenueListComponent;
  let fixture: ComponentFixture<AdminVenueListComponent>;
  let venueApiSpy: jasmine.SpyObj<AdminVenueApiService>;

  const mockVenues: VenueSummary[] = [
    {
      id: 'v-1',
      name: 'Sala Palatului',
      address: 'Ion Campineanu 28',
      city: 'Bucharest',
      country: 'Romania',
      capacity: 4000,
      totalConfiguredSeats: 3950,
    },
    {
      id: 'v-2',
      name: 'BT Arena',
      address: 'Uzinei Electrice',
      city: 'Cluj-Napoca',
      country: 'Romania',
      capacity: 10000,
      totalConfiguredSeats: 9800,
    },
  ];

  const mockPaged: PagedResult<VenueSummary> = {
    content: mockVenues,
    page: 0,
    size: 50,
    totalElements: 2,
    totalPages: 1,
    isFirst: true,
    isLast: true,
  };

  beforeEach(async () => {
    venueApiSpy = jasmine.createSpyObj('AdminVenueApiService', ['getVenues']);
    venueApiSpy.getVenues.and.returnValue(of(mockPaged));

    await TestBed.configureTestingModule({
      imports: [AdminVenueListComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AdminVenueApiService, useValue: venueApiSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AdminVenueListComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create and load venues', () => {
    expect(component).toBeTruthy();
    expect(venueApiSpy.getVenues).toHaveBeenCalled();
    expect(component.venues().length).toBe(2);
    expect(component.isLoading()).toBeFalse();
  });

  it('should compute unique cities for filtering', () => {
    const cities = component.uniqueCities();
    expect(cities).toContain('ALL');
    expect(cities).toContain('Bucharest');
    expect(cities).toContain('Cluj-Napoca');
  });

  it('should filter venues by search query', () => {
    component.onSearchChange('Palatului');
    expect(component.filteredVenues().length).toBe(1);
    expect(component.filteredVenues()[0].name).toBe('Sala Palatului');

    component.onSearchChange('non-existent');
    expect(component.filteredVenues().length).toBe(0);
  });

  it('should filter venues by selected city', () => {
    component.onCitySelect('Cluj-Napoca');
    expect(component.filteredVenues().length).toBe(1);
    expect(component.filteredVenues()[0].name).toBe('BT Arena');

    component.onCitySelect('ALL');
    expect(component.filteredVenues().length).toBe(2);
  });
});
