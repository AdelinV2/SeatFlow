import { CurrencyFormatPipe } from './currency-format.pipe';
import { DateFormatPipe } from './date-format.pipe';

describe('Shared formatting pipes', () => {
  describe('CurrencyFormatPipe', () => {
    const pipe = new CurrencyFormatPipe();

    it('formats currency with consistent two-decimal precision', () => {
      expect(pipe.transform(1234.5)).toBe('$1,234.50');
      expect(pipe.transform('1234.5')).toBe('$1,234.50');
      expect(pipe.transform(99, 'EUR')).toContain('99.00');
      expect(pipe.transform('99.95', 'EUR')).toContain('99.95');
    });

    it('returns an em dash for missing, non-finite, or invalid currency values', () => {
      expect(pipe.transform(null)).toBe('—');
      expect(pipe.transform(undefined)).toBe('—');
      expect(pipe.transform('')).toBe('—');
      expect(pipe.transform(Number.NaN)).toBe('—');
      expect(pipe.transform('not-a-number')).toBe('—');
      expect(pipe.transform(10, 'NOT_A_CURRENCY')).toBe('—');
    });
  });

  describe('DateFormatPipe', () => {
    const pipe = new DateFormatPipe();
    const date = new Date(2026, 8, 15, 19, 30);

    it('formats full, short, and time variants from Date and timestamp number', () => {
      expect(pipe.transform(date, 'full')).toContain('• 19:30');
      expect(pipe.transform(date, 'short')).toBe('Sep 15, 2026');
      expect(pipe.transform(date, 'time')).toBe('19:30');
      expect(pipe.transform(date.getTime(), 'short')).toBe('Sep 15, 2026');
    });

    it('formats 24-hour time consistently for both morning and evening without AM/PM', () => {
      const morningDate = new Date(2026, 8, 15, 9, 5);
      expect(pipe.transform(morningDate, 'time')).toBe('09:05');
      expect(pipe.transform(morningDate, 'full')).toContain('• 09:05');
      expect(pipe.transform(morningDate, 'time')).not.toMatch(/AM|PM/);

      const eveningDate = new Date(2026, 8, 15, 21, 45);
      expect(pipe.transform(eveningDate, 'time')).toBe('21:45');
      expect(pipe.transform(eveningDate, 'full')).toContain('• 21:45');
      expect(pipe.transform(eveningDate, 'time')).not.toMatch(/AM|PM/);

      // Default format should be 'full'
      expect(pipe.transform(eveningDate)).toBe(pipe.transform(eveningDate, 'full'));
    });

    it('returns an em dash for empty or invalid dates', () => {
      expect(pipe.transform(null)).toBe('—');
      expect(pipe.transform(undefined)).toBe('—');
      expect(pipe.transform('')).toBe('—');
      expect(pipe.transform('not-a-date')).toBe('—');
    });
  });
});
