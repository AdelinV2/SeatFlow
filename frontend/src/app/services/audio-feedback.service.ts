import { Injectable, signal } from '@angular/core';
import { ScanResultType } from '../models/scanner.model';

@Injectable({ providedIn: 'root' })
export class AudioFeedbackService {
  private audioCtx: AudioContext | null = null;
  readonly isMuted = signal<boolean>(false);

  toggleMute(): boolean {
    this.isMuted.update((muted) => !muted);
    return this.isMuted();
  }

  setMuted(muted: boolean): void {
    this.isMuted.set(muted);
  }

  playFeedback(result: ScanResultType): void {
    if (!this.isMuted()) {
      if (result === 'SUCCESS') {
        this.playSuccessChime();
      } else if (result === 'ALREADY_USED') {
        this.playWarningTone();
      } else {
        this.playErrorBuzzer();
      }
    }

    // Trigger matching sensory haptic vibration
    if (result === 'SUCCESS') {
      this.triggerHaptic([100]);
    } else if (result === 'ALREADY_USED') {
      this.triggerHaptic([150, 50, 150]);
    } else {
      this.triggerHaptic([400]);
    }
  }

  /**
   * Radiant 3-note harmonic arpeggio for valid entries (880Hz -> 1174Hz -> 1568Hz)
   */
  private playSuccessChime(): void {
    this.playTone(880, 0.12, 0, 'sine', 0.25);
    this.playTone(1174.66, 0.14, 0.08, 'sine', 0.28);
    this.playTone(1567.98, 0.32, 0.16, 'sine', 0.32);
    // Subtle high triangle sparkle
    this.playTone(2093, 0.2, 0.2, 'triangle', 0.1);
  }

  /**
   * Dual sonar caution pulse for tickets that have already been scanned
   */
  private playWarningTone(): void {
    // Pulse 1
    this.playTone(659.25, 0.15, 0, 'sine', 0.3);
    this.playTone(440, 0.15, 0, 'triangle', 0.2);
    // Pulse 2
    this.playTone(659.25, 0.18, 0.18, 'sine', 0.3);
    this.playTone(440, 0.18, 0.18, 'triangle', 0.2);
  }

  /**
   * Authoritative, punchy, futuristic descending access-denied alarm
   */
  private playErrorBuzzer(): void {
    try {
      const ctx = this.getAudioContext();
      if (!ctx) return;

      const now = ctx.currentTime;

      // Pulse 1: Dissonant descending klaxon burst (0ms -> 180ms)
      this.playSweptTone(240, 140, 0.18, 0, 'sawtooth', 0.35);
      this.playSweptTone(170, 100, 0.18, 0, 'square', 0.2);

      // Pulse 2: Deeper second impact burst (200ms -> 440ms)
      this.playSweptTone(200, 90, 0.24, 0.2, 'sawtooth', 0.4);
      this.playSweptTone(140, 70, 0.24, 0.2, 'square', 0.25);
    } catch (e) {
      console.warn('Error playing error buzzer:', e);
    }
  }

  private getAudioContext(): AudioContext | null {
    if (typeof window === 'undefined') {
      return null;
    }

    if (!this.audioCtx) {
      const AudioContextClass =
        window.AudioContext ||
        (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      if (AudioContextClass) {
        try {
          this.audioCtx = new AudioContextClass();
        } catch (e) {
          console.warn('Unable to initialize Web Audio AudioContext:', e);
          return null;
        }
      }
    }

    if (this.audioCtx && this.audioCtx.state === 'suspended') {
      this.audioCtx.resume().catch(() => {});
    }

    return this.audioCtx;
  }

  private playTone(
    freq: number,
    duration: number,
    delay: number,
    type: OscillatorType = 'sine',
    peakVolume = 0.3,
  ): void {
    try {
      const ctx = this.getAudioContext();
      if (!ctx) return;

      const startTime = ctx.currentTime + delay;
      const endTime = startTime + duration;

      const osc = ctx.createOscillator();
      const gain = ctx.createGain();

      osc.type = type;
      osc.frequency.setValueAtTime(freq, startTime);

      // Smooth attack and exponential decay to prevent clicking artifacts
      gain.gain.setValueAtTime(0.0001, startTime);
      gain.gain.exponentialRampToValueAtTime(peakVolume, startTime + 0.015);
      gain.gain.exponentialRampToValueAtTime(0.0001, endTime);

      osc.connect(gain);
      gain.connect(ctx.destination);

      osc.onended = () => {
        try {
          osc.disconnect();
          gain.disconnect();
        } catch {
          // Ignore disconnect failures if already detached
        }
      };

      osc.start(startTime);
      osc.stop(endTime);
    } catch (e) {
      console.warn('Web Audio synthesis error:', e);
    }
  }

  private playSweptTone(
    startFreq: number,
    endFreq: number,
    duration: number,
    delay: number,
    type: OscillatorType = 'sawtooth',
    peakVolume = 0.3,
  ): void {
    try {
      const ctx = this.getAudioContext();
      if (!ctx) return;

      const startTime = ctx.currentTime + delay;
      const endTime = startTime + duration;

      const osc = ctx.createOscillator();
      const gain = ctx.createGain();

      osc.type = type;
      osc.frequency.setValueAtTime(startFreq, startTime);
      osc.frequency.exponentialRampToValueAtTime(Math.max(1, endFreq), endTime);

      gain.gain.setValueAtTime(0.0001, startTime);
      gain.gain.exponentialRampToValueAtTime(peakVolume, startTime + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, endTime);

      osc.connect(gain);
      gain.connect(ctx.destination);

      osc.onended = () => {
        try {
          osc.disconnect();
          gain.disconnect();
        } catch {
          // Ignore disconnect failures if already detached
        }
      };

      osc.start(startTime);
      osc.stop(endTime);
    } catch (e) {
      console.warn('Web Audio sweep synthesis error:', e);
    }
  }

  private triggerHaptic(pattern: number[]): void {
    if (typeof navigator !== 'undefined' && 'vibrate' in navigator) {
      try {
        navigator.vibrate(pattern);
      } catch {
        // Ignore haptic failures on unsupported platforms
      }
    }
  }
}
