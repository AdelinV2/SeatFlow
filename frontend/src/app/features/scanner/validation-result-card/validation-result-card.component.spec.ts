import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ValidationResultResponse } from '../../../models/scanner.model';
import { ValidationResultCardComponent } from './validation-result-card.component';

describe('ValidationResultCardComponent', () => {
  let component: ValidationResultCardComponent;
  let fixture: ComponentFixture<ValidationResultCardComponent>;

  const mockSuccessResult: ValidationResultResponse = {
    valid: true,
    ticketId: 'tkt-01',
    ticketCode: 'SF-TKT-123456',
    result: 'SUCCESS',
    eventTitle: 'Symphony Concert',
    eventDate: '2026-09-15T20:00:00Z',
    attendeeName: 'Jane Doe',
    section: 'Orchestra',
    rowNumber: 'A',
    seatNumber: 12,
    scannedAt: '2026-09-15T19:45:00Z',
    message: 'Entry granted',
  };

  const mockAlreadyUsedResult: ValidationResultResponse = {
    valid: false,
    ticketCode: 'SF-TKT-123456',
    result: 'ALREADY_USED',
    attendeeName: 'John Doe',
    scannedAt: '2026-09-15T19:50:00Z',
    firstScannedAt: '2026-09-15T19:30:00Z',
    firstScannedDevice: 'GATE-NORTH-01',
    message: 'Ticket already scanned',
  };

  const mockInvalidResult: ValidationResultResponse = {
    valid: false,
    ticketCode: 'SF-TKT-999999',
    result: 'INVALID',
    scannedAt: '2026-09-15T19:55:00Z',
    message: 'Ticket not found or revoked',
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ValidationResultCardComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(ValidationResultCardComponent);
    component = fixture.componentInstance;
  });

  it('renders SUCCESS state with attendee details and seat coordinates', () => {
    fixture.componentRef.setInput('result', {
      ...mockSuccessResult,
      ticketType: 'Pensioner / Senior',
    });
    fixture.detectChanges();

    expect(component.isSuccess()).toBeTrue();
    expect(component.isAlreadyUsed()).toBeFalse();
    expect(component.isInvalid()).toBeFalse();
    expect(component.ticketType()).toBe('Pensioner / Senior');

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('ENTRY GRANTED');
    expect(compiled.textContent).toContain('Jane Doe');
    expect(compiled.textContent).toContain('PENSIONER / SENIOR PASS');
    expect(compiled.textContent).toContain('Orchestra');
    expect(compiled.textContent).toContain('Row A');
    expect(compiled.textContent).toContain('Seat 12');
  });

  it('renders ALREADY_USED state with prior scan metadata', () => {
    fixture.componentRef.setInput('result', mockAlreadyUsedResult);
    fixture.detectChanges();

    expect(component.isSuccess()).toBeFalse();
    expect(component.isAlreadyUsed()).toBeTrue();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('ALREADY SCANNED');
    expect(compiled.textContent).toContain('GATE-NORTH-01');
  });

  it('renders INVALID state with rejection message', () => {
    fixture.componentRef.setInput('result', mockInvalidResult);
    fixture.detectChanges();

    expect(component.isInvalid()).toBeTrue();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('ACCESS DENIED');
    expect(compiled.textContent).toContain('Ticket not found or revoked');
  });

  it('emits dismissed event when dismiss() is called', () => {
    spyOn(component.dismissed, 'emit');
    fixture.componentRef.setInput('result', mockSuccessResult);
    fixture.detectChanges();

    component.dismiss();
    expect(component.dismissed.emit).toHaveBeenCalled();
  });
});
