import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { FormBuilder, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatSnackBar } from '@angular/material/snack-bar';
import { AdminVenueApiService } from '../../../../services/admin-venue-api.service';
import {
  VenueLayout,
  VenueLayoutElement,
  VenueSectionLayout,
  VenueSectionSeat,
} from '../../../../models/venue.model';
import { SkeletonLoaderComponent } from '../../../../shared/components/skeleton-loader/skeleton-loader.component';
import {
  VenueLayoutEditorStateService,
  VenueLayoutSnapshot,
} from '../../../../services/venue-layout-editor-state.service';
import {
  GenerateSeatsOptions,
  getRowLabel,
  SeatLayoutGeneratorService,
} from '../../../../services/seat-layout-generator.service';
import {
  LayoutCanvasComponent,
  SectionTransformChangeEvent,
} from '../../../../shared/components/seat-layout/layout-canvas/layout-canvas.component';
import { SectionPropertiesPanelComponent } from '../section-properties-panel/section-properties-panel.component';

export { getRowLabel };

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
    LayoutCanvasComponent,
    SectionPropertiesPanelComponent,
  ],
  templateUrl: './venue-grid-designer.component.html',
  styleUrl: './venue-grid-designer.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VenueGridDesignerComponent implements OnInit {
  readonly getRowLabel = getRowLabel;

  private readonly route = inject(ActivatedRoute);
  private readonly venueApi = inject(AdminVenueApiService);
  private readonly editorState = inject(VenueLayoutEditorStateService);
  private readonly generator = inject(SeatLayoutGeneratorService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly fb = inject(FormBuilder);

  readonly canvasRef = viewChild<LayoutCanvasComponent>('canvasRef');

  readonly venueId = signal<string>('');
  readonly isLoading = signal<boolean>(true);
  readonly isCreatingSection = signal<boolean>(false);
  readonly showAddSectionModal = signal<boolean>(false);
  readonly selectedSectionKey = signal<string | null>(null);
  readonly selectedSeatKeys = signal<Set<string>>(new Set());
  readonly validationError = signal<string | null>(null);

  // Expose editor state signals
  readonly isSaving = this.editorState.isSaving;
  readonly isDirty = this.editorState.isDirty;
  readonly loadError = this.editorState.loadError;

  readonly venue = computed<VenueLayoutSnapshot | null>(() => {
    return this.editorState.layout();
  });

  readonly sections = computed<VenueSectionLayout[]>(() => {
    return (this.venue()?.sections as VenueSectionLayout[]) || [];
  });

  readonly elements = computed<VenueLayoutElement[]>(() => {
    return (this.venue()?.elements as VenueLayoutElement[]) || [];
  });

  readonly currentSection = computed<VenueSectionLayout | null>(() => {
    const all = this.sections();
    if (!all.length) return null;
    const key = this.selectedSectionKey();
    if (key === null || key === undefined) {
      return all[0];
    }
    const match = all.find((s) => s.sectionId === key || s.name === key);
    if (match) {
      return match;
    }
    const nullSec = all.find((s) => s.sectionId === null);
    if (nullSec) {
      return nullSec;
    }
    return all[0];
  });

  readonly selectedSectionId = computed<string | null>(() => {
    return this.currentSection()?.sectionId ?? null;
  });

  readonly selectedSectionIdSet = computed<Set<string>>(() => {
    const sec = this.currentSection();
    if (!sec) return new Set<string>();
    const id = sec.sectionId ?? '';
    return new Set<string>([id]);
  });

  readonly totalConfiguredActiveSeats = computed<number>(() => {
    return this.sections().reduce((sum, sec) => {
      if (!sec.isActive) return sum;
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

  readonly sectionForm = this.fb.group({
    name: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(50)]],
    rowCount: [10, [Validators.required, Validators.min(1), Validators.max(50)]],
    colCount: [15, [Validators.required, Validators.min(1), Validators.max(50)]],
    generateSeats: [true],
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
    this.validationError.set(null);
    this.editorState.load(venueId).subscribe({
      next: (layout) => {
        if (layout.sections && layout.sections.length > 0) {
          if (!this.selectedSectionKey()) {
            this.selectedSectionKey.set(layout.sections[0].sectionId || layout.sections[0].name);
          }
        }
        this.isLoading.set(false);
      },
      error: (err) => {
        this.snackBar.open(
          err?.error?.message || err?.message || 'Failed to load venue layout.',
          'Close',
          { duration: 4000, panelClass: 'bg-rose-600' },
        );
        this.isLoading.set(false);
      },
    });
  }

  selectSection(sectionOrIdOrName: VenueSectionLayout | string | null): void {
    if (!sectionOrIdOrName) {
      this.selectedSectionKey.set(null);
    } else if (typeof sectionOrIdOrName === 'string') {
      this.selectedSectionKey.set(sectionOrIdOrName);
    } else {
      this.selectedSectionKey.set(sectionOrIdOrName.sectionId || sectionOrIdOrName.name);
    }
    this.selectedSeatKeys.set(new Set());
    this.validationError.set(null);
  }

  // --- Canvas Integration ---

  onSectionTransformChanged(event: SectionTransformChangeEvent): void {
    this.validationError.set(null);
    try {
      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((sec) => {
          const matches =
            sec.sectionId === event.sectionId ||
            (sec.sectionId === null && event.sectionId === null && sec === this.currentSection());
          if (matches) {
            return {
              ...sec,
              positionX: event.positionX,
              positionY: event.positionY,
              width: event.width,
              height: event.height,
              rotationDeg: event.rotationDeg,
              ...(event.zIndex !== undefined ? { zIndex: event.zIndex } : {}),
            };
          }
          return sec;
        });
        return draft;
      });
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Invalid transform change';
      this.validationError.set(msg);
    }
  }

  onCanvasSelectionChanged(selectedIds: Set<string>): void {
    if (selectedIds.size > 0) {
      const firstId = [...selectedIds][0];
      this.selectSection(firstId);
    }
  }

  onCanvasSeatSelected(event: { seat: VenueSectionSeat; section: VenueSectionLayout }): void {
    this.selectSection(event.section);

    const currentKeys = new Set(this.selectedSeatKeys());
    const seatKey = event.seat.seatId || `${event.seat.gridY}_${event.seat.gridX}`;

    if (currentKeys.has(seatKey)) {
      currentKeys.delete(seatKey);
    } else {
      currentKeys.add(seatKey);
    }
    this.selectedSeatKeys.set(currentKeys);
    this.validationError.set(null);
  }

  // --- Save / Discard Layout ---

  saveLayout(): void {
    this.validationError.set(null);
    this.editorState.save().subscribe({
      next: () => {
        this.snackBar.open('Venue layout saved successfully!', 'Close', {
          duration: 4000,
          panelClass: 'snack-success',
        });
      },
      error: (err) => {
        const msg = err?.error?.message || err?.message || 'Failed to save venue layout.';
        this.validationError.set(msg);
        this.snackBar.open(msg, 'Close', {
          duration: 4000,
          panelClass: 'snack-error',
        });
      },
    });
  }

  discardChanges(): void {
    this.validationError.set(null);
    this.editorState.resetToBaseline();
    this.selectedSeatKeys.set(new Set());
    this.snackBar.open('All draft changes discarded. Reset to baseline.', 'Close', {
      duration: 3000,
    });
  }

  // --- Section Operations (Local Draft) ---

  openAddSectionModal(): void {
    this.sectionForm.reset({
      name: '',
      rowCount: 10,
      colCount: 15,
      generateSeats: true,
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
    const name = formVal.name!.trim();
    const rowCount = Number(formVal.rowCount);
    const colCount = Number(formVal.colCount);
    const shouldGenSeats = formVal.generateSeats !== false;

    this.validationError.set(null);
    try {
      const newSection = this.generator.createSection(name, this.sections(), {
        rowCount,
        colCount,
        generateSeats: shouldGenSeats,
        venueCapacity: this.venue()?.capacity,
        totalActiveSeats: this.totalConfiguredActiveSeats(),
      });

      this.editorState.replaceDraft((draft) => {
        draft.sections = [...draft.sections, newSection];
        return draft;
      });

      this.closeAddSectionModal();
      this.selectSection(newSection);
      this.snackBar.open(`Section "${newSection.name}" created in draft!`, 'Close', {
        duration: 3000,
        panelClass: 'snack-success',
      });
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to create section';
      this.validationError.set(msg);
      this.snackBar.open(msg, 'Close', { duration: 4000, panelClass: 'snack-error' });
    }
  }

  duplicateSection(): void {
    const sec = this.currentSection();
    if (!sec) return;

    this.validationError.set(null);
    try {
      const duplicated = this.generator.duplicateSection(
        sec,
        this.sections(),
        this.venue()?.capacity,
        this.totalConfiguredActiveSeats(),
      );

      this.editorState.replaceDraft((draft) => {
        draft.sections = [...draft.sections, duplicated];
        return draft;
      });

      this.selectSection(duplicated);
      this.snackBar.open(`Section duplicated as "${duplicated.name}"!`, 'Close', {
        duration: 3000,
        panelClass: 'snack-success',
      });
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to duplicate section';
      this.validationError.set(msg);
      this.snackBar.open(msg, 'Close', { duration: 4000, panelClass: 'snack-error' });
    }
  }

  deactivateSection(): void {
    const sec = this.currentSection();
    if (!sec) return;

    this.validationError.set(null);
    try {
      const deactivated = this.generator.deactivateSection(sec);
      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) =>
          s === sec || s.sectionId === sec.sectionId ? deactivated : s,
        );
        return draft;
      });
      this.snackBar.open(`Section "${sec.name}" and its seats deactivated!`, 'Close', {
        duration: 3000,
        panelClass: 'snack-success',
      });
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to deactivate section';
      this.validationError.set(msg);
    }
  }

  reactivateSection(): void {
    const sec = this.currentSection();
    if (!sec) return;

    this.validationError.set(null);
    try {
      const reactivated = this.generator.reactivateSection(
        sec,
        this.sections(),
        this.venue()?.capacity,
        this.totalConfiguredActiveSeats(),
      );
      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) =>
          s === sec || s.sectionId === sec.sectionId ? reactivated : s,
        );
        return draft;
      });
      this.snackBar.open(`Section "${sec.name}" reactivated!`, 'Close', {
        duration: 3000,
        panelClass: 'snack-success',
      });
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to reactivate section';
      this.validationError.set(msg);
      this.snackBar.open(msg, 'Close', { duration: 4000, panelClass: 'snack-error' });
    }
  }

  removeSection(): void {
    const sec = this.currentSection();
    if (!sec) return;

    this.validationError.set(null);
    try {
      const remaining = this.generator.removeSection(sec, this.sections());
      this.editorState.replaceDraft((draft) => {
        draft.sections = remaining;
        return draft;
      });

      if (remaining.length > 0) {
        this.selectSection(remaining[0]);
      } else {
        this.selectSection(null);
      }
      this.snackBar.open(`Draft section "${sec.name}" removed!`, 'Close', {
        duration: 3000,
        panelClass: 'snack-success',
      });
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to remove section';
      this.validationError.set(msg);
      this.snackBar.open(msg, 'Close', { duration: 4000, panelClass: 'snack-error' });
    }
  }

  updateSectionProperties(partial: Partial<VenueSectionLayout>): void {
    const sec = this.currentSection();
    if (!sec) return;

    this.validationError.set(null);
    try {
      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) => {
          if (s === sec || (sec.sectionId && s.sectionId === sec.sectionId)) {
            return { ...s, ...partial };
          }
          return s;
        });
        return draft;
      });
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Invalid section update';
      this.validationError.set(msg);
    }
  }

  // --- Seat Generation & Bulk Operations (Local Draft) ---

  onGenerateSeats(options: GenerateSeatsOptions): void {
    const sec = this.currentSection();
    if (!sec) return;

    this.validationError.set(null);
    try {
      const seats = this.generator.generateSeats(options);
      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) => {
          if (s === sec || (sec.sectionId && s.sectionId === sec.sectionId)) {
            return {
              ...s,
              rowCount: options.rowCount,
              colCount: options.colCount,
              seats,
            };
          }
          return s;
        });
        return draft;
      });

      this.selectedSeatKeys.set(new Set());
      this.snackBar.open(`Generated ${seats.length} seats for section "${sec.name}"!`, 'Close', {
        duration: 3000,
        panelClass: 'snack-success',
      });
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to generate seats';
      this.validationError.set(msg);
      this.snackBar.open(msg, 'Close', { duration: 4000, panelClass: 'snack-error' });
    }
  }

  onBulkActivate(active: boolean): void {
    const sec = this.currentSection();
    if (!sec) return;

    this.validationError.set(null);
    try {
      const updated = this.generator.bulkSetActive(
        sec,
        this.selectedSeatKeys(),
        active,
        this.venue()?.capacity,
        this.totalConfiguredActiveSeats(),
      );

      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) => (s.sectionId === sec.sectionId ? updated : s));
        return draft;
      });

      this.snackBar.open(`Updated active status for selected seats!`, 'Close', {
        duration: 2500,
      });
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to bulk update seat activity';
      this.validationError.set(msg);
      this.snackBar.open(msg, 'Close', { duration: 4000, panelClass: 'snack-error' });
    }
  }

  onBulkTranslate(delta: { deltaX: number; deltaY: number }): void {
    const sec = this.currentSection();
    if (!sec) return;

    this.validationError.set(null);
    try {
      const updated = this.generator.bulkTranslate(sec, this.selectedSeatKeys(), {
        deltaX: delta.deltaX,
        deltaY: delta.deltaY,
        sectionWidth: sec.width,
        sectionHeight: sec.height,
      });

      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) => (s.sectionId === sec.sectionId ? updated : s));
        return draft;
      });

      this.snackBar.open('Moved selected seats successfully!', 'Close', { duration: 2500 });
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to translate seats';
      this.validationError.set(msg);
      this.snackBar.open(msg, 'Close', { duration: 4000, panelClass: 'snack-error' });
    }
  }

  onBulkSetRowLabel(label: string): void {
    const sec = this.currentSection();
    if (!sec) return;

    this.validationError.set(null);
    try {
      const updated = this.generator.bulkSetRowLabel(sec, this.selectedSeatKeys(), label);

      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) => (s.sectionId === sec.sectionId ? updated : s));
        return draft;
      });

      this.snackBar.open(`Row label set to "${label}"!`, 'Close', { duration: 2500 });
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to set row label';
      this.validationError.set(msg);
      this.snackBar.open(msg, 'Close', { duration: 4000, panelClass: 'snack-error' });
    }
  }

  onBulkRenumber(startNumber: number): void {
    const sec = this.currentSection();
    if (!sec) return;

    this.validationError.set(null);
    try {
      const updated = this.generator.bulkRenumber(sec, this.selectedSeatKeys(), startNumber);

      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) => (s.sectionId === sec.sectionId ? updated : s));
        return draft;
      });

      this.snackBar.open(`Seats renumbered from #${startNumber}!`, 'Close', { duration: 2500 });
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to renumber seats';
      this.validationError.set(msg);
      this.snackBar.open(msg, 'Close', { duration: 4000, panelClass: 'snack-error' });
    }
  }

  onSeatSelectionChanged(keys: Set<string>): void {
    this.selectedSeatKeys.set(keys);
  }

  // --- Canvas Navigation Controls ---

  zoomIn(): void {
    this.canvasRef()?.zoomIn();
  }

  zoomOut(): void {
    this.canvasRef()?.zoomOut();
  }

  resetView(): void {
    this.canvasRef()?.resetView();
  }

  fitToLayout(): void {
    this.canvasRef()?.fitToLayout();
  }

  // --- Legacy Compatibility Method ---

  toggleSeat(seat: VenueSectionSeat): void {
    const sec = this.currentSection();
    if (!sec || !sec.sectionId || !seat.seatId) return;
    const sectionId = sec.sectionId;
    const seatId = seat.seatId;

    const previousState = seat.isActive;
    const newState = !previousState;

    this.venueApi.toggleSeat(this.venueId(), sectionId, seatId, newState).subscribe({
      next: () => {
        this.editorState.replaceDraft((draft) => {
          draft.sections = draft.sections.map((s) => {
            if (s.sectionId === sectionId) {
              return {
                ...s,
                seats: s.seats.map((st) =>
                  st.seatId === seatId ? { ...st, isActive: newState } : st,
                ),
              };
            }
            return s;
          });
          return draft;
        });
      },
      error: (err) => {
        this.snackBar.open(
          err?.error?.message || 'Failed to update seat status. Reverted.',
          'Close',
          { duration: 4000, panelClass: 'snack-error' },
        );
      },
    });
  }
}
