import { Clipboard } from '@angular/cdk/clipboard';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { UserContextService } from '../../../core/auth/user-context.service';
import { EventDetail } from '../../../models/event.model';
import { TicketItem } from '../../../models/ticket.model';
import { EventApiService } from '../../../services/event-api.service';
import {
  ReservationApiService,
  ReservationResponse,
} from '../../../services/reservation-api.service';
import { TicketApiService } from '../../../services/ticket-api.service';
import { GuestTicketComponent } from './guest-ticket.component';

describe('GuestTicketComponent', () => {
  let component: GuestTicketComponent;
  let fixture: ComponentFixture<GuestTicketComponent>;
  let ticketServiceSpy: jasmine.SpyObj<TicketApiService>;
  let reservationApiSpy: jasmine.SpyObj<ReservationApiService>;
  let eventApiSpy: jasmine.SpyObj<EventApiService>;
  let dialogSpy: jasmine.SpyObj<MatDialog>;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;
  let clipboardSpy: jasmine.SpyObj<Clipboard>;
  let userContextSpy: jasmine.SpyObj<UserContextService>;

  const mockTicket: TicketItem = {
    id: 'ticket-1',
    ticketCode: 'SF-TKT-123456',
    reservationId: 'res-1',
    eventId: 'event-1',
    seatId: 'seat-1',
    price: 90,
    taxAmount: 17.1,
    netAmount: 72.9,
    customerEmail: 'guest@seatflow.dev',
    attendeeName: 'Jane Doe',
    section: 'Orchestra',
    rowNumber: 'A',
    seatNumber: 1,
    status: 'VALID',
    qrCodeData: 'data:image/png;base64,mockqr',
    createdAt: '2026-08-30T12:00:00Z',
  };

  const mockReservation: ReservationResponse = {
    id: 'res-1',
    eventId: 'event-1',
    customerEmail: 'guest@seatflow.dev',
    status: 'CONFIRMED',
    expiresAt: '2026-08-30T12:15:00Z',
    totalAmount: 180,
    seats: [
      { seatId: 'seat-1', rowNumber: 'A', seatNumber: 1, price: 90 },
      { seatId: 'seat-2', rowNumber: 'A', seatNumber: 2, price: 90 },
    ],
  };

  const mockEvent: EventDetail = {
    id: 'event-1',
    venueId: 'venue-1',
    title: 'Symphony Concert',
    description: 'A great classical evening',
    category: 'SYMPHONY',
    bannerUrl: 'https://cdn.seatflow.com/symphony.jpg',
    eventDate: '2026-10-15T20:00:00Z',
    status: 'PUBLISHED',
    venueName: 'Royal Concert Hall',
    pricingTiers: [],
    createdAt: '2026-08-01T10:00:00Z',
  };

  beforeEach(async () => {
    ticketServiceSpy = jasmine.createSpyObj('TicketApiService', [
      'getGuestTicket',
      'getGuestTicketBundle',
      'downloadTicketPdf',
      'downloadGuestTicketPdf',
    ]);
    reservationApiSpy = jasmine.createSpyObj('ReservationApiService', ['getReservation']);
    eventApiSpy = jasmine.createSpyObj('EventApiService', ['getEventById']);
    dialogSpy = jasmine.createSpyObj('MatDialog', ['open']);
    snackBarSpy = jasmine.createSpyObj('MatSnackBar', ['open']);
    clipboardSpy = jasmine.createSpyObj('Clipboard', ['copy']);
    userContextSpy = jasmine.createSpyObj('UserContextService', [
      'isAuthenticated',
      'userName',
      'userEmail',
    ]);

    const mockTicketBundle: TicketItem[] = [
      mockTicket,
      {
        ...mockTicket,
        id: 'ticket-2',
        seatId: 'seat-2',
        seatNumber: 2,
        ticketCode: 'SF-TKT-123457',
      },
    ];
    ticketServiceSpy.getGuestTicket.and.returnValue(of(mockTicket));
    ticketServiceSpy.getGuestTicketBundle.and.returnValue(of(mockTicketBundle));
    ticketServiceSpy.downloadTicketPdf.and.returnValue(of(new Blob(['pdf'], { type: 'application/pdf' })));
    ticketServiceSpy.downloadGuestTicketPdf.and.returnValue(of(new Blob(['pdf'], { type: 'application/pdf' })));
    reservationApiSpy.getReservation.and.returnValue(of(mockReservation));
    eventApiSpy.getEventById.and.returnValue(of(mockEvent));
    userContextSpy.isAuthenticated.and.returnValue(false);
    clipboardSpy.copy.and.returnValue(true);

    await TestBed.configureTestingModule({
      imports: [GuestTicketComponent],
      providers: [
        provideRouter([]),
        { provide: TicketApiService, useValue: ticketServiceSpy },
        { provide: ReservationApiService, useValue: reservationApiSpy },
        { provide: EventApiService, useValue: eventApiSpy },
        { provide: MatDialog, useValue: dialogSpy },
        { provide: MatSnackBar, useValue: snackBarSpy },
        { provide: Clipboard, useValue: clipboardSpy },
        { provide: UserContextService, useValue: userContextSpy },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: {
                get: (key: string) => (key === 'ticketCode' ? 'SF-TKT-123456' : null),
              },
            },
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(GuestTicketComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('loads ticket details and enriches reservation and event', () => {
    expect(ticketServiceSpy.getGuestTicket).toHaveBeenCalledWith('SF-TKT-123456');
    expect(reservationApiSpy.getReservation).toHaveBeenCalledWith('res-1', 'guest@seatflow.dev');
    expect(eventApiSpy.getEventById).toHaveBeenCalledWith('event-1');
    expect(component.primaryTicket()).toEqual(mockTicket);
    expect(component.isLoading()).toBeFalse();
  });

  it('renders multi-ticket switcher for multi-seat bookings (ADR-001)', () => {
    expect(component.ticketList().length).toBe(2);
    expect(component.selectedTicketIndex()).toBe(0);
    expect(component.activeTicket()?.seatNumber).toBe(1);

    component.selectTicket(1);
    expect(component.selectedTicketIndex()).toBe(1);
    expect(component.activeTicket()?.seatNumber).toBe(2);
  });

  it('opens QR modal when openQrModal() is invoked', () => {
    component.openQrModal();
    expect(dialogSpy.open).toHaveBeenCalled();
  });

  it('copies ticket code to clipboard', () => {
    component.copyTicketCode();
    expect(clipboardSpy.copy).toHaveBeenCalledWith('SF-TKT-123456');
    expect(component.isCopied()).toBeTrue();
    expect(snackBarSpy.open).toHaveBeenCalled();
  });

  it('downloads ticket PDF when downloadCurrentPdf() is called', () => {
    const mockBlob = new Blob(['pdf-data'], { type: 'application/pdf' });
    ticketServiceSpy.downloadGuestTicketPdf.and.returnValue(of(mockBlob));

    component.downloadCurrentPdf();
    expect(ticketServiceSpy.downloadGuestTicketPdf).toHaveBeenCalledWith('SF-TKT-123456');
  });

  it('displays error state when guest ticket fetch fails', () => {
    ticketServiceSpy.getGuestTicket.and.returnValue(throwError(() => new Error('Not found')));
    component.ngOnInit();
    fixture.detectChanges();

    expect(component.errorMessage()).toBeTruthy();
    expect(component.isLoading()).toBeFalse();
  });
});
