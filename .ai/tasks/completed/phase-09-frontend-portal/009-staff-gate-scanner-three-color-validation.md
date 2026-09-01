# TASK-P09-009: Staff Gate Scanner, Web Audio Chimes & 3-Color Sensory Feedback Matrix

## 1. Task Metadata
- **Task ID:** `TASK-P09-009`
- **Git Branch:** `feat/p09-009-staff-qr-scanner`
- **Target Module:** `frontend/src/app/features/scanner/`, `frontend/src/app/services/`
- **Phase:** `Phase 09 - Frontend Portal`
- **Related Specs:** `.ai/architecture/06-api-contracts.md` (Section 2.6), `.ai/architecture/07-frontend-specification.md` (Section 4.4), `frontend/AGENTS.md`
- **Related ADRs:** `ADR-005` (Venue Gate Check-In Authorization and Dedicated Staff Scanner Flow)
- **Status:** `COMPLETED`

---

## 2. Objective & Invariants
Implement the dedicated staff gate check-in scanner at `/scanner`, guarded by `staff.guard.ts` (`ROLE_STAFF` or `ROLE_ADMIN` per ADR-005). The component activates the device camera via `html5-qrcode`, detects QR codes, extracts the ticket code from deep-link URLs (`https://seatflow.app/tickets/guest/{ticketCode}`) or raw strings, validates tickets via `POST /api/scanner/tickets/validate`, and triggers the **3-Color Sensory Feedback Matrix** with Web Audio synth chimes and haptic feedback.

### Critical Invariants to Enforce:
- [x] **Role Authorization Guard (ADR-005):** `/scanner` route is strictly protected by `staff.guard.ts` (permitting `ROLE_STAFF` and `ROLE_ADMIN` only).
- [x] **3-Color Sensory Feedback Matrix:**
  - 🟢 **SUCCESS (Emerald `#10B981`):** *Entry Granted* + high confirmation chime + short haptic vibration (`100ms`). Displays attendee name, section, row, seat.
  - 🟡 **ALREADY_USED (Amber `#F59E0B`):** *Ticket Already Scanned* + double warning beep + double vibration (`150ms-50ms-150ms`). Displays initial scan timestamp and gate device ID.
  - 🔴 **INVALID / CANCELLED (Rose `#F43F5E`):** *Invalid / Revoked Ticket* + low buzz tone + long alert vibration (`400ms`). Displays rejection reason.
- [x] **Zero Audio Asset Dependency (Web Audio API Synth):** Synthesize all tones (success chime, warning beep, error buzz) in real time using the browser Web Audio API (`AudioContext`) to prevent audio loading failures in offline/flaky network conditions.
- [x] **Deep-Link URL & Raw Code Parser:** Scanner must seamlessly extract the alphanumeric ticket code whether the QR contains a full URL (`https://seatflow.app/tickets/guest/SF-TKT-1234`) or a raw string (`SF-TKT-1234`).
- [x] **Manual Alphanumeric Fallback:** An accessible manual input box allows typing damaged ticket codes with immediate validation.

---

## 3. Exact File Inventory
- `[NEW]` `frontend/src/app/models/scanner.model.ts`
- `[NEW]` `frontend/src/app/services/scanner-api.service.ts`
- `[NEW]` `frontend/src/app/services/audio-feedback.service.ts`
- `[NEW]` `frontend/src/app/features/scanner/staff-scanner/staff-scanner.component.ts`
- `[NEW]` `frontend/src/app/features/scanner/staff-scanner/staff-scanner.component.html`
- `[NEW]` `frontend/src/app/features/scanner/staff-scanner/staff-scanner.component.scss`
- `[NEW]` `frontend/src/app/features/scanner/validation-result-card/validation-result-card.component.ts`
- `[NEW]` `frontend/src/app/features/scanner/validation-result-card/validation-result-card.component.html`
- `[NEW]` `frontend/src/app/features/scanner/validation-result-card/validation-result-card.component.scss`
- `[NEW]` `frontend/src/app/services/scanner-api.service.spec.ts`
- `[NEW]` `frontend/src/app/services/audio-feedback.service.spec.ts`
- `[NEW]` `frontend/src/app/features/scanner/staff-scanner/staff-scanner.component.spec.ts`
- `[NEW]` `frontend/src/app/features/scanner/validation-result-card/validation-result-card.component.spec.ts`
- `[MODIFY]` `frontend/src/app/app.routes.ts`

---

## 4. Technical Specifications & Contracts

