import { NgClass } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

export type StatusBadgeStatus =
  | 'AVAILABLE'
  | 'HELD'
  | 'SOLD'
  | 'RELEASED'
  | 'DRAFT'
  | 'PUBLISHED'
  | 'COMPLETED'
  | 'PENDING'
  | 'CONFIRMED'
  | 'EXPIRED'
  | 'INITIATED'
  | 'SUCCESS'
  | 'FAILED'
  | 'REFUNDED'
  | 'VALID'
  | 'USED'
  | 'ALREADY_USED'
  | 'INVALID'
  | 'CANCELLED';

interface StatusBadgeConfig {
  readonly label: string;
  readonly classes: string;
  readonly dotClasses: string;
}

const STATUS_CONFIG: Record<StatusBadgeStatus, StatusBadgeConfig> = {
  AVAILABLE: {
    label: 'Available',
    classes: 'border-emerald-500/30 bg-emerald-500/10 text-emerald-600 dark:text-emerald-400',
    dotClasses: 'bg-emerald-500',
  },
  HELD: {
    label: 'Held',
    classes: 'border-amber-500/30 bg-amber-500/10 text-amber-600 dark:text-amber-400',
    dotClasses: 'bg-amber-500',
  },
  SOLD: {
    label: 'Sold',
    classes: 'border-slate-500/30 bg-slate-500/10 text-slate-600 dark:text-slate-400',
    dotClasses: 'bg-slate-500',
  },
  RELEASED: {
    label: 'Released',
    classes: 'border-slate-500/30 bg-slate-500/10 text-slate-600 dark:text-slate-400',
    dotClasses: 'bg-slate-500',
  },
  DRAFT: {
    label: 'Draft',
    classes: 'border-slate-500/30 bg-slate-500/10 text-slate-600 dark:text-slate-400',
    dotClasses: 'bg-slate-400',
  },
  PUBLISHED: {
    label: 'Published',
    classes: 'border-indigo-500/30 bg-indigo-500/10 text-indigo-600 dark:text-indigo-400',
    dotClasses: 'bg-indigo-500',
  },
  COMPLETED: {
    label: 'Completed',
    classes: 'border-slate-500/30 bg-slate-500/10 text-slate-600 dark:text-slate-400',
    dotClasses: 'bg-slate-500',
  },
  PENDING: {
    label: 'Pending',
    classes: 'border-amber-500/30 bg-amber-500/10 text-amber-600 dark:text-amber-400',
    dotClasses: 'bg-amber-500',
  },
  CONFIRMED: {
    label: 'Confirmed',
    classes: 'border-emerald-500/30 bg-emerald-500/10 text-emerald-600 dark:text-emerald-400',
    dotClasses: 'bg-emerald-500',
  },
  EXPIRED: {
    label: 'Expired',
    classes: 'border-rose-500/30 bg-rose-500/10 text-rose-600 dark:text-rose-400',
    dotClasses: 'bg-rose-500',
  },
  INITIATED: {
    label: 'Initiated',
    classes: 'border-amber-500/30 bg-amber-500/10 text-amber-600 dark:text-amber-400',
    dotClasses: 'bg-amber-500',
  },
  SUCCESS: {
    label: 'Success',
    classes: 'border-emerald-500/30 bg-emerald-500/10 text-emerald-600 dark:text-emerald-400',
    dotClasses: 'bg-emerald-500',
  },
  FAILED: {
    label: 'Failed',
    classes: 'border-rose-500/30 bg-rose-500/10 text-rose-600 dark:text-rose-400',
    dotClasses: 'bg-rose-500',
  },
  REFUNDED: {
    label: 'Refunded',
    classes: 'border-slate-500/30 bg-slate-500/10 text-slate-600 dark:text-slate-400',
    dotClasses: 'bg-slate-500',
  },
  VALID: {
    label: 'Valid',
    classes: 'border-emerald-500/30 bg-emerald-500/10 text-emerald-600 dark:text-emerald-400',
    dotClasses: 'bg-emerald-500',
  },
  USED: {
    label: 'Used',
    classes: 'border-amber-500/30 bg-amber-500/10 text-amber-600 dark:text-amber-400',
    dotClasses: 'bg-amber-500',
  },
  ALREADY_USED: {
    label: 'Already Used',
    classes: 'border-amber-500/30 bg-amber-500/10 text-amber-600 dark:text-amber-400',
    dotClasses: 'bg-amber-500',
  },
  INVALID: {
    label: 'Invalid',
    classes: 'border-rose-500/30 bg-rose-500/10 text-rose-600 dark:text-rose-400',
    dotClasses: 'bg-rose-500',
  },
  CANCELLED: {
    label: 'Cancelled',
    classes: 'border-rose-500/30 bg-rose-500/10 text-rose-600 dark:text-rose-400',
    dotClasses: 'bg-rose-500',
  },
};

const UNKNOWN_STATUS: StatusBadgeConfig = {
  label: 'Unknown',
  classes: 'border-slate-500/30 bg-slate-500/10 text-slate-600 dark:text-slate-400',
  dotClasses: 'bg-slate-500',
};

@Component({
  selector: 'app-status-badge',
  standalone: true,
  imports: [NgClass],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span
      class="inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-[11px] font-bold uppercase tracking-wide"
      [ngClass]="configuration().classes"
      [attr.aria-label]="'Status: ' + configuration().label"
    >
      <span
        class="size-1.5 rounded-full"
        [ngClass]="configuration().dotClasses"
        aria-hidden="true"
      ></span>
      {{ configuration().label }}
    </span>
  `,
})
export class StatusBadgeComponent {
  readonly status = input.required<string>();

  readonly configuration = computed<StatusBadgeConfig>(() => {
    const normalizedStatus = this.status().trim().toUpperCase() as StatusBadgeStatus;
    return STATUS_CONFIG[normalizedStatus] ?? UNKNOWN_STATUS;
  });
}
