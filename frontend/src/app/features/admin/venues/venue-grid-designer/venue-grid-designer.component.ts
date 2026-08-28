import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { FormBuilder, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatSnackBar } from '@angular/material/snack-bar';
import { AdminVenueApiService } from '../../../../services/admin-venue-api.service';
import {
  CreateSectionRequest,
  VenueLayout,
  VenueSectionLayout,
  VenueSectionSeat,
} from '../../../../models/venue.model';
import { SkeletonLoaderComponent } from '../../../../shared/components/skeleton-loader/skeleton-loader.component';

export function getRowLabel(rowIndex: number): string {
  let label = '';
  let num = rowIndex;
  while (num >= 0) {
    label = String.fromCharCode((num % 26) + 65) + label;
    num = Math.floor(num / 26) - 1;
  }
  return label;
}

export interface GridMatrixRow {
  rowIndex: number;
  rowLabel: string;
  seats: (VenueSectionSeat | null)[];
}

@Component({
  selector: 'app-venue-grid-designer',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    FormsModule,
    ReactiveFormsModule,
    SkeletonLoaderComponent,
  ],
  templateUrl: './venue-grid-designer.component.html',
  styleUrl: './venue-grid-designer.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VenueGridDesignerComponent implements OnInit {
  readonly getRowLabel = getRowLabel;

  private readonly route = inject(ActivatedRoute);
  private readonly venueApi = inject(AdminVenueApiService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly fb = inject(FormBuilder);

  readonly venueId = signal<string>('');
  readonly venue = signal<VenueLayout | null>(null);
  readonly isLoading = signal<boolean>(true);
  readonly isCreatingSection = signal<boolean>(false);
  readonly isDeletingSection = signal<boolean>(false);
  readonly showAddSectionModal = signal<boolean>(false);
  readonly showDeleteSectionConfirm = signal<boolean>(false);
  readonly selectedSectionId = signal<string | null>(null);
  readonly zoomLevel = signal<number>(100);

  // Bulk action pickers
  readonly selectedBulkRow = signal<number>(0);
  readonly selectedBulkCol = signal<number>(0);

  readonly sectionForm = this.fb.group({
    name: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(50)]],
    rowCount: [10, [Validators.required, Validators.min(1), Validators.max(50)]],
    colCount: [15, [Validators.required, Validators.min(1), Validators.max(50)]],
  });

  readonly sections = computed<VenueSectionLayout[]>(() => {
    return this.venue()?.sections || [];
  });

  readonly currentSection = computed<VenueSectionLayout | null>(() => {
    const all = this.sections();
    const selectedId = this.selectedSectionId();
    if (!all.length) return null;
    if (!selectedId) return all[0];
    return all.find((s) => s.sectionId === selectedId) || all[0];
  });

  readonly totalConfiguredActiveSeats = computed<number>(() => {
    return this.sections().reduce((sum, sec) => {
      const activeInSec = (sec.seats || []).filter((s) => s.isActive).length;
      return sum + activeInSec;
    }, 0);
  });

  readonly currentSectionActiveCount = computed<number>(() => {
    const sec = this.currentSection();
    if (!sec || !sec.seats) return 0;
    return sec.seats.filter((s) => s.isActive).length;
  });

  readonly currentSectionTotalCount = computed<number>(() => {
    const sec = this.currentSection();
    if (!sec) return 0;
    return sec.rowCount * sec.colCount;
  });

  readonly currentSectionInactiveCount = computed<number>(() => {
    return this.currentSectionTotalCount() - this.currentSectionActiveCount();
  });

  readonly gridMatrix = computed<GridMatrixRow[]>(() => {
    const sec = this.currentSection();
    if (!sec) return [];

    const rowCount = sec.rowCount;
    const colCount = sec.colCount;
    const seats = sec.seats || [];

    // Map seats by gridY_gridX
    const seatMap = new Map<string, VenueSectionSeat>();
    for (const s of seats) {
      seatMap.set(`${s.gridY}_${s.gridX}`, s);
    }

    const rows: GridMatrixRow[] = [];
    for (let r = 0; r < rowCount; r++) {
      const rowSeats: (VenueSectionSeat | null)[] = [];
      const rowLabel = getRowLabel(r);

      for (let c = 0; c < colCount; c++) {
        const seat = seatMap.get(`${r}_${c}`) || null;
        rowSeats.push(seat);
      }

      rows.push({
        rowIndex: r,
        rowLabel,
        seats: rowSeats,
      });
    }

    return rows;
  });

  readonly columnHeaders = computed<number[]>(() => {
    const sec = this.currentSection();
    if (!sec) return [];
    return Array.from({ length: sec.colCount }, (_, i) => i + 1);
  });

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.venueId.set(id);
      this.loadVenueLayout(id);
    }
  }

  loadVenueLayout(venueId: string): void {
    this.isLoading.set(true);
    this.venueApi.getVenueLayout(venueId).subscribe({
      next: (layout) => {
        this.venue.set(layout);
        if (layout.sections && layout.sections.length > 0) {
          if (!this.selectedSectionId()) {
            this.selectedSectionId.set(layout.sections[0].sectionId);
          }
        }
        this.isLoading.set(false);
      },
      error: (err) => {
        this.snackBar.open(
          err?.error?.message || 'Failed to load venue layout.',
          'Close',
          { duration: 4000, panelClass: 'bg-rose-600' }
        );
        this.isLoading.set(false);
      },
    });
  }

  selectSection(sectionId: string): void {
    this.selectedSectionId.set(sectionId);
    this.selectedBulkRow.set(0);
    this.selectedBulkCol.set(0);
  }

  openAddSectionModal(): void {
    this.sectionForm.reset({
      name: '',
      rowCount: 10,
      colCount: 15,
    });
    this.showAddSectionModal.set(true);
  }

  closeAddSectionModal(): void {
    this.showAddSectionModal.set(false);
  }

  createSection(): void {
    if (this.sectionForm.invalid) {
      this.sectionForm.markAllAsTouched();
      return;
    }

    const formVal = this.sectionForm.getRawValue();
    const req: CreateSectionRequest = {
      name: formVal.name!,
      rowCount: Number(formVal.rowCount),
      colCount: Number(formVal.colCount),
    };

    this.isCreatingSection.set(true);
    this.venueApi.createSection(this.venueId(), req).subscribe({
      next: (newSection) => {
        this.isCreatingSection.set(false);
        this.closeAddSectionModal();
        this.snackBar.open(
          `Section "${newSection.name}" created with ${newSection.rowCount * newSection.colCount} seats!`,
          'Close',
          { duration: 4000, panelClass: 'snack-success' }
        );
        // Reload full venue layout and select the new section
        this.loadVenueLayout(this.venueId());
        this.selectedSectionId.set(newSection.sectionId);
      },
      error: (err) => {
        this.isCreatingSection.set(false);
        this.snackBar.open(
          err?.error?.message || 'Failed to create section.',
          'Close',
          { duration: 4000, panelClass: 'snack-error' }
        );
      },
    });
  }

  openDeleteSectionConfirm(): void {
    this.showDeleteSectionConfirm.set(true);
  }

  closeDeleteSectionConfirm(): void {
    this.showDeleteSectionConfirm.set(false);
  }

  confirmDeleteSection(): void {
    const sec = this.currentSection();
    if (!sec || !this.venueId()) return;

    this.isDeletingSection.set(true);
    this.venueApi.deleteSection(this.venueId(), sec.sectionId).subscribe({
      next: () => {
        this.isDeletingSection.set(false);
        this.showDeleteSectionConfirm.set(false);
        this.snackBar.open(
          `Section "${sec.name}" deleted successfully!`,
          'Close',
          { duration: 4000, panelClass: 'snack-success' }
        );
        this.selectedSectionId.set(null);
        this.loadVenueLayout(this.venueId());
      },
      error: (err) => {
        this.isDeletingSection.set(false);
        this.snackBar.open(
          err?.error?.message || 'Failed to delete section.',
          'Close',
          { duration: 4000, panelClass: 'snack-error' }
        );
      },
    });
  }

  toggleSeat(seat: VenueSectionSeat): void {
    const sec = this.currentSection();
    if (!sec) return;

    const previousState = seat.isActive;
    const newState = !previousState;

    // Optimistic local update
    this.updateSeatStateLocally(sec.sectionId, seat.seatId, newState);

    this.venueApi
      .toggleSeat(this.venueId(), sec.sectionId, seat.seatId, newState)
      .subscribe({
        next: () => {
          // Success
        },
        error: (err) => {
          // Rollback
          this.updateSeatStateLocally(sec.sectionId, seat.seatId, previousState);
          this.snackBar.open(
            err?.error?.message || 'Failed to update seat status. Reverted.',
            'Close',
            { duration: 4000, panelClass: 'snack-error' }
          );
        },
      });
  }

  bulkToggleRow(rowIndex: number, targetState: boolean): void {
    const sec = this.currentSection();
    if (!sec) return;

    const rowSeats = (sec.seats || []).filter((s) => s.gridY === rowIndex);
    for (const seat of rowSeats) {
      if (seat.isActive !== targetState) {
        this.toggleSeat(seat);
      }
    }
  }

  bulkToggleColumn(colIndex: number, targetState: boolean): void {
    const sec = this.currentSection();
    if (!sec) return;

    const colSeats = (sec.seats || []).filter((s) => s.gridX === colIndex);
    for (const seat of colSeats) {
      if (seat.isActive !== targetState) {
        this.toggleSeat(seat);
      }
    }
  }

  bulkSetAllSeats(targetState: boolean): void {
    const sec = this.currentSection();
    if (!sec) return;

    for (const seat of sec.seats || []) {
      if (seat.isActive !== targetState) {
        this.toggleSeat(seat);
      }
    }
  }

  private updateSeatStateLocally(
    sectionId: string,
    seatId: string,
    isActive: boolean
  ): void {
    const currentVenue = this.venue();
    if (!currentVenue) return;

    const updatedSections = currentVenue.sections.map((sec) => {
      if (sec.sectionId !== sectionId) return sec;
      const updatedSeats = (sec.seats || []).map((s) =>
        s.seatId === seatId ? { ...s, isActive } : s
      );
      return { ...sec, seats: updatedSeats };
    });

    this.venue.set({ ...currentVenue, sections: updatedSections });
  }

  setZoom(level: number): void {
    this.zoomLevel.set(Math.min(175, Math.max(50, level)));
  }

  resetZoom(): void {
    this.zoomLevel.set(100);
  }
}
