/**
 * Typed frontend mirror of P14-004 admin analytics REST contracts.
 *
 * Backend source: analytics-service
 * `AdminAnalyticsController` + `web/dto/response/*`.
 * Field names mirror the backend records EXACTLY (camelCase JSON).
 *
 * Money stays in integer minor units end-to-end; formatting happens only at
 * display time via `Intl.NumberFormat`. Rate `ratio` is `number | null` where
 * `null` means "no cohort" and must render as unavailable, never 0%.
 */

export interface MoneyMetric {
  currency: string;
  grossMinor: number;
  refundedMinor: number;
  netMinor: number;
  testMode: boolean;
}

export interface RateMetric {
  numerator: number;
  denominator: number;
  /** Decimal ratio 0..1 with up to 6 places; null when denominator is zero. */
  ratio: number | null;
}

export interface ProjectionFreshness {
  generatedAt: string;
  lastProjectedEventAt: string | null;
  lastProcessedAt: string | null;
  eventuallyConsistent: boolean;
}

export type AnalyticsMetric =
  | 'GROSS_REVENUE'
  | 'NET_REVENUE'
  | 'TICKETS_ISSUED'
  | 'TICKETS_SCANNED'
  | 'RESERVATIONS_CREATED'
  | 'PAYMENTS_SUCCEEDED';

export type AnalyticsTopMetric =
  | 'NET_REVENUE'
  | 'TICKETS_ISSUED'
  | 'TICKETS_SCANNED'
  | 'RESERVATIONS_CONFIRMED';

export interface AnalyticsSummaryFilters {
  eventId: string | null;
  eventSessionId: string | null;
}

export interface AnalyticsReservationSummary {
  created: number;
  confirmed: number;
  expired: number;
  refunded: number;
}

export interface AnalyticsTicketSummary {
  issued: number;
  revoked: number;
  scanned: number;
}

export interface AnalyticsPaymentSummary {
  succeeded: number;
  withFailure: number;
  refundsCompleted: number;
  revenueByCurrency: MoneyMetric[];
}

export interface AnalyticsRates {
  reservationToPayment: RateMetric;
  expiration: RateMetric;
  refund: RateMetric;
}

export interface AnalyticsSummary {
  from: string;
  to: string;
  filters: AnalyticsSummaryFilters;
  reservations: AnalyticsReservationSummary;
  tickets: AnalyticsTicketSummary;
  payments: AnalyticsPaymentSummary;
  rates: AnalyticsRates;
  freshness: ProjectionFreshness;
}

export interface AnalyticsDailyPoint {
  date: string;
  /** Count, or minor-unit money for financial series. */
  value: number;
}

export interface AnalyticsCountSeries {
  currency: null;
  testMode: null;
  points: AnalyticsDailyPoint[];
}

export interface AnalyticsMoneySeries {
  currency: string;
  testMode: boolean;
  points: AnalyticsDailyPoint[];
}

export type AnalyticsSeries = AnalyticsCountSeries | AnalyticsMoneySeries;

export interface AnalyticsTimeSeries {
  metric: string;
  from: string;
  to: string;
  series: AnalyticsSeries[];
}

export interface EventSessionAnalytics {
  eventId: string;
  eventSessionId: string;
  eventTitle: string | null;
  sessionLabel: string | null;
  startsAt: string | null;
  status: string | null;
  capacitySnapshot: number | null;
  reservationsCreated: number;
  reservationsConfirmed: number;
  reservationsExpired: number;
  paymentsSucceeded: number;
  paymentsWithFailure: number;
  refundsCompleted: number;
  ticketsIssued: number;
  ticketsRevoked: number;
  ticketsScanned: number;
  revenueByCurrency: MoneyMetric[];
  occupancyRatio: number | null;
  attendanceRatio: number | null;
  lastProjectedEventAt: string | null;
}

export interface TopAnalyticsItem {
  eventId: string;
  eventSessionId: string;
  eventTitle: string | null;
  sessionLabel: string | null;
  startsAt: string | null;
  metric: string;
  value: number;
  currency: string | null;
}

export interface AnalyticsQueryParams {
  from?: string;
  to?: string;
  eventId?: string;
  eventSessionId?: string;
}

export interface AnalyticsSessionQueryParams extends AnalyticsQueryParams {
  page?: number;
  size?: number;
  sort?: string;
  currency?: string;
}

export interface AnalyticsEventFilterOption {
  eventId: string;
  /** Analytics-owned title snapshot; null until a trusted lifecycle event provides it. */
  label: string | null;
  firstProjectedSessionStart: string | null;
}

export interface AnalyticsSessionFilterOption {
  eventSessionId: string;
  eventId: string;
  /** Analytics-owned label snapshot; null until a trusted lifecycle event provides it. */
  label: string | null;
  startsAt: string | null;
}

/**
 * Bounded filter-options envelope (TASK-P14-006 §6.1). At most 500 items;
 * `truncated` tells the dashboard to warn instead of silently hiding options.
 */
export interface AnalyticsFilterOptions<T> {
  items: T[];
  totalProjected: number;
  truncated: boolean;
}

export type AnalyticsEventFilterOptions = AnalyticsFilterOptions<AnalyticsEventFilterOption>;
export type AnalyticsSessionFilterOptions =
  AnalyticsFilterOptions<AnalyticsSessionFilterOption>;

/**
 * Currently applied dashboard filters (TASK-P14-006 §6.4). The dashboard keeps
 * draft form state separate; CSV export and displayed results always use these
 * applied values. Dates are UTC `YYYY-MM-DD` strings sent unchanged.
 */
export interface AnalyticsAppliedFilters {
  fromDate: string;
  toDate: string;
  eventId: string | null;
  eventSessionId: string | null;
  trendMetric: AnalyticsMetric;
  sessionPage: number;
  sessionPageSize: number;
}

export const ANALYTICS_SESSION_PAGE_SIZES: readonly number[] = [10, 25, 50, 100];
export const DEFAULT_ANALYTICS_PAGE_SIZE = 25;
export const DEFAULT_ANALYTICS_TREND_METRIC: AnalyticsMetric = 'NET_REVENUE';
export const ANALYTICS_FILTER_OPTIONS_TRUNCATED_MESSAGE =
  'Showing first 500 options — narrow the date range to see more.';
export const ANALYTICS_EXPORT_TOO_LARGE_MESSAGE =
  'Export is too large (over 10,000 rows). Narrow the date range or filters and retry.';
