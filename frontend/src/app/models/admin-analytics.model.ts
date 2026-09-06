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
