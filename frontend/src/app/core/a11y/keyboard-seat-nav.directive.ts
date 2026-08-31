import { Directive, input, output } from '@angular/core';

@Directive({
  selector: '[appKeyboardSeatNav]',
  standalone: true,
  host: {
    '(keydown)': 'handleKeyDown($event)',
  },
})
export class KeyboardSeatNavDirective {
  readonly currentRow = input<number>(0);
  readonly currentCol = input<number>(0);
  readonly maxRows = input<number>(1);
  readonly maxCols = input<number>(1);

  readonly navigate = output<{ row: number; col: number }>();
  readonly activate = output<void>();

  handleKeyDown(event: KeyboardEvent): void {
    let nextRow = this.currentRow();
    let nextCol = this.currentCol();
    let handled = false;

    switch (event.key) {
      case 'ArrowUp':
        nextRow = Math.max(0, nextRow - 1);
        handled = true;
        break;
      case 'ArrowDown':
        nextRow = Math.min(this.maxRows() - 1, nextRow + 1);
        handled = true;
        break;
      case 'ArrowLeft':
        nextCol = Math.max(0, nextCol - 1);
        handled = true;
        break;
      case 'ArrowRight':
        nextCol = Math.min(this.maxCols() - 1, nextCol + 1);
        handled = true;
        break;
      case 'Enter':
      case ' ':
        event.preventDefault();
        this.activate.emit();
        return;
    }

    if (handled) {
      event.preventDefault();
      this.navigate.emit({ row: nextRow, col: nextCol });
    }
  }
}
