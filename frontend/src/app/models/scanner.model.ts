export interface ValidateTicketRequest {
  ticketCode: string;
  scannerDeviceId: string;
}

export type ScanResultType = 'SUCCESS' | 'ALREADY_USED' | 'INVALID' | 'CANCELLED';

export interface ValidationResultResponse {
  valid: boolean;
  ticketId?: string;
  ticketCode: string;
  result: ScanResultType;
  eventTitle?: string;
  eventDate?: string;
  attendeeName?: string;
  section?: string;
  rowNumber?: string;
  seatNumber?: number;
  ticketType?: string;
  tierName?: string;
  scannedAt: string;
  firstScannedAt?: string;
  firstScannedDevice?: string;
  message: string;
}

export interface ScannerStats {
  totalScans: number;
  grantedCount: number;
  rejectedCount: number;
  alreadyUsedCount: number;
}
