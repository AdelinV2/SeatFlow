import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { TactileButtonComponent } from '../../../../shared/components/tactile-button/tactile-button.component';

@Component({
  selector: 'app-hold-expired-dialog',
  standalone: true,
  imports: [MatDialogModule, TactileButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './hold-expired-dialog.component.html',
  styleUrl: './hold-expired-dialog.component.scss',
})
export class HoldExpiredDialogComponent {
  private readonly dialogRef = inject(MatDialogRef<HoldExpiredDialogComponent>);

  returnToEvent(): void {
    this.dialogRef.close('return-to-event');
  }
}