### 4.1 Models & API Service (`src/app/services/scanner-api.service.ts`)

```typescript
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
  scannedAt: string;
  firstScannedAt?: string;
  firstScannedDevice?: string;
  message: string;
}
```

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ValidateTicketRequest, ValidationResultResponse } from '../models/scanner.model';

@Injectable({ providedIn: 'root' })
export class ScannerApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/scanner/tickets';

  validateTicket(ticketCode: string, scannerDeviceId: string): Observable<ValidationResultResponse> {
    const payload: ValidateTicketRequest = { ticketCode, scannerDeviceId };
    return this.http.post<ValidationResultResponse>(`${this.baseUrl}/validate`, payload);
  }
}
```

### 4.2 Web Audio Feedback Service (`src/app/services/audio-feedback.service.ts`)

```typescript
import { Injectable } from '@angular/core';
import { ScanResultType } from '../models/scanner.model';

@Injectable({ providedIn: 'root' })
export class AudioFeedbackService {
  private audioCtx: AudioContext | null = null;

  private getAudioContext(): AudioContext {
    if (!this.audioCtx) {
      const AudioContextClass = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      this.audioCtx = new AudioContextClass();
    }
    if (this.audioCtx.state === 'suspended') {
      this.audioCtx.resume();
    }
    return this.audioCtx;
  }

  playFeedback(result: ScanResultType): void {
    const ctx = this.getAudioContext();

    if (result === 'SUCCESS') {
      // Pleasant high double chime (880Hz -> 1320Hz)
      this.playTone(880, 0.1, 0);
      this.playTone(1320, 0.25, 0.12);
      this.triggerHaptic([100]);
    } else if (result === 'ALREADY_USED') {
      // Double warning beep (587Hz -> 587Hz)
      this.playTone(587, 0.15, 0);
      this.playTone(587, 0.15, 0.2);
      this.triggerHaptic([150, 50, 150]);
    } else {
      // Low error buzz (180Hz sawtooth)
      this.playTone(180, 0.4, 0, 'sawtooth');
      this.triggerHaptic([400]);
    }
  }

  private playTone(freq: number, duration: number, delay: number, type: OscillatorType = 'sine'): void {
    try {
      const ctx = this.getAudioContext();
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();

      osc.type = type;
      osc.frequency.setValueAtTime(freq, ctx.currentTime + delay);

      gain.gain.setValueAtTime(0.3, ctx.currentTime + delay);
      gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + delay + duration);

      osc.connect(gain);
      gain.connect(ctx.destination);

      osc.start(ctx.currentTime + delay);
      osc.stop(ctx.currentTime + delay + duration);
    } catch (e) {
      console.warn('Web Audio playback error:', e);
    }
  }

  private triggerHaptic(pattern: number[]): void {
    if (typeof navigator !== 'undefined' && 'vibrate' in navigator) {
      navigator.vibrate(pattern);
    }
  }
}
```

### 4.3 Staff Scanner Component (`src/app/features/scanner/staff-scanner/`)

```typescript
import { Component, ChangeDetectionStrategy, inject, signal, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Html5Qrcode, Html5QrcodeSupportedFormats } from 'html5-qrcode';
import { ScannerApiService } from '../../../services/scanner-api.service';
import { AudioFeedbackService } from '../../../services/audio-feedback.service';
import { ValidationResultResponse } from '../../../models/scanner.model';
import { ValidationResultCardComponent } from '../validation-result-card/validation-result-card.component';
import { TactileButtonComponent } from '../../../shared/components/tactile-button/tactile-button.component';

@Component({
  selector: 'app-staff-scanner',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    ValidationResultCardComponent,
    TactileButtonComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './staff-scanner.component.html',
  styleUrl: './staff-scanner.component.scss',
})
export class StaffScannerComponent implements OnInit, OnDestroy {
  private readonly scannerApi = inject(ScannerApiService);
  private readonly audioFeedback = inject(AudioFeedbackService);

  readonly deviceId = signal<string>('GATE-SCANNER-' + Math.floor(Math.random() * 9000 + 1000));
  readonly isScanning = signal<boolean>(false);
  readonly isValidating = signal<boolean>(false);
  readonly currentResult = signal<ValidationResultResponse | null>(null);
  readonly scanHistory = signal<ValidationResultResponse[]>([]);
  readonly manualCode = signal<string>('');

  private html5QrCode?: Html5Qrcode;
  private lastScannedCode = '';
  private lastScanTime = 0;

