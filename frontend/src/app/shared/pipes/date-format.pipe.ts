import { Pipe, PipeTransform } from '@angular/core';

export type SeatFlowDateFormat = 'full' | 'short' | 'time';

@Pipe({
  name: 'sfDate',
  standalone: true,
})
export class DateFormatPipe implements PipeTransform {
  transform(
    value: string | number | Date | null | undefined,
    format: SeatFlowDateFormat = 'full',
  ): string {
    if (value === null || value === undefined || value === '') {
      return '—';
    }

    const date = typeof value === 'number' || typeof value === 'string' ? new Date(value) : value;
    if (Number.isNaN(date.getTime())) {
      return '—';
    }

    if (format === 'time') {
      return date.toLocaleTimeString('en-US', {
        hour: '2-digit',
        minute: '2-digit',
      });
    }

    if (format === 'short') {
      return date.toLocaleDateString('en-US', {
        month: 'short',
        day: 'numeric',
        year: 'numeric',
      });
    }

    const datePart = date.toLocaleDateString('en-US', {
      weekday: 'short',
      month: 'short',
      day: 'numeric',
      year: 'numeric',
    });
    const timePart = date.toLocaleTimeString('en-US', {
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    });

    return `${datePart} • ${timePart}`;
  }
}
