import {
  defaultAnalyticsDateRange,
  isValidIsoDateRange,
  subtractDaysIso,
  toUtcDateString,
} from './utc-date';

describe('utc-date pure utilities', () => {
  it('formats in UTC regardless of the browser timezone offset', () => {
    // 2026-09-06T01:30:00+05:00 is still 2026-09-05 in UTC.
    expect(toUtcDateString(new Date('2026-09-06T01:30:00+05:00'))).toBe('2026-09-05');
    expect(toUtcDateString(new Date('2026-09-06T12:00:00Z'))).toBe('2026-09-06');
  });

  it('computes the default 30-day window ending today', () => {
    expect(defaultAnalyticsDateRange(new Date('2026-09-06T12:00:00Z'))).toEqual({
      from: '2026-08-08',
      to: '2026-09-06',
    });
  });

  it('subtracts UTC calendar days across month boundaries', () => {
    expect(subtractDaysIso('2026-09-06', 29)).toBe('2026-08-08');
    expect(subtractDaysIso('2026-03-01', 1)).toBe('2026-02-28');
  });

  it('validates obvious range mistakes client-side', () => {
    expect(isValidIsoDateRange('2026-08-08', '2026-09-06')).toBeTrue();
    expect(isValidIsoDateRange('2026-09-06', '2026-09-06')).toBeTrue();
    expect(isValidIsoDateRange('2026-09-06', '2026-08-08')).toBeFalse();
    expect(isValidIsoDateRange('08/08/2026', '2026-09-06')).toBeFalse();
    expect(isValidIsoDateRange('', '2026-09-06')).toBeFalse();
  });
});
