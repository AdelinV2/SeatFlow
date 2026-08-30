import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { TicketItem } from '../models/ticket.model';
import { TicketApiService } from './ticket-api.service';

describe('TicketApiService', () => {
  let service: TicketApiService;
  let httpTesting: HttpTestingController;

  const mockTicket: TicketItem = {
    id: 'ticket-uuid-001',
    ticketCode: 'SF-TKT-123456',
    reservationId: 'reservation-uuid-001',
    paymentId: 'payment-uuid-001',
    userId: 'user-uuid-001',
    eventId: 'event-uuid-001',
    seatId: 'seat-uuid-001',
    price: 85.0,
    taxAmount: 16.15,
    netAmount: 68.85,
    customerEmail: 'customer@seatflow.com',
    attendeeName: 'Alex Smith',
    status: 'VALID',
    qrCodeData: 'SF-TKT-123456#data',
    createdAt: '2026-08-30T10:00:00Z',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [TicketApiService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(TicketApiService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTesting.verify();
  });

  it('fetches paginated my-tickets for the authenticated customer', () => {
    service.getMyTickets(0, 10).subscribe((page) => {
      expect(page.content.length).toBe(1);
      expect(page.content[0].ticketCode).toBe('SF-TKT-123456');
      expect(page.totalElements).toBe(1);
    });

    const req = httpTesting.expectOne('/api/tickets/my-tickets?page=0&size=10');
    expect(req.request.method).toBe('GET');
    req.flush({
      content: [mockTicket],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
      isFirst: true,
      isLast: true,
    });
  });

  it('fetches a single guest ticket by ticket code', () => {
    service.getGuestTicket('SF-TKT-123456').subscribe((ticket) => {
      expect(ticket.id).toBe('ticket-uuid-001');
      expect(ticket.status).toBe('VALID');
    });

    const req = httpTesting.expectOne('/api/tickets/guest/SF-TKT-123456');
    expect(req.request.method).toBe('GET');
    req.flush(mockTicket);
  });

  it('fetches a ticket by internal UUID', () => {
    service.getTicketById('ticket-uuid-001').subscribe((ticket) => {
      expect(ticket.ticketCode).toBe('SF-TKT-123456');
    });

    const req = httpTesting.expectOne('/api/tickets/ticket-uuid-001');
    expect(req.request.method).toBe('GET');
    req.flush(mockTicket);
  });

  it('downloads the rendered PDF ticket stream as a Blob', () => {
    service.downloadTicketPdf('ticket-uuid-001').subscribe((blob) => {
      expect(blob).toBeTruthy();
      expect(blob.type).toBe('application/pdf');
    });

    const req = httpTesting.expectOne('/api/tickets/ticket-uuid-001/pdf');
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('blob');
    const dummyBlob = new Blob(['%PDF-1.4 mock pdf content'], { type: 'application/pdf' });
    req.flush(dummyBlob);
  });
});
