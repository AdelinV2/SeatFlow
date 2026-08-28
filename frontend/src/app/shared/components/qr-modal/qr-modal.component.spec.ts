import { Clipboard } from '@angular/cdk/clipboard';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { QrModalComponent } from './qr-modal.component';

describe('QrModalComponent', () => {
  let fixture: ComponentFixture<QrModalComponent>;
  let component: QrModalComponent;
  let clipboardSpy: jasmine.SpyObj<Clipboard>;
  let dialogRefSpy: jasmine.SpyObj<MatDialogRef<QrModalComponent>>;

  beforeEach(async () => {
    clipboardSpy = jasmine.createSpyObj('Clipboard', ['copy']);
    clipboardSpy.copy.and.returnValue(true);
    dialogRefSpy = jasmine.createSpyObj('MatDialogRef', ['close']);

    await TestBed.configureTestingModule({
      imports: [QrModalComponent],
      providers: [
        { provide: Clipboard, useValue: clipboardSpy },
        { provide: MatDialogRef, useValue: dialogRefSpy },
        {
          provide: MAT_DIALOG_DATA,
          useValue: {
            qrCodeData: 'data:image/png;base64,sampleqr',
            ticketCode: 'SF-TKT-987654',
            title: 'VIP Pass',
          },
        },
      ],
    }).compileComponents();

    jasmine.clock().install();
    fixture = TestBed.createComponent(QrModalComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture.destroy();
    jasmine.clock().uninstall();
  });

  it('initializes from dialog data and sets image directly for data URLs', () => {
    expect(component.title()).toBe('VIP Pass');
    expect(component.ticketCode()).toBe('SF-TKT-987654');
    expect(component.qrImageUrl()).toBe('data:image/png;base64,sampleqr');
  });

  it('copies ticket code to clipboard and resets state after 3 seconds', () => {
    component.copyTicketCode();

    expect(clipboardSpy.copy).toHaveBeenCalledWith('SF-TKT-987654');
    expect(component.copied()).toBeTrue();

    jasmine.clock().tick(3000);

    expect(component.copied()).toBeFalse();
  });

  it('emits closed output and closes dialogRef on close()', () => {
    const closedSpy = jasmine.createSpy('closed');
    component.closed.subscribe(closedSpy);

    component.close();

    expect(closedSpy).toHaveBeenCalled();
    expect(dialogRefSpy.close).toHaveBeenCalled();
  });

  it('sets error message when QR code data is empty', () => {
    fixture.componentRef.setInput('qrCodeData', '');
    fixture.detectChanges();

    expect(component.qrError()).toBe('QR code data is unavailable.');
  });
});
