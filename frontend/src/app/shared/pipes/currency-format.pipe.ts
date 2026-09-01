import { Pipe, PipeTransform } from '@angular/core';

@Pipe({
  name: 'sfCurrency',
  standalone: true,
})
export class CurrencyFormatPipe implements PipeTransform {
  transform(value: number | string | null | undefined, currencyCode = 'USD'): string {
    if (value === null || value === undefined || value === '') {
      return '—';
    }

    const numericValue = typeof value === 'string' ? Number(value) : value;

    if (!Number.isFinite(numericValue)) {
      return '—';
    }

    try {
      return new Intl.NumberFormat('en-US', {
        style: 'currency',
        currency: currencyCode,
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
      }).format(numericValue);
    } catch {
      return '—';
    }
  }
}