  ngOnInit(): void {
    this.startCameraScanner();
  }

  startCameraScanner(): void {
    this.html5QrCode = new Html5Qrcode('qr-reader-container', {
      formatsToSupport: [Html5QrcodeSupportedFormats.QR_CODE],
      verbose: false,
    });

    this.html5QrCode.start(
      { facingMode: 'environment' },
      { fps: 15, qrbox: { width: 260, height: 260 } },
      (decodedText) => this.handleDecodedQr(decodedText),
      () => {} // ignore frame misses
    ).then(() => {
      this.isScanning.set(true);
    }).catch((err) => {
      console.warn('Camera initiation failed:', err);
    });
  }

  handleDecodedQr(decodedText: string): void {
    const now = Date.now();
    // Debounce exact same code within 3 seconds
    if (decodedText === this.lastScannedCode && now - this.lastScanTime < 3000) {
      return;
    }

    this.lastScannedCode = decodedText;
    this.lastScanTime = now;

    const extractedCode = this.parseTicketCode(decodedText);
    this.validateCode(extractedCode);
  }

  parseTicketCode(raw: string): string {
    // Check if input is deep URL: https://seatflow.app/tickets/guest/{ticketCode}
    const match = raw.match(/\/tickets\/guest\/([A-Za-z0-9-]+)/);
    if (match && match[1]) {
      return match[1];
    }
    return raw.trim();
  }

  validateCode(ticketCode: string): void {
    if (!ticketCode || this.isValidating()) return;

    this.isValidating.set(true);
    this.scannerApi.validateTicket(ticketCode, this.deviceId()).subscribe({
      next: (res) => {
        this.currentResult.set(res);
        this.scanHistory.update((h) => [res, ...h.slice(0, 9)]);
        this.audioFeedback.playFeedback(res.result);
        this.isValidating.set(false);
        this.manualCode.set('');
      },
      error: (err) => {
        const errorResult: ValidationResultResponse = {
          valid: false,
          ticketCode,
          result: 'INVALID',
          scannedAt: new Date().toISOString(),
          message: err.error?.message || 'Verification rejected by server.',
        };
        this.currentResult.set(errorResult);
        this.scanHistory.update((h) => [errorResult, ...h.slice(0, 9)]);
        this.audioFeedback.playFeedback('INVALID');
        this.isValidating.set(false);
      },
    });
  }

  submitManualCode(): void {
    if (this.manualCode().trim()) {
      this.validateCode(this.manualCode().trim());
    }
  }

  ngOnDestroy(): void {
    if (this.html5QrCode?.isScanning) {
      this.html5QrCode.stop().catch(console.error);
    }
  }
}
```

---

## 5. Step-by-Step Implementation Sequence
1. **Define Scanner Models and API Service:**
   - Create `src/app/models/scanner.model.ts` and `src/app/services/scanner-api.service.ts` invoking `POST /api/scanner/tickets/validate`.
2. **Build AudioFeedbackService using Web Audio API:**
   - Implement oscillator-based tone synthesis for `SUCCESS` (double high chime), `ALREADY_USED` (double warning tone), and `INVALID` (low sawtooth buzz).
   - Add `navigator.vibrate` haptic triggers.
3. **Build ValidationResultCardComponent:**
   - Visual card with color states: Emerald background for `SUCCESS`, Amber for `ALREADY_USED`, Rose for `INVALID`.
   - Render seat coordinates, attendee name, and scan timestamp details.
4. **Implement StaffScannerComponent View:**
   - Viewport video container (`#qr-reader-container`).
   - Manual alphanumeric fallback input box.
   - Scan history table showing last 10 attempts.
5. **Configure Staff Guard Route & Write Unit Tests:**
   - Guard `/scanner` in `app.routes.ts` with `staffGuard`.
   - Unit tests for QR code string extraction, API validation dispatch, and audio synthesis triggers.

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
```
- [x] `/scanner` is accessible only to users with `ROLE_STAFF` or `ROLE_ADMIN`.
- [x] HTML5 camera stream parses QR codes and extracts codes from deep URLs.
- [x] 3-Color Sensory Feedback Matrix triggers matching visual styles, Web Audio tones, and haptic vibrations.
- [x] Manual alphanumeric entry validates tickets as fallback.
- [x] All unit tests pass cleanly.
- [x] Task file is moved to `.ai/tasks/completed/phase-09-frontend-portal/009-staff-gate-scanner-three-color-validation.md`.
