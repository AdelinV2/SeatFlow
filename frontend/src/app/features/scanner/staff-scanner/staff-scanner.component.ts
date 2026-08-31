import { CommonModule, isPlatformBrowser } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnDestroy,
  OnInit,
  PLATFORM_ID,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Html5Qrcode, Html5QrcodeSupportedFormats } from 'html5-qrcode';
import { ScanResultType, ValidationResultResponse } from '../../../models/scanner.model';
import { AudioFeedbackService } from '../../../services/audio-feedback.service';
import { ScannerApiService } from '../../../services/scanner-api.service';
import { StatusBadgeComponent } from '../../../shared/components/status-badge/status-badge.component';
import { TactileButtonComponent } from '../../../shared/components/tactile-button/tactile-button.component';
import { DateFormatPipe } from '../../../shared/pipes/date-format.pipe';
import { ValidationResultCardComponent } from '../validation-result-card/validation-result-card.component';

@Component({
  selector: 'app-staff-scanner',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    ValidationResultCardComponent,
    TactileButtonComponent,
    StatusBadgeComponent,
    DateFormatPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './staff-scanner.component.html',
  styleUrl: './staff-scanner.component.scss',
})
export class StaffScannerComponent implements OnInit, OnDestroy {
  private readonly platformId = inject(PLATFORM_ID);
  private readonly scannerApi = inject(ScannerApiService);
  readonly audioFeedback = inject(AudioFeedbackService);

  readonly deviceId = signal<string>(
    'GATE-SCANNER-' + Math.floor(1000 + Math.random() * 9000).toString(),
  );
  readonly isScanning = signal<boolean>(false);
  readonly isValidating = signal<boolean>(false);
  readonly cameraError = signal<string | null>(null);
  readonly currentResult = signal<ValidationResultResponse | null>(null);
  readonly scanHistory = signal<ValidationResultResponse[]>([]);
  readonly manualCode = signal<string>('');
  readonly isTorchOn = signal<boolean>(false);
  readonly hasTorch = signal<boolean>(false);

  // 3-Second Cooldown / Scan Lock
  readonly cooldownRemainingSeconds = signal<number>(0);
  readonly isCooldownActive = computed(() => this.cooldownRemainingSeconds() > 0);
  private cooldownInterval: ReturnType<typeof setInterval> | null = null;

  // Statistics
  readonly totalScans = computed(() => this.scanHistory().length);
  readonly grantedScans = computed(
    () => this.scanHistory().filter((s) => s.result === 'SUCCESS').length,
  );
  readonly rejectedScans = computed(
    () => this.scanHistory().filter((s) => s.result !== 'SUCCESS').length,
  );

  private html5QrCode: Html5Qrcode | null = null;
  private lastScannedCode = '';
  private lastScanTime = 0;

  ngOnInit(): void {
    if (isPlatformBrowser(this.platformId)) {
      // Small timeout to allow container DOM element to attach
      setTimeout(() => {
        this.startCameraScanner();
      }, 100);
    }
  }

  startCameraScanner(): void {
    this.cameraError.set(null);

    try {
      const container = document.getElementById('qr-reader-container');
      if (!container) {
        return;
      }

      if (this.html5QrCode) {
        if (this.html5QrCode.isScanning) {
          return;
        }
      } else {
        this.html5QrCode = new Html5Qrcode('qr-reader-container', {
          formatsToSupport: [Html5QrcodeSupportedFormats.QR_CODE],
          verbose: false,
        });
      }

      this.html5QrCode
        .start(
          { facingMode: 'environment' },
          {
            fps: 15,
            qrbox: (viewfinderWidth, viewfinderHeight) => {
              const minDim = Math.min(viewfinderWidth, viewfinderHeight);
              const boxDim = Math.max(200, Math.floor(minDim * 0.75));
              return { width: boxDim, height: boxDim };
            },
            aspectRatio: 1.0,
          },
          (decodedText) => this.handleDecodedQr(decodedText),
          () => {
            // Ignore normal frame misses
          },
        )
        .then(() => {
          this.isScanning.set(true);
          this.checkTorchCapability();
        })
        .catch((err: unknown) => {
          console.warn('Camera initiation failed:', err);
          const errorMsg =
            typeof err === 'string'
              ? err
              : (err as { message?: string })?.message ||
                'Unable to access camera. Please verify permissions or use manual entry.';
          this.cameraError.set(errorMsg);
          this.isScanning.set(false);
        });
    } catch (e: unknown) {
      console.warn('Error creating Html5Qrcode instance:', e);
      this.cameraError.set('Camera initialization failed on this device.');
      this.isScanning.set(false);
    }
  }

  stopCameraScanner(): void {
    if (this.html5QrCode && this.html5QrCode.isScanning) {
      this.html5QrCode
        .stop()
        .then(() => {
          this.isScanning.set(false);
        })
        .catch((err) => {
          console.warn('Failed to stop camera stream gracefully:', err);
          this.isScanning.set(false);
        });
    }
  }

