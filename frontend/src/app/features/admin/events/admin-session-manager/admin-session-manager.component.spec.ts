import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { EventDetail, EventSession } from '../../../../models/event.model';
import { AdminEventApiService } from '../../../../services/admin-event-api.service';
import { AdminSessionManagerComponent } from './admin-session-manager.component';

describe('AdminSessionManagerComponent', () => {
  let fixture: ComponentFixture<AdminSessionManagerComponent>;
  let component: AdminSessionManagerComponent;
  let adminEventApi: jasmine.SpyObj<AdminEventApiService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  const mockSessions: EventSession[] = [
    {
      id: 'session-future',
      eventId: 'event-1',
      startsAt: '2028-10-10T18:00:00Z',
      endsAt: '2028-10-10T20:00:00Z',
      saleStartsAt: '2028-10-01T00:00:00Z',
      saleEndsAt: '2028-10-10T17:00:00Z',
      status: 'SCHEDULED',
      timezone: 'Europe/Bucharest',
    },
    {
      id: 'session-past',
      eventId: 'event-1',
      startsAt: '2020-01-01T18:00:00Z',
      endsAt: '2020-01-01T20:00:00Z',
      status: 'SCHEDULED',
      timezone: 'UTC',
    },
  ];

  const mockEventDetail: EventDetail = {
    id: 'event-1',
    venueId: 'venue-1',
    title: 'Rock Festival 2028',
    description: 'Annual Festival',
    category: 'CONCERT',
    bannerUrl: 'https://example.com/fest.jpg',
    status: 'DRAFT',
    pricingTiers: [],
    sessions: mockSessions,
    createdAt: '2026-08-01T10:00:00Z',
  };

  beforeEach(async () => {
    adminEventApi = jasmine.createSpyObj<AdminEventApiService>('AdminEventApiService', [
      'getEventById',
      'getEventSessions',
      'createEventSession',
      'updateEventSession',
      'deleteEventSession',
    ]);
    adminEventApi.getEventById.and.returnValue(of(mockEventDetail));
    adminEventApi.getEventSessions.and.returnValue(of(mockSessions));
    adminEventApi.createEventSession.and.returnValue(of(mockSessions[0]));
    adminEventApi.updateEventSession.and.returnValue(of(mockSessions[0]));
    adminEventApi.deleteEventSession.and.returnValue(of(undefined));

    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);

    await TestBed.configureTestingModule({
      imports: [AdminSessionManagerComponent],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: convertToParamMap({ id: 'event-1' }),
            },
          },
        },
        { provide: AdminEventApiService, useValue: adminEventApi },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AdminSessionManagerComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('loads event and sessions on initialization', () => {
    expect(adminEventApi.getEventById).toHaveBeenCalledWith('event-1');
    expect(adminEventApi.getEventSessions).toHaveBeenCalledWith('event-1');
    expect(component.event()?.title).toBe('Rock Festival 2028');
    expect(component.sessions().length).toBe(2);
  });

  it('correctly identifies locked sessions for past sessions', () => {
    const futureLock = component.isSessionLocked(mockSessions[0]);
    expect(futureLock.locked).toBeFalse();

    const pastLock = component.isSessionLocked(mockSessions[1]);
    expect(pastLock.locked).toBeTrue();
    expect(pastLock.reason).toBe('Session has already ended');
  });

  it('validates that end time cannot be before start time', () => {
    component.openCreateModal();
    component.formStartsAt.set('2028-10-10T20:00');
    component.formEndsAt.set('2028-10-10T18:00');

    component.saveSession();

    expect(component.formErrors()).toContain('End time must be strictly after start time.');
    expect(adminEventApi.createEventSession).not.toHaveBeenCalled();
  });

  it('creates session when form is valid', () => {
    component.openCreateModal();
    component.formStartsAt.set('2028-10-10T18:00');
    component.formEndsAt.set('2028-10-10T20:00');
    component.formTimezone.set('UTC');

    component.saveSession();

    expect(adminEventApi.createEventSession).toHaveBeenCalledWith(
      'event-1',
      jasmine.objectContaining({
        timezone: 'UTC',
      }),
    );
    expect(snackBar.open).toHaveBeenCalledWith('Showtime created successfully', 'Close', jasmine.any(Object));
  });

  it('updates session when editing', () => {
    component.openEditModal(mockSessions[0]);
    component.formTimezone.set('Europe/Berlin');

    component.saveSession();

    expect(adminEventApi.updateEventSession).toHaveBeenCalledWith(
      'event-1',
      'session-future',
      jasmine.objectContaining({
        timezone: 'Europe/Berlin',
      }),
    );
    expect(snackBar.open).toHaveBeenCalledWith('Showtime updated successfully', 'Close', jasmine.any(Object));
  });

  it('prevents opening edit modal when session is locked', () => {
    component.openEditModal(mockSessions[1]);
    expect(component.showEditModal()).toBeFalse();
    expect(snackBar.open).toHaveBeenCalledWith(
      jasmine.stringContaining('Cannot edit locked session'),
      'Close',
      jasmine.any(Object),
    );
  });

  it('deletes session after confirmation', () => {
    component.openDeleteModal(mockSessions[0]);
    expect(component.sessionToDelete()?.id).toBe('session-future');

    component.confirmDelete();

    expect(adminEventApi.deleteEventSession).toHaveBeenCalledWith('event-1', 'session-future');
    expect(snackBar.open).toHaveBeenCalledWith('Showtime deleted successfully', 'Close', jasmine.any(Object));
    expect(component.sessionToDelete()).toBeNull();
  });

  describe('schedule contract mirror (TASK-P12-006 REV-005)', () => {
    function openValidForm(): void {
      component.openCreateModal();
      component.formStartsAt.set('2028-10-10T18:00');
      component.formEndsAt.set('2028-10-10T20:00');
      component.formSaleStartsAt.set('2028-10-01T00:00');
      component.formSaleEndsAt.set('2028-10-09T00:00');
      component.formTimezone.set('UTC');
    }

    it('rejects a sale start after the session start without calling the API', () => {
      openValidForm();
      component.formSaleStartsAt.set('2028-10-10T19:00');

      component.saveSession();

      expect(component.formErrors()).toContain(
        'Sale start time must be on or before session start time.',
      );
      expect(adminEventApi.createEventSession).not.toHaveBeenCalled();
    });

    it('rejects a sale end inside the showing without calling the API', () => {
      openValidForm();
      component.formSaleEndsAt.set('2028-10-10T19:00');

      component.saveSession();

      expect(component.formErrors()).toContain(
        'Sale end time must be on or before session start time.',
      );
      expect(adminEventApi.createEventSession).not.toHaveBeenCalled();
    });

    it('rejects an invalid IANA timezone without calling the API', () => {
      openValidForm();
      component.formTimezone.set('Europe/Bucharestt');

      component.saveSession();

      expect(component.formErrors()).toContain(
        'Timezone must be a valid IANA timezone identifier (e.g. Europe/Bucharest or UTC).',
      );
      expect(adminEventApi.createEventSession).not.toHaveBeenCalled();
    });

    it('accepts a sale window fully before the session start', () => {
      openValidForm();

      component.saveSession();

      expect(adminEventApi.createEventSession).toHaveBeenCalled();
      expect(component.formErrors()).toEqual([]);
    });

    it('surfaces backend validation errors as the authoritative fallback', () => {
      adminEventApi.createEventSession.and.returnValue(
        throwError(() => ({ error: { message: 'Sale end time must be on or before session start' } })),
      );
      openValidForm();

      component.saveSession();

      expect(component.formErrors()).toEqual([
        'Sale end time must be on or before session start',
      ]);
    });
  });
});
