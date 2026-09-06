import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription } from 'rxjs';
import { PagedResult } from '../../../../models/event.model';
import {
  AnalyticsMetric,
  AnalyticsSummary,
  AnalyticsTimeSeries,
  EventSessionAnalytics,
  MoneyMetric,
  RateMetric,
} from '../../../../models/admin-analytics.model';
import { AdminAnalyticsApiService } from '../../../../services/admin-analytics-api.service';
import { SkeletonLoaderComponent } from '../../../../shared/components/skeleton-loader/skeleton-loader.component';
import { AnalyticsLineChartComponent } from '../analytics-line-chart/analytics-line-chart.component';

export type WidgetState = 'idle' | 'loading' | 'success' | 'error';

const TREND_METRICS: readonly AnalyticsMetric[] = [
  'NET_REVENUE',
  'GROSS_REVENUE',
  'TICKETS_ISSUED',
  'TICKETS_SCANNED',
  'RESERVATIONS_CREATED',
  'PAYMENTS_SUCCEEDED',
];

const MONEY_METRICS: readonly AnalyticsMetric[] = ['NET_REVENUE', 'GROSS_REVENUE'];

@Component({
  selector: 'app-admin-analytics-dashboard',
  standalone: true,
  imports: [CommonModule, SkeletonLoaderComponent, AnalyticsLineChartComponent],
  templateUrl: './admin-analytics-dashboard.component.html',
  styleUrl: './admin-analytics-dashboard.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminAnalyticsDashboardComponent implements OnInit {
  private readonly api = inject(AdminAnalyticsApiService);
  private readonly destroyRef = inject(DestroyRef);

  readonly summaryState = signal<WidgetState>('idle');
  readonly trendState = signal<WidgetState>('idle');
  readonly sessionsState = signal<WidgetState>('idle');

  readonly summary = signal<AnalyticsSummary | null>(null);
  readonly trend = signal<AnalyticsTimeSeries | null>(null);
  readonly sessions = signal<PagedResult<EventSessionAnalytics> | null>(null);

  readonly selectedTrendMetric = signal<AnalyticsMetric>('NET_REVENUE');
  readonly trendMetricOptions: readonly AnalyticsMetric[] = TREND_METRICS;

  private trendRequestId = 0;
  private activeTrendSubscription: Subscription | null = null;

  readonly isMoneyTrend = computed(() =>
    (MONEY_METRICS as readonly string[]).includes(this.selectedTrendMetric()),
  );

  readonly summaryIsEmpty = computed(() => {
    const s = this.summary();
    if (!s) {
      return false;
    }
    return (
      s.reservations.created === 0 &&
      s.reservations.confirmed === 0 &&
      s.reservations.expired === 0 &&
      s.reservations.refunded === 0 &&
      s.tickets.issued === 0 &&
      s.tickets.scanned === 0 &&
      s.tickets.revoked === 0 &&
      s.payments.succeeded === 0 &&
      s.payments.refundsCompleted === 0 &&
      s.payments.revenueByCurrency.length === 0
    );
  });

  /** Contract discrepancy: backend unexpectedly reports non-test revenue.
   * Covers every rendered financial payload: summary, trend money series,
   * and session revenue rows. Count series (currency/testMode null) are not
   * financial payloads and must not trigger this state. */
  readonly hasNonTestRevenue = computed(() => {
    const summaryNonTest = (this.summary()?.payments.revenueByCurrency ?? []).some(
      (m) => m.testMode !== true,
    );
    if (summaryNonTest) {
      return true;
    }
    const trendNonTest = (this.trend()?.series ?? []).some(
      (s) => s.currency !== null && (s.testMode as boolean | null) !== true,
    );
    if (trendNonTest) {
      return true;
    }
    return (this.sessions()?.content ?? []).some((row) =>
      (row.revenueByCurrency ?? []).some((m) => m.testMode !== true),
    );
  });

  readonly freshnessText = computed(() => {
    const last = this.summary()?.freshness.lastProjectedEventAt ?? null;
    return last ?? null;
  });

  ngOnInit(): void {
    this.loadSummary();
    this.loadTrend();
    this.loadSessions();
  }

  loadSummary(): void {
    this.summaryState.set('loading');
    this.api
      .getSummary()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (res) => {
          this.summary.set(res);
          this.summaryState.set('success');
        },
        error: () => {
          this.summaryState.set('error');
        },
      });
  }

  loadTrend(): void {
    this.trendRequestId += 1;
    const requestId = this.trendRequestId;
    this.activeTrendSubscription?.unsubscribe();
    this.trendState.set('loading');
    this.activeTrendSubscription = this.api
      .getTimeSeries(this.selectedTrendMetric())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (res) => {
          if (requestId !== this.trendRequestId) {
            return;
          }
          this.trend.set(res);
          this.trendState.set('success');
        },
        error: () => {
          if (requestId !== this.trendRequestId) {
            return;
          }
          this.trendState.set('error');
        },
      });
  }

  loadSessions(): void {
    this.sessionsState.set('loading');
    this.api
      .getSessions({ page: 0, size: 25, sort: 'startsAt,desc' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (res) => {
          this.sessions.set(res);
          this.sessionsState.set('success');
        },
        error: () => {
          this.sessionsState.set('error');
        },
      });
  }

  selectTrendMetric(metric: AnalyticsMetric): void {
    if (metric === this.selectedTrendMetric()) {
      return;
    }
    this.selectedTrendMetric.set(metric);
    this.loadTrend();
  }

  formatMoney(minor: number, currency: string): string {
    try {
      const probe = new Intl.NumberFormat(undefined, { style: 'currency', currency });
      const fractionDigits = probe.resolvedOptions().maximumFractionDigits ?? 2;
      const major = minor / Math.pow(10, fractionDigits);
      return new Intl.NumberFormat(undefined, {
        style: 'currency',
        currency,
      }).format(major);
    } catch {
      return `${minor} ${currency}`;
    }
  }

  formatChartValue = (value: number, currency: string | null): string => {
    if (currency) {
      return this.formatMoney(value, currency);
    }
    return new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 }).format(value);
  };

  formatRate(rate: RateMetric | null | undefined): string {
    if (!rate || rate.ratio === null || rate.ratio === undefined) {
      return '—';
    }
    return new Intl.NumberFormat(undefined, {
      style: 'percent',
      maximumFractionDigits: 1,
    }).format(rate.ratio);
  }

  formatRatio(ratio: number | null | undefined): string {
    if (ratio === null || ratio === undefined) {
      return '—';
    }
    return new Intl.NumberFormat(undefined, {
      style: 'percent',
      maximumFractionDigits: 1,
    }).format(ratio);
  }

  rateDetail(rate: RateMetric | null | undefined): string {
    if (!rate) {
      return 'Not available';
    }
    if (rate.ratio === null || rate.ratio === undefined) {
      return 'Not available (no cohort)';
    }
    return `${rate.numerator} of ${rate.denominator}`;
  }

  sessionLabel(session: EventSessionAnalytics): string {
    if (session.eventTitle && session.eventTitle.trim() !== '') {
      return session.eventTitle;
    }
    if (session.sessionLabel && session.sessionLabel.trim() !== '') {
      return session.sessionLabel;
    }
    return this.shortenId(session.eventSessionId);
  }

  shortenId(id: string): string {
    return id.length > 8 ? `${id.slice(0, 8)}…` : id;
  }

  revenueFor(session: EventSessionAnalytics): MoneyMetric[] {
    return session.revenueByCurrency ?? [];
  }

  trackByCurrency(_index: number, item: MoneyMetric): string {
    return item.currency;
  }

  trackBySessionId(_index: number, item: EventSessionAnalytics): string {
    return item.eventSessionId;
  }
}
