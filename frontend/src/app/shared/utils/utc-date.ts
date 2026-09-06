/**
 * UTC calendar-day helpers for analytics filtering (TASK-P14-006 §6.4).
 *
 * The dashboard sends date-only `YYYY-MM-DD` strings that the backend interprets
 * as inclusive UTC boundaries. These helpers format in UTC and never shift a
 * boundary through the browser timezone. Pure functions taking an explicit
 * `now` default so specs can freeze time deterministically.
 */

export interface UtcDateRange {
  from: string;
  to: string;
}

/** Default analytics window length in UTC days, including today. */
export const DEFAULT_ANALYTICS_WINDOW_DAYS = 30;

/** Format a Date as a UTC `YYYY-MM-DD` string (no timezone shift). */
export function toUtcDateString(date: Date): string {
  const year = date.getUTCFullYear();
  const month = String(date.getUTCMonth() + 1).padStart(2, '0');
  const day = String(date.getUTCDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

/** Subtract whole UTC calendar days from a `YYYY-MM-DD` string. */
export function subtractDaysIso(isoDate: string, days: number): string {
  const [year, month, day] = isoDate.split('-').map(Number);
  const utc = Date.UTC(year, month - 1, day) - days * 86_400_000;
  return toUtcDateString(new Date(utc));
}

/** Default 30-day UTC window ending today: `[today - 29d, today]`. */
export function defaultAnalyticsDateRange(now: Date = new Date()): UtcDateRange {
  const to = toUtcDateString(now);
  return { from: subtractDaysIso(to, DEFAULT_ANALYTICS_WINDOW_DAYS - 1), to };
}

/**
 * Obvious client-side range check only (format + ordering). The backend remains
 * authoritative for span limits and returns actionable errors.
 */
export function isValidIsoDateRange(from: string, to: string): boolean {
  const pattern = /^\d{4}-\d{2}-\d{2}$/;
  return pattern.test(from) && pattern.test(to) && from <= to;
}
