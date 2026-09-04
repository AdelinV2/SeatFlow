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
  getSeatColorKey,
  getSectionDraftKey,
  isSeatSelected,
  SeatLayoutGeneratorService,
} from '../../../../services/seat-layout-generator.service';
import {
  CanvasSeatSelectedEvent,
  getCanvasSectionKey,
  LayoutCanvasComponent,
  SectionTransformChangeEvent,
} from '../../../../shared/components/seat-layout/layout-canvas/layout-canvas.component';
import { CanvasToolMode } from '../../../../shared/components/seat-layout/section-node/section-node.component';
import {
  MAX_DIMENSION,
  MAX_POSITION,
  MAX_ROTATION,
  MAX_Z_INDEX,
  MIN_DIMENSION,
  MIN_POSITION,
  MIN_ROTATION,
  MIN_Z_INDEX,
} from '../../../../shared/utils/layout-geometry';
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
  readonly toolMode = signal<CanvasToolMode>('select');
  readonly activePaintColor = signal<string>('#6366f1');

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
    const match = all.find(
      (s) => getSectionDraftKey(s) === key || s.sectionId === key || s.name === key,
    );
    if (match) {
      return match;
    }
    return all[0];
  });

  readonly selectedSectionId = computed<string | null>(() => {
    return this.currentSection()?.sectionId ?? null;
  });

  readonly selectedSectionKeySet = computed<Set<string>>(() => {
    const sec = this.currentSection();
    if (!sec) return new Set<string>();
    return new Set<string>([getSectionDraftKey(sec)]);
  });

  /** Legacy alias kept for the section-tab template binding. */
  readonly selectedSectionIdSet = this.selectedSectionKeySet;

  readonly totalConfiguredActiveSeats = computed<number>(() => {
    return this.sections().reduce((sum, sec) => {
      if (!sec.isActive) return sum;
      const activeInSec = (sec.seats || []).filter((s) => s.isActive).length;
      return sum + activeInSec;
    }, 0);
  });

  readonly capacityPercentage = computed<number>(() => {
    const cap = this.venue()?.capacity || 0;
    if (cap <= 0) return 0;
    return Math.min(100, Math.round((this.totalConfiguredActiveSeats() / cap) * 100));
  });

  getSectionColor(sec: VenueSectionLayout): string {
    const meta = sec.shapeMetadata as Record<string, unknown> | null;
    if (meta && typeof meta['color'] === 'string' && meta['color']) {
      return meta['color'];
    }
    return '#6366f1';
  }

  readonly currentSectionActiveCount = computed<number>(() => {
    const sec = this.currentSection();
    if (!sec || !sec.seats) return 0;
    return sec.seats.filter((s) => s.isActive).length;
  });

  // REV-008: derive totals from actual seats so empty/custom sections do not
  // report misleading grid-capacity counts (rowCount*colCount).
  readonly currentSectionTotalCount = computed<number>(() => {
    const sec = this.currentSection();
    if (!sec) return 0;
    return (sec.seats || []).length;
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

      for (let c = 0; c < colCount; c++) {
        const seat = seatMap.get(`${r}_${c}`) || null;
        rowSeats.push(seat);
      }

      // REV-008: label grid rows with the actual seat rowLabel so the display
      // matches the selection model after bulk rename; fall back to the
      // synthetic label for empty grid rows.
      const rowLabel = rowSeats.find((s) => s !== null)?.rowLabel ?? getRowLabel(r);
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

  /** Resolves the stable draft key for a section (REV-002). */
  sectionKey(section: VenueSectionLayout): string {
    return getSectionDraftKey(section);
  }

  loadVenueLayout(venueId: string): void {
    this.isLoading.set(true);
    this.validationError.set(null);
    this.editorState.load(venueId).subscribe({
      next: (layout) => {
        if (layout.sections && layout.sections.length > 0) {
          if (!this.selectedSectionKey()) {
            this.selectedSectionKey.set(
              getSectionDraftKey(layout.sections[0] as VenueSectionLayout),
            );
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
      this.selectedSectionKey.set(getSectionDraftKey(sectionOrIdOrName));
    }
    this.selectedSeatKeys.set(new Set());
    this.validationError.set(null);
  }

  /**
   * Counts active seats in OTHER sections (excluding the target draft key).
   * Used for capacity preflight so reactivation/generation project correctly.
   */
  private countOtherActiveSeats(excludeKey: string): number {
    return this.sections().reduce((sum, sec) => {
      if (getSectionDraftKey(sec) === excludeKey) return sum;
      if (!sec.isActive) return sum;
      return sum + (sec.seats || []).filter((s) => s.isActive).length;
    }, 0);
  }

  /**
   * REV-001: removes legacy `RowLabel_Number` color keys for seats whose
   * identity changed, so repeated paint/rename cycles cannot bloat
   * `shapeMetadata.seatColors`. A stale key is deleted only when no seat in
   * the updated section still resolves to that label key.
   */
  private pruneStaleLabelColorKeys(
    updated: VenueSectionLayout,
    staleLabelKeys: Set<string>,
  ): VenueSectionLayout {
    if (staleLabelKeys.size === 0) return updated;
    const meta = (updated.shapeMetadata as Record<string, unknown>) || {};
    const seatColors = meta['seatColors'] as Record<string, string> | undefined;
    if (!seatColors) return updated;
    const liveLabelKeys = new Set(
      (updated.seats || []).map((s) => `${s.rowLabel}_${s.seatNumber}`),
    );
    const pruned: Record<string, string> = { ...seatColors };
    let mutated = false;
    for (const stale of staleLabelKeys) {
      if (!liveLabelKeys.has(stale) && stale in pruned) {
        delete pruned[stale];
        mutated = true;
      }
    }
    if (!mutated) return updated;
    return { ...updated, shapeMetadata: { ...meta, seatColors: pruned } };
  }

  // --- Canvas Integration ---

  /**
   * REV-008: mirrors the authoritative 0..100000 position/size contract
   * (TASK-P11-003 bounds) for immediate feedback, then delegates to
   * seat-in-bounds validation. Returns null when the projected geometry is valid.
   */
  private validateSectionGeometry(projected: VenueSectionLayout): string | null {
    const name = projected.name;
    if (
      !Number.isFinite(projected.positionX) ||
      projected.positionX < MIN_POSITION ||
      projected.positionX > MAX_POSITION
    ) {
      return `Section "${name}" positionX must be between 0 and 100000`;
    }
    if (
      !Number.isFinite(projected.positionY) ||
      projected.positionY < MIN_POSITION ||
      projected.positionY > MAX_POSITION
    ) {
      return `Section "${name}" positionY must be between 0 and 100000`;
    }
    if (
      !Number.isFinite(projected.width) ||
      projected.width < MIN_DIMENSION ||
      projected.width > MAX_DIMENSION
    ) {
      return `Section "${name}" width must be between 0 and 100000`;
    }
    if (
      !Number.isFinite(projected.height) ||
      projected.height < MIN_DIMENSION ||
      projected.height > MAX_DIMENSION
    ) {
      return `Section "${name}" height must be between 0 and 100000`;
    }
    if (
      !Number.isFinite(projected.rotationDeg) ||
      projected.rotationDeg < MIN_ROTATION ||
      projected.rotationDeg > MAX_ROTATION
    ) {
      return `Section "${name}" rotation must be between -180 and 180 degrees`;
    }
    if (
      !Number.isInteger(projected.zIndex) ||
      projected.zIndex < MIN_Z_INDEX ||
      projected.zIndex > MAX_Z_INDEX
    ) {
      return `Section "${name}" zIndex must be an integer between -1000 and 1000`;
    }
    return this.generator.validateSectionBounds(projected);
  }

  onSectionTransformChanged(event: SectionTransformChangeEvent): void {
    this.validationError.set(null);
    const fallbackKey = this.currentSection() ? getSectionDraftKey(this.currentSection()!) : null;
    const targetKey = event.draftKey ?? event.sectionId ?? fallbackKey;
    if (!targetKey) {
      this.validationError.set('Selected section is no longer present in the draft');
      return;
    }
    const current = this.sections().find((s) => getSectionDraftKey(s) === targetKey);
    if (!current) {
      this.validationError.set('Selected section is no longer present in the draft');
      return;
    }
    const projected: VenueSectionLayout = {
      ...current,
      positionX: event.positionX,
      positionY: event.positionY,
      width: event.width,
      height: event.height,
      rotationDeg: event.rotationDeg,
      ...(event.zIndex !== undefined ? { zIndex: event.zIndex } : {}),
    };
    const boundsError = this.validateSectionGeometry(projected);
    if (boundsError) {
      this.validationError.set(boundsError);
      return;
    }
    try {
      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((sec) =>
          getSectionDraftKey(sec) === targetKey ? { ...projected, draftKey: sec.draftKey } : sec,
        );
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
      this.selectedSectionKey.set(firstId);
      this.selectedSeatKeys.set(new Set());
      this.validationError.set(null);
    }
  }

  /**
   * Persists canvas layout-element mutations (create/move/resize/rotate/
   * duplicate/label-edit/z-order/remove) into the authoritative editor draft.
   * Without this feedback the canvas-local element list is reset by its input
   * binding on the next draft update (e.g. seat toggle), making added elements
   * vanish and dropping them from the save payload.
   */
  onCanvasElementsChanged(elements: VenueLayoutElement[]): void {
    this.validationError.set(null);
    try {
      this.editorState.replaceDraft((draft) => {
        draft.elements = [...(elements || [])];
        return draft;
      });
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to update layout elements';
      this.validationError.set(msg);
    }
  }

  onCanvasSeatSelected(event: CanvasSeatSelectedEvent): void {
    const eventKey = getCanvasSectionKey(event.section);
    const currentKey = this.currentSection() ? getSectionDraftKey(this.currentSection()!) : null;
    if (eventKey !== currentKey) {
      // Switching sections replaces the seat selection (single-select semantics).
      this.selectedSectionKey.set(eventKey);
      const seatKey = event.seat.seatId || `${event.seat.gridY}_${event.seat.gridX}`;
      this.selectedSeatKeys.set(new Set([seatKey]));
      this.validationError.set(null);
      return;
    }
    if (event.additive) {
      // Ctrl/Cmd-click toggles while preserving the current multi-selection.
      const currentKeys = new Set(this.selectedSeatKeys());
      const seatKey = event.seat.seatId || `${event.seat.gridY}_${event.seat.gridX}`;
      if (currentKeys.has(seatKey)) {
        currentKeys.delete(seatKey);
      } else {
        currentKeys.add(seatKey);
      }
      this.selectedSeatKeys.set(currentKeys);
      this.validationError.set(null);
      return;
    }
    // Plain click replaces the selection with the clicked seat only.
    const seatKey = event.seat.seatId || `${event.seat.gridY}_${event.seat.gridX}`;
    this.selectedSeatKeys.set(new Set([seatKey]));
    this.validationError.set(null);
  }

  onToolModeChange(mode: CanvasToolMode): void {
    this.toolMode.set(mode);
  }

  onCanvasSeatToggle(event: { seat: VenueSectionSeat; section: VenueSectionLayout }): void {
    const sec = event.section;
    const targetKey = getSectionDraftKey(sec);
    const targetSeat = event.seat;
    const nextActive = !targetSeat.isActive;

    if (nextActive) {
      const cap = this.venue()?.capacity ?? 0;
      if (cap > 0 && this.totalConfiguredActiveSeats() + 1 > cap) {
        const msg = `Cannot activate seat: venue capacity limit (${cap}) reached`;
        this.validationError.set(msg);
        this.snackBar.open(msg, 'Close', { duration: 4000, panelClass: 'snack-error' });
        return;
      }
    }

    try {
      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) => {
          if (getSectionDraftKey(s) === targetKey) {
            const updatedSeats = (s.seats || []).map((st) => {
              if (
                (st.seatId && st.seatId === targetSeat.seatId) ||
                (st.gridX === targetSeat.gridX && st.gridY === targetSeat.gridY)
              ) {
                return { ...st, isActive: nextActive };
              }
              return st;
            });
            return { ...s, seats: updatedSeats };
          }
          return s;
        });
        return draft;
      });
      this.validationError.set(null);
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to toggle seat';
      this.validationError.set(msg);
    }
  }

  onCanvasSeatPaint(event: {
    seat: VenueSectionSeat;
    section: VenueSectionLayout;
    color: string;
  }): void {
    const targetKey = getSectionDraftKey(event.section);
    // REV-001: exactly one stable entry per seat (seatId || grid key). Legacy
    // `RowLabel_Number` keys are never written; readers fall back to them.
    const colorKey = getSeatColorKey(event.seat);
    try {
      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) => {
          if (getSectionDraftKey(s) === targetKey) {
            const oldMeta = (s.shapeMetadata as Record<string, unknown>) || {};
            const oldSeatColors = (oldMeta['seatColors'] as Record<string, string>) || {};
            const updatedSeatColors: Record<string, string> = {
              ...oldSeatColors,
              [colorKey]: event.color,
            };
            const updatedMeta = {
              ...oldMeta,
              seatColors: updatedSeatColors,
            };
            return { ...s, shapeMetadata: updatedMeta };
          }
          return s;
        });
        return draft;
      });
      this.validationError.set(null);
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to paint seat';
      this.validationError.set(msg);
    }
  }

  onCanvasRowClick(event: {
    event: MouseEvent;
    rowLabel: string;
    section: VenueSectionLayout;
  }): void {
    const sec = event.section;
    const targetKey = getSectionDraftKey(sec);
    this.selectedSectionKey.set(targetKey);
    const rowSeats = (sec.seats || []).filter((s) => s.rowLabel === event.rowLabel);
    const keys = new Set(rowSeats.map((s) => s.seatId || `${s.gridY}_${s.gridX}`));
    this.selectedSeatKeys.set(keys);
  }

  onCanvasRowDblClick(event: {
    event: MouseEvent;
    rowLabel: string;
    section: VenueSectionLayout;
  }): void {
    this.onRowToggled({ rowLabel: event.rowLabel });
  }

  onCanvasColClick(event: {
    event: MouseEvent;
    colIndex: number;
    section: VenueSectionLayout;
  }): void {
    const sec = event.section;
    const targetKey = getSectionDraftKey(sec);
    this.selectedSectionKey.set(targetKey);
    const colSeats = (sec.seats || []).filter((s) => s.gridX === event.colIndex);
    const keys = new Set(colSeats.map((s) => s.seatId || `${s.gridY}_${s.gridX}`));
    this.selectedSeatKeys.set(keys);
  }

  onCanvasColDblClick(event: {
    event: MouseEvent;
    colIndex: number;
    section: VenueSectionLayout;
  }): void {
    this.onColToggled({ colIndex: event.colIndex });
  }

  onSectionColorChanged(color: string): void {
    const sec = this.currentSection();
    if (!sec) return;
    this.activePaintColor.set(color);
    const targetKey = getSectionDraftKey(sec);
    try {
      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) => {
          if (getSectionDraftKey(s) === targetKey) {
            const oldMeta = (s.shapeMetadata as Record<string, unknown>) || {};
            return {
              ...s,
              shapeMetadata: {
                ...oldMeta,
                color,
              },
            };
          }
          return s;
        });
        return draft;
      });
      this.validationError.set(null);
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to update section color';
      this.validationError.set(msg);
    }
  }

  onSeatColorAssigned(event: { seatKeys: string[]; color: string }): void {
    const sec = this.currentSection();
    if (!sec) return;
    const targetKey = getSectionDraftKey(sec);
    try {
      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) => {
          if (getSectionDraftKey(s) === targetKey) {
            const oldMeta = (s.shapeMetadata as Record<string, unknown>) || {};
            const oldSeatColors = (oldMeta['seatColors'] as Record<string, string>) || {};
            const updatedSeatColors = { ...oldSeatColors };
            for (const key of event.seatKeys) {
              updatedSeatColors[key] = event.color;
            }
            return {
              ...s,
              shapeMetadata: {
                ...oldMeta,
                seatColors: updatedSeatColors,
              },
            };
          }
          return s;
        });
        return draft;
      });
      this.validationError.set(null);
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to assign seat color';
      this.validationError.set(msg);
    }
  }

  onRowToggled(event: { rowLabel: string; active?: boolean }): void {
    const sec = this.currentSection();
    if (!sec) return;
    const targetKey = getSectionDraftKey(sec);
    const rowSeats = (sec.seats || []).filter((s) => s.rowLabel === event.rowLabel);
    if (!rowSeats.length) return;

    const shouldActivate =
      event.active !== undefined ? event.active : rowSeats.some((s) => !s.isActive);
    if (shouldActivate) {
      const needed = rowSeats.filter((s) => !s.isActive).length;
      const cap = this.venue()?.capacity ?? 0;
      if (cap > 0 && this.totalConfiguredActiveSeats() + needed > cap) {
        const msg = `Cannot activate row: venue capacity limit (${cap}) reached`;
        this.validationError.set(msg);
        this.snackBar.open(msg, 'Close', { duration: 4000, panelClass: 'snack-error' });
        return;
      }
    }

    try {
      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) => {
          if (getSectionDraftKey(s) === targetKey) {
            const updatedSeats = (s.seats || []).map((st) =>
              st.rowLabel === event.rowLabel ? { ...st, isActive: shouldActivate } : st,
            );
            return { ...s, seats: updatedSeats };
          }
          return s;
        });
        return draft;
      });
      this.validationError.set(null);
      this.snackBar.open(
        `Row ${event.rowLabel} ${shouldActivate ? 'activated' : 'turned into aisle'}!`,
        'Close',
        { duration: 2500 },
      );
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to toggle row';
      this.validationError.set(msg);
    }
  }

  onColToggled(event: { colIndex: number; active?: boolean }): void {
    const sec = this.currentSection();
    if (!sec) return;
    const targetKey = getSectionDraftKey(sec);
    const colSeats = (sec.seats || []).filter((s) => s.gridX === event.colIndex);
    if (!colSeats.length) return;

    const shouldActivate =
      event.active !== undefined ? event.active : colSeats.some((s) => !s.isActive);
    if (shouldActivate) {
      const needed = colSeats.filter((s) => !s.isActive).length;
      const cap = this.venue()?.capacity ?? 0;
      if (cap > 0 && this.totalConfiguredActiveSeats() + needed > cap) {
        const msg = `Cannot activate column: venue capacity limit (${cap}) reached`;
        this.validationError.set(msg);
        this.snackBar.open(msg, 'Close', { duration: 4000, panelClass: 'snack-error' });
        return;
      }
    }

    try {
      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) => {
          if (getSectionDraftKey(s) === targetKey) {
            const updatedSeats = (s.seats || []).map((st) =>
              st.gridX === event.colIndex ? { ...st, isActive: shouldActivate } : st,
            );
            return { ...s, seats: updatedSeats };
          }
          return s;
        });
        return draft;
      });
      this.validationError.set(null);
      this.snackBar.open(
        `Column ${event.colIndex + 1} ${shouldActivate ? 'activated' : 'turned into aisle'}!`,
        'Close',
        { duration: 2500 },
      );
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to toggle column';
      this.validationError.set(msg);
    }
  }

  onCenterAisleCreated(): void {
    const sec = this.currentSection();
    if (!sec || sec.colCount < 2) return;
    const centerCol = Math.floor(sec.colCount / 2);
    this.onColToggled({ colIndex: centerCol, active: false });
  }

  onDualAislesCreated(): void {
    const sec = this.currentSection();
    if (!sec || sec.colCount < 5) return;
    const col1 = Math.floor(sec.colCount / 3);
    const col2 = Math.floor((sec.colCount * 2) / 3);
    this.onColToggled({ colIndex: col1, active: false });
    this.onColToggled({ colIndex: col2, active: false });
  }

  onAllSeatsActivated(): void {
    const sec = this.currentSection();
    if (!sec) return;
    const targetKey = getSectionDraftKey(sec);
    const inactiveCount = (sec.seats || []).filter((s) => !s.isActive).length;
    const cap = this.venue()?.capacity ?? 0;
    if (cap > 0 && this.totalConfiguredActiveSeats() + inactiveCount > cap) {
      const msg = `Cannot activate all seats: venue capacity limit (${cap}) reached`;
      this.validationError.set(msg);
      this.snackBar.open(msg, 'Close', { duration: 4000, panelClass: 'snack-error' });
      return;
    }

    try {
      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) => {
          if (getSectionDraftKey(s) === targetKey) {
            const updatedSeats = (s.seats || []).map((st) => ({ ...st, isActive: true }));
            return { ...s, seats: updatedSeats };
          }
          return s;
        });
        return draft;
      });
      this.validationError.set(null);
      this.snackBar.open(`All seats in "${sec.name}" activated!`, 'Close', { duration: 2500 });
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to activate all seats';
      this.validationError.set(msg);
    }
  }

  onRowAppended(): void {
    const sec = this.currentSection();
    if (!sec) return;
    if (sec.rowCount >= 50) {
      this.validationError.set('Maximum 50 rows allowed per section');
      return;
    }
    const newRowIndex = sec.rowCount;
    const newRowLabel = getRowLabel(newRowIndex);
    const pitchY = 44;
    const lastRowSeats = (sec.seats || []).filter((s) => s.gridY === newRowIndex - 1);
    const startY =
      lastRowSeats.length > 0
        ? Math.max(...lastRowSeats.map((s) => s.positionY)) + pitchY
        : 20 + newRowIndex * pitchY;

    const cap = this.venue()?.capacity ?? 0;
    if (cap > 0 && this.totalConfiguredActiveSeats() + sec.colCount > cap) {
      const msg = `Cannot add row: venue capacity limit (${cap}) would be exceeded`;
      this.validationError.set(msg);
      this.snackBar.open(msg, 'Close', { duration: 4000, panelClass: 'snack-error' });
      return;
    }

    const newSeats: VenueSectionSeat[] = [];
    for (let c = 0; c < sec.colCount; c++) {
      const colSeats = (sec.seats || []).filter((s) => s.gridX === c);
      const posX = colSeats.length > 0 ? colSeats[0].positionX : 20 + c * 44;
      newSeats.push({
        seatId: null,
        rowLabel: newRowLabel,
        seatNumber: c + 1,
        gridX: c,
        gridY: newRowIndex,
        positionX: posX,
        positionY: startY,
        isActive: true,
      });
    }

    const newHeight = Math.max(sec.height, startY + 44);
    const targetKey = getSectionDraftKey(sec);

    // REV-002: pre-mutation duplicate + upper-bound validation (same
    // no-mutation-on-violation pattern as onSectionTransformChanged).
    if (newHeight > MAX_POSITION) {
      const msg = `Cannot add row: projected section height (${newHeight}) exceeds maximum (${MAX_POSITION})`;
      this.validationError.set(msg);
      this.snackBar.open(msg, 'Close', { duration: 4000, panelClass: 'snack-error' });
      return;
    }
    const appendError = this.generator.validateSeatAppend(sec, newSeats);
    if (appendError) {
      this.validationError.set(appendError);
      this.snackBar.open(appendError, 'Close', { duration: 4000, panelClass: 'snack-error' });
      return;
    }
    const projected: VenueSectionLayout = {
      ...sec,
      rowCount: sec.rowCount + 1,
      height: newHeight,
      seats: [...(sec.seats || []), ...newSeats],
    };
    const boundsError = this.generator.validateSectionBounds(projected);
    if (boundsError) {
      this.validationError.set(boundsError);
      this.snackBar.open(boundsError, 'Close', { duration: 4000, panelClass: 'snack-error' });
      return;
    }

    try {
      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) => {
          if (getSectionDraftKey(s) === targetKey) {
            return {
              ...s,
              rowCount: sec.rowCount + 1,
              height: newHeight,
              seats: [...(s.seats || []), ...newSeats],
            };
          }
          return s;
        });
        return draft;
      });
      this.validationError.set(null);
      this.snackBar.open(`Added Row ${newRowLabel}!`, 'Close', { duration: 2500 });
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to add row';
      this.validationError.set(msg);
    }
  }

  onColAppended(): void {
    const sec = this.currentSection();
    if (!sec) return;
    if (sec.colCount >= 50) {
      this.validationError.set('Maximum 50 columns allowed per section');
      return;
    }
    const newColIndex = sec.colCount;
    const pitchX = 44;
    const lastColSeats = (sec.seats || []).filter((s) => s.gridX === newColIndex - 1);
    const startX =
      lastColSeats.length > 0
        ? Math.max(...lastColSeats.map((s) => s.positionX)) + pitchX
        : 20 + newColIndex * pitchX;

    const cap = this.venue()?.capacity ?? 0;
    if (cap > 0 && this.totalConfiguredActiveSeats() + sec.rowCount > cap) {
      const msg = `Cannot add column: venue capacity limit (${cap}) would be exceeded`;
      this.validationError.set(msg);
      this.snackBar.open(msg, 'Close', { duration: 4000, panelClass: 'snack-error' });
      return;
    }

    const newSeats: VenueSectionSeat[] = [];
    for (let r = 0; r < sec.rowCount; r++) {
      const rowSeats = (sec.seats || []).filter((s) => s.gridY === r);
      const posY = rowSeats.length > 0 ? rowSeats[0].positionY : 20 + r * 44;
      const rowLabel = rowSeats.length > 0 ? rowSeats[0].rowLabel : getRowLabel(r);
      newSeats.push({
        seatId: null,
        rowLabel,
        seatNumber: newColIndex + 1,
        gridX: newColIndex,
        gridY: r,
        positionX: startX,
        positionY: posY,
        isActive: true,
      });
    }

    const newWidth = Math.max(sec.width, startX + 44);
    const targetKey = getSectionDraftKey(sec);

    // REV-002: pre-mutation duplicate + upper-bound validation (same
    // no-mutation-on-violation pattern as onSectionTransformChanged).
    if (newWidth > MAX_POSITION) {
      const msg = `Cannot add column: projected section width (${newWidth}) exceeds maximum (${MAX_POSITION})`;
      this.validationError.set(msg);
      this.snackBar.open(msg, 'Close', { duration: 4000, panelClass: 'snack-error' });
      return;
    }
    const appendError = this.generator.validateSeatAppend(sec, newSeats);
    if (appendError) {
      this.validationError.set(appendError);
      this.snackBar.open(appendError, 'Close', { duration: 4000, panelClass: 'snack-error' });
      return;
    }
    const projected: VenueSectionLayout = {
      ...sec,
      colCount: sec.colCount + 1,
      width: newWidth,
      seats: [...(sec.seats || []), ...newSeats],
    };
    const boundsError = this.generator.validateSectionBounds(projected);
    if (boundsError) {
      this.validationError.set(boundsError);
      this.snackBar.open(boundsError, 'Close', { duration: 4000, panelClass: 'snack-error' });
      return;
    }

    try {
      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) => {
          if (getSectionDraftKey(s) === targetKey) {
            return {
              ...s,
              colCount: sec.colCount + 1,
              width: newWidth,
              seats: [...(s.seats || []), ...newSeats],
            };
          }
          return s;
        });
        return draft;
      });
      this.validationError.set(null);
      this.snackBar.open(`Added Column ${newColIndex + 1}!`, 'Close', { duration: 2500 });
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to add column';
      this.validationError.set(msg);
    }
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
      const targetKey = getSectionDraftKey(sec);
      const deactivated = this.generator.deactivateSection(sec);
      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) =>
          getSectionDraftKey(s) === targetKey
            ? { ...deactivated, draftKey: s.draftKey ?? deactivated.draftKey }
            : s,
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
      const targetKey = getSectionDraftKey(sec);
      const reactivated = this.generator.reactivateSection(
        sec,
        this.sections(),
        this.venue()?.capacity,
        this.countOtherActiveSeats(targetKey),
      );
      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) =>
          getSectionDraftKey(s) === targetKey
            ? { ...reactivated, draftKey: s.draftKey ?? reactivated.draftKey }
            : s,
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
    const targetKey = getSectionDraftKey(sec);
    const { draftKey: _ignoredDraftKey, sectionId: _ignoredSectionId, ...safePartial } = partial;
    // REV-008: name-uniqueness pre-check mirrors createSection/reactivateSection.
    if (safePartial.name !== undefined) {
      const candidate = (safePartial.name as string)?.trim() ?? '';
      const clash = this.sections().some(
        (s) =>
          getSectionDraftKey(s) !== targetKey &&
          s.isActive &&
          s.name.trim().toLowerCase() === candidate.toLowerCase(),
      );
      if (clash) {
        this.validationError.set(`Section name "${candidate}" already exists`);
        return;
      }
    }
    const projected: VenueSectionLayout = { ...sec, ...safePartial };
    const geometryChanged =
      safePartial.positionX !== undefined ||
      safePartial.positionY !== undefined ||
      safePartial.width !== undefined ||
      safePartial.height !== undefined ||
      safePartial.rotationDeg !== undefined ||
      safePartial.zIndex !== undefined;
    if (geometryChanged) {
      const geometryError = this.validateSectionGeometry(projected);
      if (geometryError) {
        this.validationError.set(geometryError);
        return;
      }
    }
    try {
      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) => {
          if (getSectionDraftKey(s) === targetKey) {
            return { ...s, ...safePartial };
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
      // REV-001: never replace persisted seat identities with null-ID records.
      if ((sec.seats || []).some((s) => s.seatId !== null)) {
        throw new Error(
          `Cannot regenerate section "${sec.name}" because it contains saved seats; ` +
            `generation would discard stable seat identities. Create a new draft section instead.`,
        );
      }
      const targetKey = getSectionDraftKey(sec);
      const seats = this.generator.generateSeats(options);
      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) => {
          if (getSectionDraftKey(s) === targetKey) {
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
      const targetKey = getSectionDraftKey(sec);
      const updated = this.generator.bulkSetActive(
        sec,
        this.selectedSeatKeys(),
        active,
        this.venue()?.capacity,
        this.totalConfiguredActiveSeats(),
      );

      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) =>
          getSectionDraftKey(s) === targetKey
            ? { ...updated, draftKey: s.draftKey ?? updated.draftKey }
            : s,
        );
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
      const targetKey = getSectionDraftKey(sec);
      const updated = this.generator.bulkTranslate(sec, this.selectedSeatKeys(), {
        deltaX: delta.deltaX,
        deltaY: delta.deltaY,
        sectionWidth: sec.width,
        sectionHeight: sec.height,
      });

      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) =>
          getSectionDraftKey(s) === targetKey
            ? { ...updated, draftKey: s.draftKey ?? updated.draftKey }
            : s,
        );
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
      const targetKey = getSectionDraftKey(sec);
      const staleLabelKeys = new Set(
        (sec.seats || [])
          .filter((s) => isSeatSelected(s, this.selectedSeatKeys()))
          .map((s) => `${s.rowLabel}_${s.seatNumber}`),
      );
      const renamed = this.generator.bulkSetRowLabel(sec, this.selectedSeatKeys(), label);
      const updated = this.pruneStaleLabelColorKeys(renamed, staleLabelKeys);

      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) =>
          getSectionDraftKey(s) === targetKey
            ? { ...updated, draftKey: s.draftKey ?? updated.draftKey }
            : s,
        );
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
      const targetKey = getSectionDraftKey(sec);
      const staleLabelKeys = new Set(
        (sec.seats || [])
          .filter((s) => isSeatSelected(s, this.selectedSeatKeys()))
          .map((s) => `${s.rowLabel}_${s.seatNumber}`),
      );
      const renumbered = this.generator.bulkRenumber(sec, this.selectedSeatKeys(), startNumber);
      const updated = this.pruneStaleLabelColorKeys(renumbered, staleLabelKeys);

      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) =>
          getSectionDraftKey(s) === targetKey
            ? { ...updated, draftKey: s.draftKey ?? updated.draftKey }
            : s,
        );
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
