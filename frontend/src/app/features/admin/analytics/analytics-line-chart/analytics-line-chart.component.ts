import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AnalyticsSeries } from '../../../../models/admin-analytics.model';

export interface ChartRenderSeries {
  label: string;
  currency: string | null;
  pointsAttr: string;
  dots: { x: number; y: number; value: number; date: string }[];
  dashArray: string;
}

const CHART_WIDTH = 720;
const CHART_HEIGHT = 260;
const PAD_LEFT = 44;
const PAD_RIGHT = 16;
const PAD_TOP = 16;
const PAD_BOTTOM = 32;

const DASH_PATTERNS = ['', '6 4', '2 3', '8 3 2 3'];

@Component({
  selector: 'app-analytics-line-chart',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './analytics-line-chart.component.html',
  styleUrl: './analytics-line-chart.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AnalyticsLineChartComponent {
  readonly seriesList = input<readonly AnalyticsSeries[]>([]);
  readonly chartTitle = input<string>('Analytics trend');
  readonly chartDescription = input<string>('Daily analytics time series');
  readonly formatValue = input<(value: number, currency: string | null) => string>((v) => String(v));

  readonly chartWidth = CHART_WIDTH;
  readonly chartHeight = CHART_HEIGHT;

  /** Max value across all series; zero baseline always included. Never zero divisor. */
  readonly maxValue = computed(() => {
    let max = 0;
    for (const s of this.seriesList()) {
      for (const p of s.points) {
        if (p.value > max) {
          max = p.value;
        }
      }
    }
    return max;
  });

  readonly divisor = computed(() => (this.maxValue() > 0 ? this.maxValue() : 1));

  readonly pointCount = computed(() => {
    let n = 0;
    for (const s of this.seriesList()) {
      n = Math.max(n, s.points.length);
    }
    return n;
  });

  readonly xFor = computed(() => {
    const n = this.pointCount();
    const inner = CHART_WIDTH - PAD_LEFT - PAD_RIGHT;
    return (index: number): number => {
      if (n <= 1) {
        return PAD_LEFT + inner / 2;
      }
      return PAD_LEFT + (index / (n - 1)) * inner;
    };
  });

  readonly yFor = computed(() => {
    const div = this.divisor();
    const inner = CHART_HEIGHT - PAD_TOP - PAD_BOTTOM;
    return (value: number): number => {
      const clamped = value < 0 ? 0 : value;
      return PAD_TOP + inner * (1 - clamped / div);
    };
  });

  readonly renderSeries = computed<ChartRenderSeries[]>(() => {
    const xFor = this.xFor();
    const yFor = this.yFor();
    return this.seriesList().map((s, si) => {
      const label = s.currency ?? 'count';
      const dots = s.points.map((p, i) => ({ x: xFor(i), y: yFor(p.value), value: p.value, date: p.date }));
      const pointsAttr = dots.map((d) => `${d.x.toFixed(2)},${d.y.toFixed(2)}`).join(' ');
      return {
        label,
        currency: s.currency,
        pointsAttr,
        dots,
        dashArray: DASH_PATTERNS[si % DASH_PATTERNS.length],
      };
    });
  });

  readonly baselineY = computed(() => this.yFor()(0));

  readonly yTicks = computed(() => {
    const max = this.maxValue();
    const yFor = this.yFor();
    const ticks = [0];
    if (max > 0) {
      ticks.push(Math.round(max / 2), max);
    }
    return ticks.map((v) => ({ value: v, y: yFor(v) }));
  });

  readonly xLabels = computed(() => {
    const xFor = this.xFor();
    const first = this.seriesList()[0];
    if (!first || first.points.length === 0) {
      return [];
    }
    const pts = first.points;
    const n = pts.length;
    const idxs = n <= 7 ? pts.map((_, i) => i) : [0, Math.floor((n - 1) / 2), n - 1];
    return idxs.map((i) => ({ date: pts[i].date, x: xFor(i) }));
  });

  readonly isEmpty = computed(() => this.pointCount() === 0);

  /** Full date list from the longest series for the data-table equivalent. */
  readonly tableRows = computed(() => {
    let longest: readonly { date: string; value: number }[] = [];
    for (const s of this.seriesList()) {
      if (s.points.length > longest.length) {
        longest = s.points;
      }
    }
    return longest.map((p, i) => ({ index: i, date: p.date }));
  });
}
