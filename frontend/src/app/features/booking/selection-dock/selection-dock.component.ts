import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { Seat } from '../../../models/seat.model';
import { TactileButtonComponent } from '../../../shared/components/tactile-button/tactile-button.component';
import { CurrencyFormatPipe } from '../../../shared/pipes/currency-format.pipe';

@Component({
  selector: 'app-selection-dock',
  standalone: true,
  imports: [CommonModule, TactileButtonComponent, CurrencyFormatPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './selection-dock.component.html',
  styleUrl: './selection-dock.component.scss',
})
export class SelectionDockComponent {
  readonly selectedSeats = input.required<Seat[]>();
  readonly maxSeats = input(10);
  readonly isCreatingHold = input(false);
  readonly seatRemoved = output<Seat>();
  readonly checkoutTriggered = output<void>();

  readonly count = computed(() => this.selectedSeats().length);
  readonly totalPrice = computed(() =>
    this.selectedSeats().reduce((sum, seat) => sum + seat.price, 0),
  );
  readonly currencyCode = computed(() => this.selectedSeats()[0]?.currency ?? 'USD');
}
