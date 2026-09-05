import { Clipboard } from '@angular/cdk/clipboard';
import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import {
  MAT_DIALOG_DATA,
  MatDialogActions,
  MatDialogClose,
  MatDialogContent,
  MatDialogRef,
  MatDialogTitle,
} from '@angular/material/dialog';

export interface LayoutConflictDialogData {
  readonly localVersion: number;
  readonly correlationId?: string | null;
  readonly snapshotJson: string;
}

export type LayoutConflictDialogResult = 'keep-editing' | 'reload';

@Component({
  selector: 'app-layout-conflict-dialog',
  standalone: true,
  imports: [CommonModule, MatDialogTitle, MatDialogContent, MatDialogActions, MatDialogClose],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './layout-conflict-dialog.component.html',
  styleUrl: './layout-conflict-dialog.component.scss',
})
export class LayoutConflictDialogComponent {
  private readonly clipboard = inject(Clipboard);
  private readonly dialogRef = inject(
    MatDialogRef<LayoutConflictDialogComponent, LayoutConflictDialogResult>,
    { optional: true },
  );
  readonly data: LayoutConflictDialogData =
    inject<LayoutConflictDialogData>(MAT_DIALOG_DATA, { optional: true }) ??
    ({ localVersion: 0, snapshotJson: '{}' } as LayoutConflictDialogData);

  readonly localVersion = signal<number>(this.data.localVersion ?? 0);
  readonly correlationId = signal<string | null>(this.data.correlationId ?? null);
  readonly snapshotJson = signal<string>(this.data.snapshotJson ?? '{}');
  readonly copied = signal<boolean>(false);
  readonly showFallback = signal<boolean>(false);

  keepEditing(): void {
    this.dialogRef?.close('keep-editing');
  }

  reloadServer(): void {
    this.dialogRef?.close('reload');
  }

  copyLocalJson(): void {
    const payload = this.snapshotJson();
    let ok = false;
    try {
      ok = this.clipboard.copy(payload);
    } catch {
      ok = false;
    }
    if (ok) {
      this.copied.set(true);
      this.showFallback.set(false);
    } else {
      this.copied.set(false);
      this.showFallback.set(true);
    }
  }
}
