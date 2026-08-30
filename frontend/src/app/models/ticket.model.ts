import { PagedResult } from './event.model';

export type TicketStatus = 'VALID' | 'USED' | 'CANCELLED';

export interface TicketItem {
  id: string;
  ticketCode: string;
  reservationId: string;
  paymentId?: string;
  userId?: string;
  eventId: string;
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
