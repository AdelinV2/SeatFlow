import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { ValidationResultResponse } from '../../../models/scanner.model';
import { AudioFeedbackService } from '../../../services/audio-feedback.service';
import { ScannerApiService } from '../../../services/scanner-api.service';
import { StaffScannerComponent } from './staff-scanner.component';

describe('StaffScannerComponent', () => {
  let component: StaffScannerComponent;
  let fixture: ComponentFixture<StaffScannerComponent>;
  let scannerApiSpy: jasmine.SpyObj<ScannerApiService>;
  let audioFeedbackSpy: jasmine.SpyObj<AudioFeedbackService>;

  const mockSuccessResponse: ValidationResultResponse = {
    valid: true,
    ticketId: 'tkt-001',
    ticketCode: 'SF-TKT-9876-ABCD',
    result: 'SUCCESS',
    eventTitle: 'Symphony Gala',
    attendeeName: 'Alex Smith',
    ticketType: 'Student',
    section: 'Orchestra',
    rowNumber: 'A',
    seatNumber: 1,
    scannedAt: '2026-09-15T18:45:10Z',
    message: 'Entry granted successfully',
  };

  const mockAlreadyUsedResponse: ValidationResultResponse = {
    valid: false,
    ticketCode: 'SF-TKT-9876-ABCD',
    result: 'ALREADY_USED',
    attendeeName: 'Alex Smith',
    ticketType: 'Senior',
    scannedAt: '2026-09-15T18:46:00Z',
    firstScannedAt: '2026-09-15T18:30:00Z',
    firstScannedDevice: 'GATE-NORTH-01',
    message: 'Ticket already scanned',
  };

  beforeEach(async () => {
    jasmine.clock().install();

    scannerApiSpy = jasmine.createSpyObj('ScannerApiService', ['validateTicket']);
    audioFeedbackSpy = jasmine.createSpyObj('AudioFeedbackService', [
      'playFeedback',
      'toggleMute',
      'setMuted',
    ]);
    (audioFeedbackSpy as unknown as { isMuted: unknown }).isMuted = signal(false);

    scannerApiSpy.validateTicket.and.returnValue(of(mockSuccessResponse));

    await TestBed.configureTestingModule({
      imports: [StaffScannerComponent],
      providers: [
        { provide: ScannerApiService, useValue: scannerApiSpy },
        { provide: AudioFeedbackService, useValue: audioFeedbackSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(StaffScannerComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => {
    component.ngOnDestroy();
    jasmine.clock().uninstall();
  });

  it('correctly extracts ticket code from full deep-link URLs', () => {
    expect(
      component.parseTicketCode('https://seatflow.app/tickets/guest/SF-TKT-9876-ABCD'),
    ).toBe('SF-TKT-9876-ABCD');

    expect(
      component.parseTicketCode('https://seatflow.app/tickets/guest/SF-TKT-9876-ABCD?param=1'),
    ).toBe('SF-TKT-9876-ABCD');

    expect(
      component.parseTicketCode('http://localhost:4200/tickets/guest/SF-12345'),
    ).toBe('SF-12345');
  });

  it('correctly handles raw ticket codes without URL', () => {
    expect(component.parseTicketCode('SF-TKT-9876-ABCD')).toBe('SF-TKT-9876-ABCD');
    expect(component.parseTicketCode('  SF-TKT-SPACED  ')).toBe('SF-TKT-SPACED');
    expect(component.parseTicketCode('')).toBe('');
  });

  it('validates a ticket code and triggers audio, cooldown, and visual updates', () => {
    component.validateCode('SF-TKT-9876-ABCD');

    expect(scannerApiSpy.validateTicket).toHaveBeenCalledWith(
      'SF-TKT-9876-ABCD',
      component.deviceId(),
    );
    expect(component.currentResult()).toEqual(mockSuccessResponse);
    expect(component.scanHistory().length).toBe(1);
    expect(component.scanHistory()[0]).toEqual(mockSuccessResponse);
    expect(audioFeedbackSpy.playFeedback).toHaveBeenCalledWith('SUCCESS');
    expect(component.isValidating()).toBeFalse();
    expect(component.isCooldownActive()).toBeTrue();
    expect(component.cooldownRemainingSeconds()).toBe(3);
    expect(component.grantedScans()).toBe(1);
    expect(component.rejectedScans()).toBe(0);

    jasmine.clock().tick(1001);
    expect(component.cooldownRemainingSeconds()).toBe(2);
    jasmine.clock().tick(2001);
    expect(component.cooldownRemainingSeconds()).toBe(0);
    expect(component.isCooldownActive()).toBeFalse();
  });

  it('enforces 3-second scan cooldown ignoring camera frames during active cooldown', () => {
    component.handleDecodedQr('https://seatflow.app/tickets/guest/SF-TKT-9876-ABCD');
    expect(scannerApiSpy.validateTicket).toHaveBeenCalledTimes(1);

    // Immediate next scan (even with different code) during cooldown is locked
    component.handleDecodedQr('https://seatflow.app/tickets/guest/SF-TKT-ANOTHER');
    expect(scannerApiSpy.validateTicket).toHaveBeenCalledTimes(1);
  });

  it('validates ticket code submitted manually', () => {
    component.manualCode.set('SF-TKT-MANUAL');
    component.submitManualCode();

    expect(scannerApiSpy.validateTicket).toHaveBeenCalledWith(
      'SF-TKT-MANUAL',
      component.deviceId(),
    );
    expect(component.manualCode()).toBe('');
  });

  it('normalizes pasted full deep-link URLs submitted via manual input', () => {
    component.manualCode.set('https://seatflow.app/tickets/guest/SF-TKT-PASTED-999');
    component.submitManualCode();

    expect(scannerApiSpy.validateTicket).toHaveBeenCalledWith(
      'SF-TKT-PASTED-999',
      component.deviceId(),
    );
    expect(component.manualCode()).toBe('');
  });

  it('handles backend error response gracefully and produces an INVALID result card', () => {
    scannerApiSpy.validateTicket.and.returnValue(
      throwError(() => ({
        error: { message: 'Ticket has been revoked' },
      })),
    );

    component.validateCode('SF-TKT-REVOKED');

    expect(component.currentResult()?.result).toBe('INVALID');
    expect(component.currentResult()?.valid).toBeFalse();
    expect(component.currentResult()?.message).toBe('Ticket has been revoked');
    expect(audioFeedbackSpy.playFeedback).toHaveBeenCalledWith('INVALID');
    expect(component.rejectedScans()).toBe(1);
    expect(component.grantedScans()).toBe(0);
  });

  it('allows dismissing the current result card and resetting cooldown', () => {
    component.validateCode('SF-TKT-9876-ABCD');
    expect(component.currentResult()).not.toBeNull();
    expect(component.isCooldownActive()).toBeTrue();

    component.dismissResult();
    expect(component.currentResult()).toBeNull();
    expect(component.isCooldownActive()).toBeFalse();
  });

  it('allows inspecting a past history item and clearing history', () => {
    component.validateCode('SF-TKT-9876-ABCD');
    expect(component.scanHistory().length).toBe(1);

    component.inspectHistoryItem(mockAlreadyUsedResponse);
    expect(component.currentResult()).toEqual(mockAlreadyUsedResponse);

    component.clearHistory();
    expect(component.scanHistory().length).toBe(0);
    expect(component.totalScans()).toBe(0);
  });

  it('allows audio testing from HUD buttons', () => {
    component.testAudioTone('ALREADY_USED');
    expect(audioFeedbackSpy.playFeedback).toHaveBeenCalledWith('ALREADY_USED');
  });
});
