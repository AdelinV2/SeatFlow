import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';
import { AdminEventEditorComponent } from './admin-event-editor.component';
import { AdminEventApiService } from '../../../../services/admin-event-api.service';
import { AdminVenueApiService } from '../../../../services/admin-venue-api.service';
import { EventDetail } from '../../../../models/event.model';
import { VenueSummary } from '../../../../models/venue.model';

describe('AdminEventEditorComponent', () => {
  let component: AdminEventEditorComponent;
  let fixture: ComponentFixture<AdminEventEditorComponent>;
  let adminEventApi: jasmine.SpyObj<AdminEventApiService>;
  let adminVenueApi: jasmine.SpyObj<AdminVenueApiService>;
  let router: Router;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  const mockEvent: EventDetail = {
    id: 'evt-123',
    venueId: 'ven-1',
    title: 'Neon Symphony Live',
    description: 'A spectacular concert experience with orchestral symphony.',
    category: 'CONCERT',
    bannerUrl: 'https://images.unsplash.com/photo-1514525253161-7a46d19cd819',
    eventDate: '2026-11-20T20:00:00Z',
    status: 'DRAFT',
    pricingTiers: [],
    createdAt: '2026-08-29T10:00:00Z',
  };

  const mockVenues: VenueSummary[] = [
    {
      id: 'ven-1',
      name: 'Grand Arena',
      address: '100 Stadium Way',
      city: 'London',
      country: 'UK',
      capacity: 5000,
    },
  ];

  beforeEach(async () => {
    const eventApiSpy = jasmine.createSpyObj('AdminEventApiService', [
      'getEventById',
      'createEvent',
      'updateEvent',
    ]);
    const venueApiSpy = jasmine.createSpyObj('AdminVenueApiService', ['getVenues']);
    const snackBarSpy = jasmine.createSpyObj('MatSnackBar', ['open']);

    venueApiSpy.getVenues.and.returnValue(
      of({ content: mockVenues, page: 0, size: 10, totalElements: 1, totalPages: 1, isFirst: true, isLast: true })
    );
    eventApiSpy.getEventById.and.returnValue(of(mockEvent));

    await TestBed.configureTestingModule({
      imports: [AdminEventEditorComponent],
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
                get: (key: string) => (key === 'id' ? null : null),
              },
            },
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AdminEventEditorComponent);
    component = fixture.componentInstance;
    adminEventApi = TestBed.inject(AdminEventApiService) as jasmine.SpyObj<AdminEventApiService>;
    adminVenueApi = TestBed.inject(AdminVenueApiService) as jasmine.SpyObj<AdminVenueApiService>;
    router = TestBed.inject(Router);
    snackBar = TestBed.inject(MatSnackBar) as jasmine.SpyObj<MatSnackBar>;
    spyOn(router, 'navigate');
    fixture.detectChanges();
  });

  it('should create in new mode', () => {
    expect(component).toBeTruthy();
    expect(component.isEditMode()).toBeFalse();
    expect(component.venues().length).toBe(1);
  });

  it('should update banner url when preset selected', () => {
    const bannerUrl = 'https://images.unsplash.com/photo-custom';
    component.onBannerSelected(bannerUrl);

    expect(component.bannerUrl()).toBe(bannerUrl);
    expect(component.eventForm.controls.bannerUrl.value).toBe(bannerUrl);
  });

  it('should insert markdown formatting and templates', () => {
    component.insertFormatting('**', '**');
    expect(component.eventForm.controls.description.value).toContain('**');

    component.insertTemplate('SCHEDULE');
    expect(component.eventForm.controls.description.value).toContain('Schedule & Door Times');

    expect(component.formattedDescriptionPreview()).toContain('Schedule');
    expect(component.formattedDescriptionPreview()).toContain('Door Times');
  });

  it('should submit valid create form and redirect to pricing manager', () => {
    adminEventApi.createEvent.and.returnValue(of(mockEvent));

    component.eventForm.patchValue({
      title: 'Neon Symphony Live',
      category: 'CONCERT',
      venueId: 'ven-1',
      eventDate: '2026-11-20T20:00',
      description: 'A spectacular concert experience with live music.',
      bannerUrl: 'https://images.unsplash.com/photo-1514525253161-7a46d19cd819',
    });

    component.onSubmit();

    expect(adminEventApi.createEvent).toHaveBeenCalled();
    expect(snackBar.open).toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['/admin/events', 'evt-123', 'pricing']);
  });

  it('should not submit if form is invalid', () => {
    component.eventForm.patchValue({
      title: '', // invalid
    });

    component.onSubmit();

    expect(adminEventApi.createEvent).not.toHaveBeenCalled();
  });

  it('should handle creation error gracefully', () => {
    adminEventApi.createEvent.and.returnValue(
      throwError(() => ({ error: { message: 'Failed to create event.' } }))
    );

    component.eventForm.patchValue({
      title: 'Neon Symphony Live',
      category: 'CONCERT',
      venueId: 'ven-1',
      eventDate: '2026-11-20T20:00',
      description: 'A spectacular concert experience with live music.',
      bannerUrl: 'https://images.unsplash.com/photo-1514525253161-7a46d19cd819',
    });

    component.onSubmit();

    expect(component.isSaving()).toBeFalse();
    expect(component.errorMessage()).toBe('Failed to create event.');
  });
});
