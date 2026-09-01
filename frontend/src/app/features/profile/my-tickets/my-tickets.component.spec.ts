import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { UserContextService } from '../../../core/auth/user-context.service';
import { EventDetail, VenueDetail } from '../../../models/event.model';
import { TicketItem } from '../../../models/ticket.model';
import { EventApiService } from '../../../services/event-api.service';
import {
  ReservationApiService,
  ReservationResponse,
} from '../../../services/reservation-api.service';
import { TicketApiService } from '../../../services/ticket-api.service';
import { MyTicketsComponent } from './my-tickets.component';

describe('MyTicketsComponent', () => {
  let component: MyTicketsComponent;
  let fixture: ComponentFixture<MyTicketsComponent>;
  let ticketServiceSpy: jasmine.SpyObj<TicketApiService>;
  let eventApiSpy: jasmine.SpyObj<EventApiService>;
  let reservationApiSpy: jasmine.SpyObj<ReservationApiService>;
  let dialogSpy: jasmine.SpyObj<MatDialog>;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;
  let userContextSpy: jasmine.SpyObj<UserContextService>;

  const upcomingDate = new Date(Date.now() + 86400000 * 30).toISOString();
  const pastDate = new Date(Date.now() - 86400000 * 30).toISOString();

  const mockUpcomingTicket: TicketItem = {
    id: 'ticket-up-1',
    ticketCode: 'SF-TKT-UPCOMING',
    reservationId: 'res-up-1',
    eventId: 'event-up-1',
    seatId: 'seat-up-1',
    eventTitle: 'Upcoming Festival',
    eventDate: upcomingDate,
    price: 120,
    taxAmount: 22.8,
    netAmount: 97.2,
    customerEmail: 'alex@example.com',
    status: 'VALID',
    qrCodeData: 'SF-TKT-UPCOMING#qr',
    createdAt: '2026-08-30T10:00:00Z',
  };

  const mockPastTicket: TicketItem = {
    id: 'ticket-past-1',
    ticketCode: 'SF-TKT-PAST',
    reservationId: 'res-past-1',
    eventId: 'event-past-1',
    seatId: 'seat-past-1',
    eventTitle: 'Past Symphony',
    eventDate: pastDate,
    price: 65,
    taxAmount: 12.35,
    netAmount: 52.65,
    customerEmail: 'alex@example.com',
    status: 'USED',
    qrCodeData: 'SF-TKT-PAST#qr',
    createdAt: '2026-06-01T10:00:00Z',
  };

  const mockEventDetail: EventDetail = {
    id: 'event-up-1',
    venueId: 'venue-1',
    title: 'Upcoming Festival',
    description: 'Electric vibes',
    category: 'FESTIVAL',
    bannerUrl: 'https://cdn.seatflow.com/festival.jpg',
    eventDate: upcomingDate,
    status: 'PUBLISHED',
    venueName: 'Green Park Arena',
    pricingTiers: [],
    createdAt: '2026-08-01T10:00:00Z',
  };

  const mockVenue: VenueDetail = {
    id: 'venue-1',
    name: 'Green Park Arena',
    city: 'Timisoara',
    address: '10 Park Lane',
    country: 'Romania',
    capacity: 8000,
    latitude: 45.75,
    longitude: 21.22,
    sections: [],
  };

  const mockReservation: ReservationResponse = {
    id: 'res-up-1',
    eventId: 'event-up-1',
    customerEmail: 'alex@example.com',
    customerName: 'Alex Smith',
    status: 'CONFIRMED',
    expiresAt: '2026-08-30T10:15:00Z',
    totalAmount: 120,
    seats: [
      { seatId: 'seat-up-1', rowNumber: 'A', seatNumber: 1, price: 120, ticketType: 'VIP' },
    ],
  };

  beforeEach(async () => {
    ticketServiceSpy = jasmine.createSpyObj('TicketApiService', [
      'getMyTickets',
      'downloadTicketPdf',
    ]);
    eventApiSpy = jasmine.createSpyObj('EventApiService', ['getEventById', 'getVenueById']);
    reservationApiSpy = jasmine.createSpyObj('ReservationApiService', ['getReservation']);
    dialogSpy = jasmine.createSpyObj('MatDialog', ['open']);
    snackBarSpy = jasmine.createSpyObj('MatSnackBar', ['open']);
    userContextSpy = jasmine.createSpyObj('UserContextService', [
      'isAuthenticated',
      'userName',
      'userEmail',
    ]);

    ticketServiceSpy.getMyTickets.and.returnValue(
      of({
        content: [mockUpcomingTicket, mockPastTicket],
        page: 0,
        size: 50,
        totalElements: 2,
        totalPages: 1,
        isFirst: true,
        isLast: true,
      }),
    );
    eventApiSpy.getEventById.and.returnValue(of(mockEventDetail));
    eventApiSpy.getVenueById.and.returnValue(of(mockVenue));
    reservationApiSpy.getReservation.and.returnValue(of(mockReservation));
    userContextSpy.isAuthenticated.and.returnValue(true);

    await TestBed.configureTestingModule({
      imports: [MyTicketsComponent],
      providers: [
        provideRouter([]),
        { provide: TicketApiService, useValue: ticketServiceSpy },
        { provide: EventApiService, useValue: eventApiSpy },
        { provide: ReservationApiService, useValue: reservationApiSpy },
        { provide: MatDialog, useValue: dialogSpy },
        { provide: MatSnackBar, useValue: snackBarSpy },
        { provide: UserContextService, useValue: userContextSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(MyTicketsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('loads tickets on initialization and partitions into upcoming and past', () => {
    expect(ticketServiceSpy.getMyTickets).toHaveBeenCalledWith(0, 50);
    expect(component.allTickets().length).toBe(2);
    expect(component.upcomingTickets().length).toBe(1);
    expect(component.pastTickets().length).toBe(1);
    expect(component.activeTab()).toBe('upcoming');
    expect(component.displayedTickets()[0].id).toBe('ticket-up-1');
  });

  it('switches tabs to past events', () => {
    component.setTab('past');
    expect(component.activeTab()).toBe('past');
    expect(component.displayedTickets().length).toBe(1);
    expect(component.displayedTickets()[0].id).toBe('ticket-past-1');
  });

  it('opens QR modal for a selected ticket', () => {
    component.openQrModal(mockUpcomingTicket);
    expect(dialogSpy.open).toHaveBeenCalled();
  });

  it('downloads ticket PDF for a pass card', () => {
    const mockBlob = new Blob(['sample-pdf'], { type: 'application/pdf' });
    ticketServiceSpy.downloadTicketPdf.and.returnValue(of(mockBlob));

    component.downloadPdf(mockUpcomingTicket);
    expect(ticketServiceSpy.downloadTicketPdf).toHaveBeenCalledWith('ticket-up-1');
    expect(snackBarSpy.open).toHaveBeenCalled();
  });

  it('handles ticket loading error gracefully', () => {
    ticketServiceSpy.getMyTickets.and.returnValue(throwError(() => new Error('Error')));
    component.loadMyTickets();
    fixture.detectChanges();

    expect(component.isLoading()).toBeFalse();
    expect(snackBarSpy.open).toHaveBeenCalled();
  });
});
