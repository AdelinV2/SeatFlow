import { Clipboard } from '@angular/cdk/clipboard';
import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  OnDestroy,
  output,
  signal,
  untracked,
} from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';

export interface QrModalData {
  readonly qrCodeData: string;
  readonly ticketCode: string;
  readonly title?: string;
}

@Component({
  selector: 'app-qr-modal',
  standalone: true,
  imports: [MatDialogModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './qr-modal.component.html',
  styleUrl: './qr-modal.component.scss',
})
export class QrModalComponent implements OnDestroy {
  private readonly clipboard = inject(Clipboard);
  private readonly dialogData =
    inject<Partial<QrModalData>>(MAT_DIALOG_DATA, { optional: true }) ?? {};
  private readonly dialogRef = inject(MatDialogRef<QrModalComponent>, { optional: true });

  readonly qrCodeData = input(this.dialogData.qrCodeData ?? '');
  readonly ticketCode = input(this.dialogData.ticketCode ?? '');
  readonly title = input(this.dialogData.title ?? 'Your digital ticket');
  readonly closed = output<void>();

  readonly qrImageUrl = signal('');
  readonly qrError = signal('');
  readonly copied = signal(false);

  private copyTimeoutId?: ReturnType<typeof setTimeout>;

  constructor() {
    effect((onCleanup) => {
      const payload = this.qrCodeData().trim();
      let cancelled = false;

      untracked(() => {
        this.qrImageUrl.set('');
        this.qrError.set('');
      });

      if (!payload) {
        untracked(() => this.qrError.set('QR code data is unavailable.'));
        return;
      }

      if (payload.startsWith('data:image/')) {
        untracked(() => this.qrImageUrl.set(payload));
        return;
      }

      void import('qrcode')
        .then((module) => {
          const qr = (module as { default?: { toDataURL: typeof module.toDataURL } }).default ?? module;
          return qr.toDataURL(payload, {
            errorCorrectionLevel: 'H',
            margin: 3,
            width: 384,
            color: {
              dark: '#0B0F19FF',
              light: '#FFFFFFFF',
            },
          });
        })
        .then((imageUrl) => {
          if (!cancelled) {
            this.qrImageUrl.set(imageUrl);
          }
        })
        .catch(() => {
          if (!cancelled) {
            this.qrError.set('The QR code could not be rendered. Use the ticket code instead.');
          }
        });

      onCleanup(() => {
        cancelled = true;
      });
    });
  }

  ngOnDestroy(): void {
    if (this.copyTimeoutId !== undefined) {
      clearTimeout(this.copyTimeoutId);
      this.copyTimeoutId = undefined;
    }
  }

  copyTicketCode(): void {
    const code = this.ticketCode().trim();
    if (code.length > 0 && this.clipboard.copy(code)) {
      this.copied.set(true);
      if (this.copyTimeoutId !== undefined) {
        clearTimeout(this.copyTimeoutId);
      }
      this.copyTimeoutId = setTimeout(() => {
        this.copied.set(false);
        this.copyTimeoutId = undefined;
      }, 3000);
    }
  }

  close(): void {
    this.closed.emit();
    this.dialogRef?.close();
  }
}
