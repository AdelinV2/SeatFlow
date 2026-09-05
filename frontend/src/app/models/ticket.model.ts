import { PagedResult } from './event.model';

export type TicketStatus = 'VALID' | 'USED' | 'CANCELLED';

export interface TicketItem {
  id: string;
  ticketCode: string;
  reservationId: string;
  paymentId?: string;
  userId?: string;
  eventId: string;
  // P12-007: authoritative showing identity from GET /api/tickets/my-tickets
  // (TicketResponse.eventSessionId). Used to select the ticket's own session
  // for display; optional because pre-P12-004 rows have no session.
  eventSessionId?: string;
  seatId: string;
  eventTitle?: string;
  eventDate?: string;
  bannerUrl?: string;
  venueName?: string;
  venueAddress?: string;
  section?: string;
  rowNumber?: string;
  seatNumber?: number;
  price: number;
  taxAmount: number;
  netAmount: number;
  attendeeName?: string;
  customerEmail: string;
  status: TicketStatus;
  qrCodeData: string;
  createdAt: string;
  updatedAt?: string;
}

export interface GuestTicketBundleResponse {
  tickets: TicketItem[];
  reservationId: string;
  eventTitle?: string;
  venueName?: string;
  customerEmail: string;
}

export interface ClaimTicketsResponse {
  claimedCount: number;
  message?: string;
}
