import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  input,
  OnDestroy,
  output,
  signal,
  untracked,
} from '@angular/core';
import { NgClass } from '@angular/common';

@Component({
  selector: 'app-hold-countdown',
  standalone: true,
  imports: [NgClass],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './hold-countdown.component.html',
  styleUrl: './hold-countdown.component.scss',
})
export class HoldCountdownComponent implements OnDestroy {
  readonly expiresAt = input.required<string | Date>();
  readonly totalDurationSeconds = input(900);
  readonly expired = output<void>();

  readonly remainingSeconds = signal(0);
  readonly circleCircumference = 2 * Math.PI * 18;

  readonly formattedTime = computed(() => {
    const totalSeconds = this.remainingSeconds();
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    return `${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}`;
  });

  readonly isUrgent = computed(() => {
    const remaining = this.remainingSeconds();
    return remaining > 0 && remaining < 120;
  });

  readonly progressPercentage = computed(() => {
    const duration = Math.max(1, this.totalDurationSeconds());
    return Math.min(100, Math.max(0, (this.remainingSeconds() / duration) * 100));
  });

  readonly strokeDashoffset = computed(
    () => this.circleCircumference * (1 - this.progressPercentage() / 100),
  );

  private timerId?: ReturnType<typeof setInterval>;
  private lastEmittedExpiry?: number;

  constructor() {
    effect((onCleanup) => {
      const expiry = new Date(this.expiresAt()).getTime();
      this.totalDurationSeconds();
      this.clearTimer();

      if (!Number.isFinite(expiry)) {
        untracked(() => this.remainingSeconds.set(0));
        return;
      }

      const tick = (): void => {
        const difference = Math.max(0, expiry - Date.now());
        const remaining = Math.ceil(difference / 1000);
        this.remainingSeconds.set(remaining);

        if (remaining === 0) {
          this.clearTimer();
          if (this.lastEmittedExpiry !== expiry) {
            this.lastEmittedExpiry = expiry;
            this.expired.emit();
          }
        }
      };

      untracked(() => tick());
      if (untracked(() => this.remainingSeconds()) > 0) {
        this.timerId = setInterval(tick, 1000);
      }

      onCleanup(() => this.clearTimer());
    });
  }

  ngOnDestroy(): void {
    this.clearTimer();
  }

  private clearTimer(): void {
    if (this.timerId !== undefined) {
      clearInterval(this.timerId);
      this.timerId = undefined;
    }
  }
}
