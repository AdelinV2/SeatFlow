import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ValidationResultResponse } from '../models/scanner.model';
import { ScannerApiService } from './scanner-api.service';

describe('ScannerApiService', () => {
  let service: ScannerApiService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [ScannerApiService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ScannerApiService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTesting.verify();
  });

  it('validates a ticket code and sends scannerDeviceId in payload', () => {
    const mockResponse: ValidationResultResponse = {
      valid: true,
      ticketId: 'tkt-uuid-001',
      ticketCode: 'SF-TKT-123456',
      result: 'SUCCESS',
      eventTitle: 'Symphony Gala',
      eventDate: '2026-09-15T19:30:00Z',
      attendeeName: 'Alex Smith',
      section: 'Orchestra',
      rowNumber: 'A',
      seatNumber: 1,
      scannedAt: '2026-09-15T18:45:10Z',
      message: 'Entry granted successfully',
    };

    service.validateTicket('SF-TKT-123456', 'GATE-01').subscribe((response) => {
      expect(response.valid).toBeTrue();
      expect(response.result).toBe('SUCCESS');
      expect(response.attendeeName).toBe('Alex Smith');
      expect(response.section).toBe('Orchestra');
      expect(response.seatNumber).toBe(1);
    });

    const req = httpTesting.expectOne('/api/scanner/tickets/validate');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      ticketCode: 'SF-TKT-123456',
      scannerDeviceId: 'GATE-01',
    });

    req.flush(mockResponse);
  });

  it('handles ALREADY_USED validation response', () => {
    const mockResponse: ValidationResultResponse = {
      valid: false,
      ticketCode: 'SF-TKT-123456',
      result: 'ALREADY_USED',
      attendeeName: 'Jane Doe',
      scannedAt: '2026-09-15T18:50:00Z',
      firstScannedAt: '2026-09-15T18:30:00Z',
      firstScannedDevice: 'GATE-NORTH-01',
      message: 'Ticket already scanned at gate GATE-NORTH-01',
    };

    service.validateTicket('SF-TKT-123456', 'GATE-SOUTH-02').subscribe((response) => {
      expect(response.valid).toBeFalse();
      expect(response.result).toBe('ALREADY_USED');
      expect(response.firstScannedDevice).toBe('GATE-NORTH-01');
    });

    const req = httpTesting.expectOne('/api/scanner/tickets/validate');
    req.flush(mockResponse);
  });
});
