import { ComponentFixture, TestBed } from '@angular/core/testing';
import { VenueMapViewComponent } from './venue-map-view.component';

describe('VenueMapViewComponent', () => {
  let component: VenueMapViewComponent;
  let fixture: ComponentFixture<VenueMapViewComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [VenueMapViewComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(VenueMapViewComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('venueName', 'National Arena');
    fixture.componentRef.setInput('venueAddress', 'Bulevardul Basarabia 37-39');
    fixture.componentRef.setInput('venueCity', 'Bucharest');
    fixture.componentRef.setInput('latitude', 44.4372);
    fixture.componentRef.setInput('longitude', 26.1525);
    fixture.componentRef.setInput('zoom', 16);
    fixture.detectChanges();
  });

  it('should create the venue map component', () => {
    expect(component).toBeTruthy();
  });

  it('should generate valid navigation URLs for Google Maps, Apple Maps, and Waze', () => {
    const googleUrl = component.googleMapsUrl();
    expect(googleUrl).toContain('google.com/maps/search');
    expect(googleUrl).toContain(encodeURIComponent('National Arena Bulevardul Basarabia 37-39 Bucharest'));

    const appleUrl = component.appleMapsUrl();
    expect(appleUrl).toContain('maps.apple.com');
    expect(appleUrl).toContain('44.4372,26.1525');

    const wazeUrl = component.wazeUrl();
    expect(wazeUrl).toContain('waze.com/ul');
    expect(wazeUrl).toContain('44.4372,26.1525');
  });

  it('should render map container element', () => {
    const container = component.mapContainer()?.nativeElement;
    expect(container).toBeTruthy();
  });

  it('should clean up map on destroy without throwing errors', () => {
    expect(() => fixture.destroy()).not.toThrow();
  });
});