  toggleCamera(): void {
    if (this.isScanning()) {
      this.stopCameraScanner();
    } else {
      this.startCameraScanner();
    }
  }

  handleDecodedQr(decodedText: string): void {
    if (this.isCooldownActive() || this.isValidating()) {
      return;
    }

    const now = Date.now();
    // Debounce exact same code within 3000ms
    if (decodedText === this.lastScannedCode && now - this.lastScanTime < 3000) {
      return;
    }

    this.lastScannedCode = decodedText;
    this.lastScanTime = now;

    const extractedCode = this.parseTicketCode(decodedText);
    this.validateCode(extractedCode);
  }

  parseTicketCode(raw: string): string {
    if (!raw) return '';
    const trimmed = raw.trim();

    // Check if input is deep URL: https://seatflow.app/tickets/guest/{ticketCode} (with optional query parameters)
    const match = trimmed.match(/\/tickets\/guest\/([A-Za-z0-9-]+)/);
    if (match && match[1]) {
      return match[1];
    }

    return trimmed;
  }

  validateCode(ticketCode: string): void {
    const code = this.parseTicketCode(ticketCode);
    if (!code || this.isValidating()) return;

    this.isValidating.set(true);

    this.scannerApi.validateTicket(code, this.deviceId()).subscribe({
      next: (res) => {
        this.currentResult.set(res);
        this.scanHistory.update((h) => [res, ...h.slice(0, 9)]);
        this.audioFeedback.playFeedback(res.result);
        this.isValidating.set(false);
        this.manualCode.set('');
        this.startCooldown(3);
      },
      error: (err) => {
        const errorResult: ValidationResultResponse = {
          valid: false,
          ticketCode: code,
          result: 'INVALID',
          scannedAt: new Date().toISOString(),
          message:
            err.error?.message ||
            'Ticket verification failed. Ticket may be unissued, invalid, or revoked.',
        };
        this.currentResult.set(errorResult);
        this.scanHistory.update((h) => [errorResult, ...h.slice(0, 9)]);
        this.audioFeedback.playFeedback('INVALID');
        this.isValidating.set(false);
        this.startCooldown(3);
      },
    });
  }

  submitManualCode(): void {
    if (this.manualCode().trim()) {
      this.validateCode(this.manualCode().trim());
    }
  }

  dismissResult(): void {
    this.currentResult.set(null);
    this.resetCooldown();
  }

  startCooldown(seconds = 3): void {
    this.resetCooldown();
    this.cooldownRemainingSeconds.set(seconds);
    this.cooldownInterval = setInterval(() => {
      const remaining = this.cooldownRemainingSeconds() - 1;
      if (remaining <= 0) {
        this.resetCooldown();
      } else {
        this.cooldownRemainingSeconds.set(remaining);
      }
    }, 1000);
  }

  resetCooldown(): void {
    if (this.cooldownInterval) {
      clearInterval(this.cooldownInterval);
      this.cooldownInterval = null;
    }
    this.cooldownRemainingSeconds.set(0);
  }

  inspectHistoryItem(item: ValidationResultResponse): void {
    this.currentResult.set(item);
  }

  clearHistory(): void {
    this.scanHistory.set([]);
  }

  testAudioTone(result: ScanResultType): void {
    this.audioFeedback.playFeedback(result);
  }

  private checkTorchCapability(): void {
    try {
      if (this.html5QrCode) {
        const track = (
          this.html5QrCode as unknown as {
            getRunningTrackCapabilities?: () => { torch?: boolean };
          }
        ).getRunningTrackCapabilities?.();
        if (track && track.torch) {
          this.hasTorch.set(true);
        }
      }
    } catch {
      this.hasTorch.set(false);
    }
  }

  toggleTorch(): void {
    if (!this.hasTorch() || !this.html5QrCode) return;
    try {
      const nextTorch = !this.isTorchOn();
      (
        this.html5QrCode as unknown as {
          applyVideoConstraints: (c: { advanced: [{ torch: boolean }] }) => Promise<void>;
        }
      )
        .applyVideoConstraints({
          advanced: [{ torch: nextTorch }],
        })
        .then(() => {
          this.isTorchOn.set(nextTorch);
        })
        .catch(console.warn);
    } catch (e) {
      console.warn('Torch toggle error:', e);
    }
  }

  ngOnDestroy(): void {
    this.resetCooldown();
    if (this.html5QrCode) {
      if (this.html5QrCode.isScanning) {
        this.html5QrCode
          .stop()
          .then(() => this.html5QrCode?.clear())
          .catch((err) => console.warn('Error stopping scanner during ngOnDestroy:', err));
      } else {
        this.html5QrCode.clear();
      }
    }
  }
}
