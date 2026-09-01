import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { ValidationResultResponse } from '../../../models/scanner.model';
import { DateFormatPipe } from '../../../shared/pipes/date-format.pipe';

@Component({
  selector: 'app-validation-result-card',
  standalone: true,
  imports: [CommonModule, DateFormatPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './validation-result-card.component.html',
  styleUrl: './validation-result-card.component.scss',
})
export class ValidationResultCardComponent {
  readonly result = input<ValidationResultResponse | null>(null);
  readonly dismissed = output<void>();

  readonly isSuccess = computed(() => this.result()?.result === 'SUCCESS');
  readonly isAlreadyUsed = computed(() => this.result()?.result === 'ALREADY_USED');
  readonly isInvalid = computed(
    () => this.result()?.result === 'INVALID' || this.result()?.result === 'CANCELLED',
  );

  readonly ticketType = computed(() => {
    const r = this.result();
    return r?.ticketType || r?.tierName || 'Standard';
  });

  readonly formattedSeat = computed(() => {
    const r = this.result();
    if (!r) return null;
    return {
      section: r.section || 'General',
      row: r.rowNumber ? `Row ${r.rowNumber}` : '—',
      seat: r.seatNumber != null ? `Seat ${r.seatNumber}` : 'GA',
    };
  });

  dismiss(): void {
    this.dismissed.emit();
  }
}
