import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';
import { AdminPricingManagerComponent } from './admin-pricing-manager.component';
import { AdminEventApiService } from '../../../../services/admin-event-api.service';
import { AdminVenueApiService } from '../../../../services/admin-venue-api.service';
import { EventDetail } from '../../../../models/event.model';
import { VenueLayout } from '../../../../models/venue.model';

describe('AdminPricingManagerComponent', () => {
  let component: AdminPricingManagerComponent;
  let fixture: ComponentFixture<AdminPricingManagerComponent>;
  let adminEventApi: jasmine.SpyObj<AdminEventApiService>;
  let adminVenueApi: jasmine.SpyObj<AdminVenueApiService>;
  let router: Router;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  const mockEvent: EventDetail = {
    id: 'evt-123',
    venueId: 'ven-1',
    title: 'Neon Symphony',
    description: 'Concert',
    category: 'CONCERT',
    bannerUrl: 'https://example.com/banner.jpg',
    eventDate: '2026-11-20T20:00:00Z',
    status: 'DRAFT',
    pricingTiers: [
      { sectionId: 'sec-1', categoryName: 'Standard', price: 20.0, currency: 'USD' },
      { sectionId: 'sec-1', categoryName: 'Student', price: 12.0, currency: 'USD' },
    ],
    createdAt: '2026-08-29T10:00:00Z',
  };

  const mockVenueLayout: VenueLayout = {
    venueId: 'ven-1',
    name: 'Grand Concert Hall',
    capacity: 1000,
    sections: [
      {
        sectionId: 'sec-1',
        name: 'Orchestra Main',
        rowCount: 10,
        colCount: 20,
        seats: [],
      },
      {
        sectionId: 'sec-2',
        name: 'Balcony Tier',
        rowCount: 5,
        colCount: 15,
        seats: [],
      },
    ],
  };

  beforeEach(async () => {
    const eventApiSpy = jasmine.createSpyObj('AdminEventApiService', [
      'getEventById',
      'configurePricing',
      'updateEvent',
    ]);
    const venueApiSpy = jasmine.createSpyObj('AdminVenueApiService', ['getVenueLayout']);
    const snackBarSpy = jasmine.createSpyObj('MatSnackBar', ['open']);

    eventApiSpy.getEventById.and.returnValue(of(mockEvent));
    eventApiSpy.configurePricing.and.returnValue(of(null as any));
    eventApiSpy.updateEvent.and.returnValue(of({ ...mockEvent, status: 'PUBLISHED' }));
    venueApiSpy.getVenueLayout.and.returnValue(of(mockVenueLayout));

    await TestBed.configureTestingModule({
      imports: [AdminPricingManagerComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AdminEventApiService, useValue: eventApiSpy },
        { provide: AdminVenueApiService, useValue: venueApiSpy },
        { provide: MatSnackBar, useValue: snackBarSpy },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: {
                get: (key: string) => (key === 'id' ? 'evt-123' : null),
              },
            },
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AdminPricingManagerComponent);
    component = fixture.componentInstance;
    adminEventApi = TestBed.inject(AdminEventApiService) as jasmine.SpyObj<AdminEventApiService>;
    adminVenueApi = TestBed.inject(AdminVenueApiService) as jasmine.SpyObj<AdminVenueApiService>;
    router = TestBed.inject(Router);
    snackBar = TestBed.inject(MatSnackBar) as jasmine.SpyObj<MatSnackBar>;
    spyOn(router, 'navigate');
    fixture.detectChanges();
  });

  it('should create and load event and venue layout with $20 default', () => {
    expect(component).toBeTruthy();
    expect(adminEventApi.getEventById).toHaveBeenCalledWith('evt-123');
    expect(adminVenueApi.getVenueLayout).toHaveBeenCalledWith('ven-1');
    expect(component.sections().length).toBe(2);
    expect(component.isLoading()).toBeFalse();
  });

  it('should calculate multi-tier price metrics correctly', () => {
    const stats = component.priceStats();
    expect(stats.min).toBe(12); // sec-1 Student is 12
    expect(stats.max).toBe(20); // sec-1 Standard & sec-2 default 20
    expect(stats.tierCount).toBe(3); // 2 in sec-1, 1 in sec-2
  });

  it('should add customer category tier to section', () => {
    const pensionerTpl = component.categoryTemplates.find((t) => t.name === 'Pensioner')!;
    component.addTierToSection('sec-1', pensionerTpl);

    const sec1 = component.sections().find((s) => s.sectionId === 'sec-1')!;
    expect(sec1.tiers.some((t) => t.categoryName === 'Pensioner' && t.price === 15.0)).toBeTrue();
  });

  it('should prevent the same customer category from being added twice to a section', () => {
    const studentTpl = component.categoryTemplates.find((t) => t.name === 'Student')!;

    component.addTierToSection('sec-2', studentTpl);
    component.addTierToSection('sec-2', studentTpl);

    const sec2 = component.sections().find((s) => s.sectionId === 'sec-2')!;
    expect(sec2.tiers.filter((t) => t.categoryName === 'Student')).toHaveSize(1);
    expect(component.hasTemplateTier('sec-2', studentTpl)).toBeTrue();
  });

  it('should make a category action available again after its tier is removed', () => {
    const studentTpl = component.categoryTemplates.find((t) => t.name === 'Student')!;

    component.addTierToSection('sec-2', studentTpl);
    const studentTier = component.sections().find((s) => s.sectionId === 'sec-2')!.tiers.find((t) => t.categoryName === 'Student')!;
    expect(component.hasTemplateTier('sec-2', studentTpl)).toBeTrue();

    component.removeTierFromSection('sec-2', studentTier.id);

    expect(component.hasTemplateTier('sec-2', studentTpl)).toBeFalse();
    component.addTierToSection('sec-2', studentTpl);
    expect(component.hasTemplateTier('sec-2', studentTpl)).toBeTrue();
  });

  it('should disable a section category button after clicking it and re-enable it after removal', () => {
    const addStudentButton = fixture.nativeElement.querySelector('button[aria-label="Add Student tier"]') as HTMLButtonElement;
    expect(addStudentButton).toBeTruthy();
    expect(addStudentButton.disabled).toBeFalse();

    addStudentButton.click();
    fixture.detectChanges();

    const sec2 = component.sections().find((s) => s.sectionId === 'sec-2')!;
    const studentTier = sec2.tiers.find((t) => t.categoryName === 'Student')!;
    const disabledStudentButtons = fixture.nativeElement.querySelectorAll('button[aria-label="Student tier already added"]');
    expect(studentTier).toBeTruthy();
    expect(disabledStudentButtons.length).toBe(2);
    expect((disabledStudentButtons[1] as HTMLButtonElement).disabled).toBeTrue();

    component.removeTierFromSection('sec-2', studentTier.id);
    fixture.detectChanges();

    const availableButton = fixture.nativeElement.querySelector('button[aria-label="Add Student tier"]') as HTMLButtonElement;
    expect(availableButton).toBeTruthy();
    expect(availableButton.disabled).toBeFalse();
  });

  it('should apply uniform standard price to all sections', () => {
    component.uniformPriceInput.set(25);
    component.applyUniformStandardPrice();

    const sections = component.sections();
    expect(sections.every((s) => s.tiers.some((t) => t.categoryName === 'Standard' && t.price === 25))).toBeTrue();
    expect(snackBar.open).toHaveBeenCalledWith('Applied USD 25.00 standard price across all sections.', 'Close', jasmine.any(Object));
  });

  it('should submit the selected currency for every pricing tier', () => {
    component.currency.set('EUR');

    component.savePricing(false);

    const pricingTiers = adminEventApi.configurePricing.calls.mostRecent().args[1].pricingTiers;
    expect(pricingTiers.every((tier) => tier.currency === 'EUR')).toBeTrue();
  });

  it('should add template tier to all sections', () => {
    const studentTpl = component.categoryTemplates.find((t) => t.name === 'Student')!;
    component.addTemplateToAllSections(studentTpl);

    const sections = component.sections();
    expect(sections.every((s) => s.tiers.some((t) => t.categoryName === 'Student' && t.price === 12))).toBeTrue();
  });

  it('should add a bulk customer category only to sections that do not have it', () => {
    const studentTpl = component.categoryTemplates.find((t) => t.name === 'Student')!;

    component.addTemplateToAllSections(studentTpl);
    component.addTemplateToAllSections(studentTpl);

    const studentTierCounts = component.sections().map((section) => section.tiers.filter((tier) => tier.categoryName === 'Student').length);
    expect(studentTierCounts).toEqual([1, 1]);
    expect(component.allSectionsHaveTemplate(studentTpl)).toBeTrue();
  });

  it('should use the requested Student and Pensioner defaults', () => {
    expect(component.categoryTemplates.find((t) => t.name === 'Student')?.defaultPrice).toBe(12);
    expect(component.categoryTemplates.find((t) => t.name === 'Pensioner')?.defaultPrice).toBe(15);
  });

  it('should save pricing matrix with multi-customer tiers', () => {
    component.savePricing(false);

    expect(adminEventApi.configurePricing).toHaveBeenCalled();
    const callArgs = adminEventApi.configurePricing.calls.mostRecent().args;
    expect(callArgs[0]).toBe('evt-123');
    expect(callArgs[1].pricingTiers.length).toBe(3);
    expect(callArgs[1].pricingTiers[0].categoryName).toBe('Standard');
    expect(snackBar.open).toHaveBeenCalledWith(
      'Section pricing matrix updated successfully!',
      'Close',
      jasmine.any(Object)
    );
  });

  it('should save pricing and publish event when andPublish is true', () => {
    component.savePricing(true);

    expect(adminEventApi.configurePricing).toHaveBeenCalled();
    expect(adminEventApi.updateEvent).toHaveBeenCalledWith('evt-123', { status: 'PUBLISHED' });
    expect(router.navigate).toHaveBeenCalledWith(['/admin/events']);
  });

  it('should handle pricing error gracefully', () => {
    adminEventApi.configurePricing.and.returnValue(
      throwError(() => ({ error: { message: 'Invalid section tiers' } }))
    );

    component.savePricing(false);

    expect(component.isSaving()).toBeFalse();
    expect(component.errorMessage()).toBe('Invalid section tiers');
  });
});
