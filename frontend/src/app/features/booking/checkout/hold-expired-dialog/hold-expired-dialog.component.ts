import { ChangeDetectionStrategy, Component } from '@angular/core';
import {
  MatDialogActions,
  MatDialogClose,
  MatDialogContent,
  MatDialogTitle,
} from '@angular/material/dialog';
import { TactileButtonComponent } from '../../../../shared/components/tactile-button/tactile-button.component';

@Component({
  selector: 'app-hold-expired-dialog',
  standalone: true,
  imports: [MatDialogTitle, MatDialogContent, MatDialogActions, MatDialogClose, TactileButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './hold-expired-dialog.component.html',
  styleUrl: './hold-expired-dialog.component.scss',
})
export class HoldExpiredDialogComponent {}
