import {
  ChangeDetectionStrategy,
  Component,
  HostListener,
  OnDestroy,
  OnInit,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { FormBuilder, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import {
  SaveVenueLayoutRequest,
  VenueLayout,
  VenueLayoutElement,
  VenueSectionLayout,
  VenueSectionSeat,
} from '../../../../models/venue.model';
import { ApiErrorResponse, ValidationErrorDetail } from '../../../../models/api-error.model';
import { AdminVenueApiService } from '../../../../services/admin-venue-api.service';
import {
  LayoutConflictDialogComponent,
  LayoutConflictDialogResult,
} from '../layout-conflict-dialog/layout-conflict-dialog.component';
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
import { PendingChangesAware } from '../../../../core/guards/pending-changes.guard';
import { LayoutHistoryService } from '../../../../services/layout-history.service';

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
export class VenueGridDesignerComponent implements OnInit, OnDestroy, PendingChangesAware {
  readonly getRowLabel = getRowLabel;

  private readonly route = inject(ActivatedRoute);
  private readonly editorState = inject(VenueLayoutEditorStateService);
  private readonly generator = inject(SeatLayoutGeneratorService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly fb = inject(FormBuilder);
  private readonly history = inject(LayoutHistoryService);
  private readonly venueApi = inject(AdminVenueApiService);
  private readonly dialog = inject(MatDialog);

  readonly canvasRef = viewChild<LayoutCanvasComponent>('canvasRef');

  readonly venueId = signal<string>('');
  readonly isLoading = signal<boolean>(true);
  readonly isCreatingSection = signal<boolean>(false);
  readonly showAddSectionModal = signal<boolean>(false);
  readonly selectedSectionKey = signal<string | null>(null);
  readonly selectedSeatKeys = signal<Set<string>>(new Set());
  readonly validationError = signal<string | null>(null);
  readonly serverValidationErrors = signal<ValidationErrorDetail[]>([]);
  readonly saveCorrelationId = signal<string | null>(null);
  readonly toolMode = signal<CanvasToolMode>('select');
  readonly activePaintColor = signal<string>('#6366f1');
  readonly announcement = signal<string>('');
  readonly snapStep = signal<number>(0);
  readonly selectedLayoutElement = signal<VenueLayoutElement | null>(null);
  readonly selectedElementIndex = signal<number | null>(null);
  readonly prefersReducedMotion = signal<boolean>(false);

  private lastFocusedElement: HTMLElement | null = null;

  // Expose editor state signals
  readonly isSaving = this.editorState.isSaving;
  readonly isDirty = this.editorState.isDirty;
  readonly loadError = this.editorState.loadError;
  readonly canUndo = this.history.canUndo;
  readonly canRedo = this.history.canRedo;

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
    try {
      const media = window.matchMedia?.('(prefers-reduced-motion: reduce)');
      this.prefersReducedMotion.set(media?.matches ?? false);
    } catch {
      this.prefersReducedMotion.set(false);
    }
    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.venueId.set(id);
      this.loadVenueLayout(id);
    }
  }

  ngOnDestroy(): void {
    // HostListener registrations (keydown, beforeunload) are torn down
    // automatically by Angular; end any coalesced pointer gesture state.
    this.history.endCoalesced();
  }

  // --- PendingChangesAware (route-leave + browser-close protection; UX only) ---

  hasPendingChanges(): boolean {
    return this.editorState.isDirty();
  }

  confirmDiscardChanges(): boolean {
    return window.confirm(
      'You have unsaved changes. If you leave now, unsaved layout edits will be discarded. Continue?',
    );
  }

  @HostListener('window:beforeunload', ['$event'])
  onBeforeUnload(event: BeforeUnloadEvent): void {
    if (this.hasPendingChanges()) {
      // REV-P11-009-001: Chrome/Edge/Firefox only show the refresh/close
      // confirmation when returnValue is set; preventDefault() alone is not
      // enough. Keep the clean-state early-out so clean exits never prompt.
      event.preventDefault();
      event.returnValue = '';
    }
  }

  // --- Local history boundary (single commit point for every mutation) ---

  /** TASK-P11-010: true while validation/save flight is active; mutations must no-op. */
  private isMutationLocked(): boolean {
    return this.editorState.isSaving();
  }

  private currentDraftLayout(): VenueLayout | null {
    return this.editorState.layout() as VenueLayout | null;
  }

  private pushHistoryCheckpoint(): void {
    const current = this.currentDraftLayout();
    if (current) {
      this.history.push(current);
    }
  }

  private beginPointerGestureCheckpoint(): void {
    const current = this.currentDraftLayout();
    if (current) {
      this.history.beginCoalesced(current);
    }
  }

  /** Commits the active coalesced pointer gesture as one undo entry. */
  endPointerGesture(): void {
    this.history.endCoalesced();
  }

  private announceResult(baseMessage: string): void {
    const seatCount = this.selectedSeatKeys().size;
    const sectionName = this.currentSection()?.name ?? 'none';
    this.announcement.set(`${baseMessage} ${seatCount} seats selected in ${sectionName}.`);
  }

  undo(): void {
    if (this.isMutationLocked()) {
      return;
    }
    const current = this.currentDraftLayout();
    if (!current || !this.history.canUndo()) {
      return;
    }
    const previous = this.history.undo(current);
    if (!previous) {
      return;
    }
    try {
      this.editorState.replaceDraft(previous);
      this.selectedSeatKeys.set(new Set());
      this.validationError.set(null);
      this.announceResult('Undo applied.');
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to undo change';
      this.validationError.set(msg);
    }
  }

  redo(): void {
    if (this.isMutationLocked()) {
      return;
    }
    const current = this.currentDraftLayout();
    if (!current || !this.history.canRedo()) {
      return;
    }
    const next = this.history.redo(current);
    if (!next) {
      return;
    }
    try {
      this.editorState.replaceDraft(next);
      this.selectedSeatKeys.set(new Set());
      this.validationError.set(null);
      this.announceResult('Redo applied.');
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to redo change';
      this.validationError.set(msg);
    }
  }

  onElementSelected(element: VenueLayoutElement | null): void {
    if (!element) {
      this.selectedLayoutElement.set(null);
      this.selectedElementIndex.set(null);
      return;
    }
    this.selectedLayoutElement.set({
      ...element,
      geometry: { ...element.geometry },
    });
    const internal = this.canvasRef()?.internalElements() ?? [];
    const idx = internal.indexOf(element);
    if (idx >= 0) {
      this.selectedElementIndex.set(idx);
    } else {
      const draftElements = this.currentDraftLayout()?.elements ?? [];
      if (element.elementId) {
        const byId = draftElements.findIndex((el) => el.elementId === element.elementId);
        this.selectedElementIndex.set(byId >= 0 ? byId : null);
      } else {
        this.selectedElementIndex.set(null);
      }
    }
  }

  private isEditableKeyboardTarget(event: KeyboardEvent): boolean {
    if (event.isComposing) {
      return true;
    }
    const target = event.target as HTMLElement | null;
    if (!target || typeof target.tagName !== 'string') {
      return false;
    }
    const tag = target.tagName.toUpperCase();
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') {
      return true;
    }
    if (target.isContentEditable) {
      return true;
    }
    try {
      if (typeof target.closest === 'function' && target.closest('[contenteditable]')) {
        const closest = target.closest('[contenteditable]') as HTMLElement;
        if (closest && closest.isContentEditable !== false) {
          return true;
        }
      }
    } catch {
      // ignore DOM query failures and treat as non-editable
    }
    return false;
  }

  private arrowStep(shiftKey: boolean): number {
    const snap = this.snapStep();
    if (snap > 0) {
      return shiftKey ? snap * 10 : snap;
    }
    return shiftKey ? 10 : 1;
  }

  private moveSelectedByArrow(deltaX: number, deltaY: number): void {
    if (this.isMutationLocked()) {
      return;
    }
    const seatKeys = this.selectedSeatKeys();
    if (seatKeys.size > 0) {
      const sec = this.currentSection();
      if (!sec) {
        return;
      }
      try {
        const targetKey = getSectionDraftKey(sec);
        const updated = this.generator.bulkTranslate(sec, seatKeys, {
          deltaX,
          deltaY,
          sectionWidth: sec.width,
          sectionHeight: sec.height,
        });
        this.pushHistoryCheckpoint();
        this.editorState.replaceDraft((draft) => {
          draft.sections = draft.sections.map((s) =>
            getSectionDraftKey(s) === targetKey
              ? { ...updated, draftKey: s.draftKey ?? updated.draftKey }
              : s,
          );
          return draft;
        });
        this.validationError.set(null);
        this.announceResult(`Moved ${seatKeys.size} seats by ${deltaX}, ${deltaY}.`);
      } catch (err) {
        const msg = err instanceof Error ? err.message : 'Failed to move seats';
        this.validationError.set(msg);
      }
      return;
    }

    const elementIndex = this.selectedElementIndex();
    const selectedElement =
      this.selectedLayoutElement() ?? this.canvasRef()?.selectedElement() ?? null;
    if (selectedElement && elementIndex !== null && elementIndex !== undefined) {
      const draft = this.currentDraftLayout();
      const target = draft?.elements?.[elementIndex];
      if (!target) {
        return;
      }
      const nextX = Math.min(100000, Math.max(0, target.geometry.x + deltaX));
      const nextY = Math.min(100000, Math.max(0, target.geometry.y + deltaY));
      this.pushHistoryCheckpoint();
      try {
        const index = elementIndex;
        this.editorState.replaceDraft((d) => {
          d.elements = d.elements.map((el, i) =>
            i === index ? { ...el, geometry: { ...el.geometry, x: nextX, y: nextY } } : el,
          );
          return d;
        });
        this.validationError.set(null);
        this.announceResult(`Moved element by ${deltaX}, ${deltaY}.`);
      } catch (err) {
        const msg = err instanceof Error ? err.message : 'Failed to move element';
        this.validationError.set(msg);
      }
      return;
    }

    const sec = this.currentSection();
    if (!sec) {
      return;
    }
    const nextX = Math.min(100000, Math.max(0, sec.positionX + deltaX));
    const nextY = Math.min(100000, Math.max(0, sec.positionY + deltaY));
    const projected: VenueSectionLayout = { ...sec, positionX: nextX, positionY: nextY };
    const geometryError = this.validateSectionGeometry(projected);
    if (geometryError) {
      this.validationError.set(geometryError);
      return;
    }
    const targetKey = getSectionDraftKey(sec);
    this.pushHistoryCheckpoint();
    try {
      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) =>
          getSectionDraftKey(s) === targetKey ? { ...projected, draftKey: s.draftKey } : s,
        );
        return draft;
      });
      this.validationError.set(null);
      this.announceResult(`Moved section ${sec.name} by ${deltaX}, ${deltaY}.`);
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Invalid section move';
      this.validationError.set(msg);
    }
  }

  private handleDeleteKey(): void {
    if (this.isMutationLocked()) {
      return;
    }
    const seatKeys = this.selectedSeatKeys();
    if (seatKeys.size > 0) {
      const sec = this.currentSection();
      if (!sec) {
        return;
      }
      const targetKey = getSectionDraftKey(sec);
      const selectedSeats = (sec.seats || []).filter((s) =>
        seatKeys.has(s.seatId || `${s.gridY}_${s.gridX}`),
      );
      if (selectedSeats.length === 0) {
        return;
      }
      const hasPersisted = selectedSeats.some((s) => s.seatId !== null);
      const hasDrafts = selectedSeats.some((s) => s.seatId === null);
      const seatKeyOf = (st: VenueSectionSeat): string => st.seatId || `${st.gridY}_${st.gridX}`;
      this.pushHistoryCheckpoint();
      try {
        this.editorState.replaceDraft((draft) => {
          draft.sections = draft.sections.map((s) => {
            if (getSectionDraftKey(s) !== targetKey) {
              return s;
            }
            if (hasDrafts && !hasPersisted) {
              return {
                ...s,
                seats: (s.seats || []).filter((st) => !seatKeys.has(seatKeyOf(st))),
              };
            }
            if (hasDrafts && hasPersisted) {
              // REV-P11-009-002: mixed selection applies both documented
              // semantics in one commit — deactivate selected persisted
              // seats AND remove selected null-ID draft records.
              return {
                ...s,
                seats: (s.seats || []).flatMap((st) => {
                  if (!seatKeys.has(seatKeyOf(st))) {
                    return [st];
                  }
                  if (st.seatId === null) {
                    return [];
                  }
                  return [{ ...st, isActive: false }];
                }),
              };
            }
            return {
              ...s,
              seats: (s.seats || []).map((st) =>
                seatKeys.has(seatKeyOf(st)) && st.seatId !== null ? { ...st, isActive: false } : st,
              ),
            };
          });
          return draft;
        });
        this.selectedSeatKeys.set(new Set());
        this.validationError.set(null);
        this.announceResult(
          hasPersisted && hasDrafts
            ? 'Deactivated selected seats and removed selected draft seats.'
            : hasPersisted
              ? 'Deactivated selected seats.'
              : 'Removed selected draft seats.',
        );
      } catch (err) {
        const msg = err instanceof Error ? err.message : 'Failed to delete seats';
        this.validationError.set(msg);
      }
      return;
    }

    const elementIndex = this.selectedElementIndex();
    const hasCanvasElement =
      elementIndex !== null && elementIndex !== undefined
        ? true
        : this.canvasRef()?.selectedElement() !== null &&
          this.canvasRef()?.selectedElement() !== undefined;
    if (hasCanvasElement) {
      const draft = this.currentDraftLayout();
      const resolvedIndex =
        elementIndex ??
        this.canvasRef()?.internalElements().indexOf(this.canvasRef()?.selectedElement()!) ??
        -1;
      if (!draft || resolvedIndex < 0 || resolvedIndex >= draft.elements.length) {
        return;
      }
      this.pushHistoryCheckpoint();
      try {
        this.editorState.replaceDraft((d) => {
          d.elements = d.elements.filter((_, i) => i !== resolvedIndex);
          return d;
        });
        this.selectedLayoutElement.set(null);
        this.selectedElementIndex.set(null);
        this.canvasRef()?.deselectElement();
        this.validationError.set(null);
        this.announceResult('Removed selected element.');
      } catch (err) {
        const msg = err instanceof Error ? err.message : 'Failed to remove element';
        this.validationError.set(msg);
      }
      return;
    }

    const sec = this.currentSection();
    if (!sec) {
      return;
    }
    if (sec.sectionId === null) {
      this.removeSection();
    } else {
      this.deactivateSection();
    }
  }

  private handleEscapeKey(): void {
    if (this.isMutationLocked() && this.history.isCoalescing()) {
      return;
    }
    if (this.history.isCoalescing()) {
      const current = this.currentDraftLayout();
      const baseline = this.history.cancelCoalesced(current);
      if (baseline && current) {
        try {
          this.editorState.replaceDraft(baseline);
        } catch {
          // Baseline restore is best-effort; selection is still cleared below.
        }
      }
      this.selectedSeatKeys.set(new Set());
      this.selectedLayoutElement.set(null);
      this.selectedElementIndex.set(null);
      this.canvasRef()?.deselectElement();
      this.announceResult('Pointer operation cancelled.');
      return;
    }
    this.selectedSeatKeys.set(new Set());
    this.selectedLayoutElement.set(null);
    this.selectedElementIndex.set(null);
    this.canvasRef()?.deselectElement();
    this.announceResult('Selection cleared.');
  }

  @HostListener('keydown', ['$event'])
  onKeyDown(event: KeyboardEvent): void {
    if (this.isEditableKeyboardTarget(event)) {
      return;
    }
    if (this.isMutationLocked()) {
      const mutationKey =
        event.key === 'Delete' ||
        event.key === 'Backspace' ||
        event.key === 'ArrowUp' ||
        event.key === 'ArrowDown' ||
        event.key === 'ArrowLeft' ||
        event.key === 'ArrowRight' ||
        ((event.ctrlKey || event.metaKey) &&
          typeof event.key === 'string' &&
          event.key.toLowerCase() === 'z') ||
        (event.ctrlKey && typeof event.key === 'string' && event.key.toLowerCase() === 'y');
      if (mutationKey) {
        return;
      }
    }
    const key = event.key;
    const lowerKey = typeof key === 'string' ? key.toLowerCase() : '';
    const ctrlOrCmd = event.ctrlKey || event.metaKey;

    if (ctrlOrCmd && lowerKey === 'z' && !event.shiftKey) {
      event.preventDefault();
      this.undo();
      return;
    }
    if ((ctrlOrCmd && event.shiftKey && lowerKey === 'z') || (event.ctrlKey && lowerKey === 'y')) {
      event.preventDefault();
      this.redo();
      return;
    }
    if (key === 'Delete' || key === 'Backspace') {
      event.preventDefault();
      this.handleDeleteKey();
      return;
    }
    if (key === 'Escape') {
      this.handleEscapeKey();
      return;
    }
    if (key === 'ArrowUp' || key === 'ArrowDown' || key === 'ArrowLeft' || key === 'ArrowRight') {
      if (event.ctrlKey || event.metaKey || event.altKey) {
        return;
      }
      if (!this.currentSection()) {
        return;
      }
      event.preventDefault();
      const step = this.arrowStep(event.shiftKey);
      if (key === 'ArrowUp') {
        this.moveSelectedByArrow(0, -step);
      } else if (key === 'ArrowDown') {
        this.moveSelectedByArrow(0, step);
      } else if (key === 'ArrowLeft') {
        this.moveSelectedByArrow(-step, 0);
      } else {
        this.moveSelectedByArrow(step, 0);
      }
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
        this.history.clear();
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
    if (this.isMutationLocked()) {
      return;
    }
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
      this.beginPointerGestureCheckpoint();
      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((sec) =>
          getSectionDraftKey(sec) === targetKey ? { ...projected, draftKey: sec.draftKey } : sec,
        );
        return draft;
      });
      this.announceResult('Section transform applied.');
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
    if (this.isMutationLocked()) {
      return;
    }
    this.validationError.set(null);
    try {
      this.beginPointerGestureCheckpoint();
      this.editorState.replaceDraft((draft) => {
        draft.elements = [...(elements || [])];
        return draft;
      });
      this.announceResult('Layout elements updated.');
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
    if (this.isMutationLocked()) {
      return;
    }
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
      this.pushHistoryCheckpoint();
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
      this.announceResult('Seat toggled.');
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
    if (this.isMutationLocked()) {
      return;
    }
    const targetKey = getSectionDraftKey(event.section);
    // REV-001: exactly one stable entry per seat (seatId || grid key). Legacy
    // `RowLabel_Number` keys are never written; readers fall back to them.
    const colorKey = getSeatColorKey(event.seat);
    try {
      this.pushHistoryCheckpoint();
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
      this.announceResult('Seat color applied.');
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
    if (this.isMutationLocked()) {
      return;
    }
    const sec = this.currentSection();
    if (!sec) return;
    this.activePaintColor.set(color);
    const targetKey = getSectionDraftKey(sec);
    try {
      this.pushHistoryCheckpoint();
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
      this.announceResult('Section color updated.');
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to update section color';
      this.validationError.set(msg);
    }
  }

  onSeatColorAssigned(event: { seatKeys: string[]; color: string }): void {
    if (this.isMutationLocked()) {
      return;
    }
    const sec = this.currentSection();
    if (!sec) return;
    const targetKey = getSectionDraftKey(sec);
    try {
      this.pushHistoryCheckpoint();
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
      this.announceResult('Seat color assigned.');
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to assign seat color';
      this.validationError.set(msg);
    }
  }

  onRowToggled(event: { rowLabel: string; active?: boolean }): void {
    if (this.isMutationLocked()) {
      return;
    }
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
      this.pushHistoryCheckpoint();
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
      this.announceResult(`Row ${event.rowLabel} toggled.`);
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
    if (this.isMutationLocked()) {
      return;
    }
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
      this.pushHistoryCheckpoint();
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
      this.announceResult(`Column ${event.colIndex + 1} toggled.`);
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
    if (this.isMutationLocked()) {
      return;
    }
    const sec = this.currentSection();
    if (!sec || sec.colCount < 2) return;
    const centerCol = Math.floor(sec.colCount / 2);
    this.onColToggled({ colIndex: centerCol, active: false });
  }

  onDualAislesCreated(): void {
    if (this.isMutationLocked()) {
      return;
    }
    const sec = this.currentSection();
    if (!sec || sec.colCount < 5) return;
    const col1 = Math.floor(sec.colCount / 3);
    const col2 = Math.floor((sec.colCount * 2) / 3);
    this.onColToggled({ colIndex: col1, active: false });
    this.onColToggled({ colIndex: col2, active: false });
  }

  onAllSeatsActivated(): void {
    if (this.isMutationLocked()) {
      return;
    }
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
      this.pushHistoryCheckpoint();
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
      this.announceResult('All seats activated.');
      this.snackBar.open(`All seats in "${sec.name}" activated!`, 'Close', { duration: 2500 });
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to activate all seats';
      this.validationError.set(msg);
    }
  }

  onRowAppended(): void {
    if (this.isMutationLocked()) {
      return;
    }
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
      this.pushHistoryCheckpoint();
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
      this.announceResult(`Added row ${newRowLabel}.`);
      this.snackBar.open(`Added Row ${newRowLabel}!`, 'Close', { duration: 2500 });
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to add row';
      this.validationError.set(msg);
    }
  }

  onColAppended(): void {
    if (this.isMutationLocked()) {
      return;
    }
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
      this.pushHistoryCheckpoint();
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
      this.announceResult(`Added column ${newColIndex + 1}.`);
      this.snackBar.open(`Added Column ${newColIndex + 1}!`, 'Close', { duration: 2500 });
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to add column';
      this.validationError.set(msg);
    }
  }

  // --- Save / Discard Layout (TASK-P11-010 atomic validate -> save) ---

  private resolveSaveVenueId(): string | null {
    const fromRoute = this.venueId();
    if (fromRoute) {
      return fromRoute;
    }
    const layout = this.currentDraftLayout();
    return layout?.venueId ?? null;
  }

  private runLocalPreSaveValidation(): string | null {
    const layout = this.currentDraftLayout();
    if (!layout) {
      return 'No layout loaded. Reload the venue before saving.';
    }
    const sections = (layout.sections as VenueSectionLayout[]) || [];
    const activeNames = new Set<string>();
    for (const sec of sections) {
      const trimmed = (sec.name ?? '').trim();
      if (!trimmed) {
        return 'Section name must not be blank';
      }
      if (!Number.isInteger(sec.rowCount) || sec.rowCount < 1 || sec.rowCount > 50) {
        return `Section "${trimmed}" rowCount must be an integer between 1 and 50`;
      }
      if (!Number.isInteger(sec.colCount) || sec.colCount < 1 || sec.colCount > 50) {
        return `Section "${trimmed}" colCount must be an integer between 1 and 50`;
      }
      if (sec.isActive) {
        const lower = trimmed.toLowerCase();
        if (activeNames.has(lower)) {
          return `Section name "${trimmed}" already exists`;
        }
        activeNames.add(lower);
      }
      const geometryError = this.validateSectionGeometry(sec);
      if (geometryError) {
        return geometryError;
      }
      const boundsError = this.generator.validateSectionBounds(sec);
      if (boundsError) {
        return boundsError;
      }
      const seen = new Set<string>();
      for (const seat of sec.seats || []) {
        if (!seat.rowLabel || !seat.rowLabel.trim()) {
          return `Seat in section "${trimmed}" has a blank row label`;
        }
        if (!Number.isInteger(seat.seatNumber) || seat.seatNumber < 1) {
          return `Seat Row ${seat.rowLabel} in section "${trimmed}" has an invalid seat number`;
        }
        const key = `${seat.rowLabel.trim().toUpperCase()}|${seat.seatNumber}`;
        if (seen.has(key)) {
          return `Section "${trimmed}" has duplicate row/seat (${seat.rowLabel}, ${seat.seatNumber})`;
        }
        seen.add(key);
      }
    }
    const cap = layout.capacity ?? 0;
    if (cap > 0 && this.totalConfiguredActiveSeats() > cap) {
      return `Projected active seat count (${this.totalConfiguredActiveSeats()}) exceeds venue capacity (${cap})`;
    }
    return null;
  }

  private focusSaveValidationSummary(): void {
    queueMicrotask(() => {
      try {
        document.getElementById('saveValidationSummary')?.focus?.();
      } catch {
        // Focus is best-effort for screen readers; validation text is still visible.
      }
    });
  }

  private extractSaveApiError(err: unknown): {
    status: number | null;
    errorCode: string | null;
    message: string;
    validationErrors: ValidationErrorDetail[];
    correlationId: string | null;
  } {
    const httpErr = err as HttpErrorResponse | null | undefined;
    const status =
      typeof httpErr?.status === 'number' ? (httpErr as HttpErrorResponse).status : null;
    const body = (httpErr as HttpErrorResponse | undefined)?.error as
      Partial<ApiErrorResponse> | string | null | undefined;
    const apiError = typeof body === 'object' && body !== null ? body : null;
    const validationErrors = Array.isArray(apiError?.validationErrors)
      ? ((apiError as ApiErrorResponse).validationErrors ?? [])
      : [];
    const correlationId =
      typeof apiError?.correlationId === 'string' ? apiError.correlationId : null;
    const message =
      (validationErrors[0]?.message as string | undefined) ||
      (typeof apiError?.message === 'string' ? apiError.message : null) ||
      (err as { message?: string })?.message ||
      'Failed to save venue layout.';
    const errorCode = typeof apiError?.errorCode === 'string' ? apiError.errorCode : null;
    return { status, errorCode, message, validationErrors, correlationId };
  }

  private isStaleVersionConflict(err: unknown): boolean {
    const parsed = this.extractSaveApiError(err);
    return parsed.status === 409 && parsed.errorCode === 'SF_409_CONFLICT';
  }

  saveLayout(): void {
    if (this.editorState.isSaving()) {
      return;
    }
    this.validationError.set(null);
    this.serverValidationErrors.set([]);
    this.saveCorrelationId.set(null);

    const localError = this.runLocalPreSaveValidation();
    if (localError) {
      this.validationError.set(localError);
      this.announceResult('Save blocked by local validation; draft retained.');
      this.focusSaveValidationSummary();
      return;
    }

    const venueId = this.resolveSaveVenueId();
    if (!venueId) {
      this.validationError.set('Cannot save layout: venueId is required');
      return;
    }

    let request: SaveVenueLayoutRequest;
    try {
      request = this.editorState.buildSaveRequest();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Cannot build save request';
      this.validationError.set(msg);
      this.announceResult('Save blocked; draft and history retained.');
      return;
    }
    const immutableSnapshot: SaveVenueLayoutRequest = JSON.parse(JSON.stringify(request));

    this.editorState.setIsSaving(true);
    this.venueApi.validateLayout(venueId, immutableSnapshot).subscribe({
      next: () => this.executeSavePut(venueId, immutableSnapshot),
      error: (err) => this.handleValidationFailure(err),
    });
  }

  private executeSavePut(venueId: string, snapshot: SaveVenueLayoutRequest): void {
    this.venueApi.saveLayout(venueId, snapshot).subscribe({
      next: (saved) => this.handleSaveSuccess(saved),
      error: (err) => this.handleSaveError(err, snapshot),
    });
  }

  private handleValidationFailure(err: unknown): void {
    const parsed = this.extractSaveApiError(err);
    this.editorState.setIsSaving(false);
    if (parsed.status === 400) {
      this.serverValidationErrors.set(parsed.validationErrors);
      this.saveCorrelationId.set(parsed.correlationId);
      const summary = parsed.correlationId
        ? `${parsed.message} (ref ${parsed.correlationId})`
        : parsed.message;
      this.validationError.set(summary);
      this.announceResult('Server validation failed; draft and history retained.');
      this.focusSaveValidationSummary();
      return;
    }
    this.validationError.set(parsed.message);
    if (parsed.correlationId) {
      this.saveCorrelationId.set(parsed.correlationId);
    }
    this.announceResult('Save failed; draft and history retained.');
    this.snackBar.open(parsed.message, 'Close', {
      duration: 4000,
      panelClass: 'snack-error',
    });
  }

  private handleSaveSuccess(saved: VenueLayout): void {
    try {
      this.editorState.applyServerSnapshot(saved);
    } finally {
      this.editorState.setIsSaving(false);
    }
    this.history.clear();
    this.serverValidationErrors.set([]);
    this.saveCorrelationId.set(null);
    this.validationError.set(null);
    this.pruneSelectionToServerIds(saved);
    this.announceResult(`Venue layout saved successfully at version ${saved.layoutVersion}.`);
    this.snackBar.open(`Venue layout saved successfully! (v${saved.layoutVersion})`, 'Close', {
      duration: 4000,
      panelClass: 'snack-success',
    });
  }

  private handleSaveError(err: unknown, snapshot: SaveVenueLayoutRequest): void {
    if (this.isStaleVersionConflict(err)) {
      const parsed = this.extractSaveApiError(err);
      this.editorState.setIsSaving(false);
      this.saveCorrelationId.set(parsed.correlationId);
      this.openConflictDialog(snapshot, parsed.correlationId);
      this.announceResult('Save conflict; local draft and history retained.');
      return;
    }
    const parsed = this.extractSaveApiError(err);
    this.editorState.setIsSaving(false);
    if (parsed.correlationId) {
      this.saveCorrelationId.set(parsed.correlationId);
    }
    this.validationError.set(parsed.message);
    this.announceResult('Save failed; draft and history retained.');
    this.snackBar.open(parsed.message, 'Close', {
      duration: 4000,
      panelClass: 'snack-error',
    });
  }

  private pruneSelectionToServerIds(saved: VenueLayout): void {
    const valid = new Set<string>();
    for (const sec of saved.sections || []) {
      for (const seat of sec.seats || []) {
        if (seat.seatId) {
          valid.add(seat.seatId);
        }
        valid.add(`${seat.gridY}_${seat.gridX}`);
      }
    }
    this.selectedSeatKeys.update((current) => {
      const next = new Set<string>();
      for (const key of current) {
        if (valid.has(key)) {
          next.add(key);
        }
      }
      return next;
    });
    const selectedElement = this.selectedLayoutElement();
    if (selectedElement?.elementId) {
      const validElementIds = new Set(
        (saved.elements || []).map((el) => el.elementId).filter((id) => id !== null),
      );
      if (!validElementIds.has(selectedElement.elementId)) {
        this.selectedLayoutElement.set(null);
        this.selectedElementIndex.set(null);
      }
    }
  }

  private openConflictDialog(snapshot: SaveVenueLayoutRequest, correlationId: string | null): void {
    const localVersion = (this.editorState.baseline() as VenueLayout | null)?.layoutVersion ?? 0;
    let snapshotJson = '{}';
    try {
      snapshotJson = JSON.stringify(snapshot, null, 2);
    } catch {
      snapshotJson = '{}';
    }
    const ref = this.dialog.open(LayoutConflictDialogComponent, {
      data: { localVersion, correlationId, snapshotJson },
      disableClose: false,
      restoreFocus: true,
      autoFocus: 'dialog',
    });
    ref.afterClosed().subscribe((result: LayoutConflictDialogResult | undefined) => {
      if (result === 'reload') {
        this.reloadServerLayout();
        return;
      }
      this.focusSaveButton();
    });
  }

  private focusSaveButton(): void {
    queueMicrotask(() => {
      try {
        const saveButton = document.getElementById(
          'designerSaveButton',
        ) as HTMLButtonElement | null;
        if (saveButton && !saveButton.disabled) {
          saveButton.focus();
        }
      } catch {
        // Focus restoration is best-effort.
      }
    });
  }

  private focusDesignerHeading(): void {
    queueMicrotask(() => {
      try {
        document.getElementById('designerHeading')?.focus?.();
      } catch {
        // Focus restoration is best-effort.
      }
    });
  }

  reloadServerLayout(): void {
    const venueId = this.resolveSaveVenueId();
    if (!venueId) {
      this.validationError.set('Cannot reload layout: venueId is required');
      return;
    }
    if (
      !window.confirm(
        'Reloading replaces your unsaved draft with the latest server layout. Continue?',
      )
    ) {
      return;
    }
    this.editorState.setIsSaving(true);
    this.venueApi.getEditableLayout(venueId).subscribe({
      next: (serverLayout) => {
        try {
          this.editorState.applyServerSnapshot(serverLayout);
        } finally {
          this.editorState.setIsSaving(false);
        }
        this.history.clear();
        this.selectedSeatKeys.set(new Set());
        this.selectedLayoutElement.set(null);
        this.selectedElementIndex.set(null);
        this.serverValidationErrors.set([]);
        this.saveCorrelationId.set(null);
        this.validationError.set(null);
        const sections = serverLayout.sections || [];
        if (sections.length > 0) {
          const currentKey = this.selectedSectionKey();
          const stillPresent = sections.some(
            (s) => getSectionDraftKey(s as VenueSectionLayout) === currentKey,
          );
          if (!stillPresent) {
            this.selectedSectionKey.set(getSectionDraftKey(sections[0] as VenueSectionLayout));
          }
        }
        this.announceResult(`Server layout reloaded at version ${serverLayout.layoutVersion}.`);
        this.snackBar.open('Server layout reloaded. Local draft replaced.', 'Close', {
          duration: 4000,
        });
        this.focusDesignerHeading();
      },
      error: (err) => {
        this.editorState.setIsSaving(false);
        const parsed = this.extractSaveApiError(err);
        this.validationError.set(parsed.message || 'Failed to reload server layout.');
        this.announceResult('Reload failed; local draft and history retained.');
        this.snackBar.open(parsed.message || 'Failed to reload server layout.', 'Close', {
          duration: 4000,
          panelClass: 'snack-error',
        });
      },
    });
  }

  discardChanges(): void {
    if (this.isMutationLocked()) {
      return;
    }
    this.validationError.set(null);
    this.serverValidationErrors.set([]);
    this.saveCorrelationId.set(null);
    this.editorState.resetToBaseline();
    this.history.clear();
    this.selectedSeatKeys.set(new Set());
    this.selectedLayoutElement.set(null);
    this.selectedElementIndex.set(null);
    this.announceResult('All draft changes discarded.');
    this.snackBar.open('All draft changes discarded. Reset to baseline.', 'Close', {
      duration: 3000,
    });
  }

  // --- Section Operations (Local Draft) ---

  openAddSectionModal(): void {
    this.lastFocusedElement = document.activeElement as HTMLElement | null;
    this.sectionForm.reset({
      name: '',
      rowCount: 10,
      colCount: 15,
      generateSeats: true,
    });
    this.showAddSectionModal.set(true);
    queueMicrotask(() => {
      document.getElementById('newSecName')?.focus();
    });
  }

  closeAddSectionModal(): void {
    this.showAddSectionModal.set(false);
    queueMicrotask(() => {
      this.lastFocusedElement?.focus?.();
      this.lastFocusedElement = null;
    });
  }

  onSnapStepInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    const parsed = Number(input.value);
    this.snapStep.set(Number.isFinite(parsed) && parsed > 0 ? parsed : 0);
  }

  createSection(): void {
    if (this.isMutationLocked()) {
      return;
    }
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

      this.pushHistoryCheckpoint();
      this.editorState.replaceDraft((draft) => {
        draft.sections = [...draft.sections, newSection];
        return draft;
      });

      this.closeAddSectionModal();
      this.selectSection(newSection);
      this.announceResult(`Section ${newSection.name} created.`);
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
    if (this.isMutationLocked()) {
      return;
    }
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

      this.pushHistoryCheckpoint();
      this.editorState.replaceDraft((draft) => {
        draft.sections = [...draft.sections, duplicated];
        return draft;
      });

      this.selectSection(duplicated);
      this.announceResult(`Section duplicated as ${duplicated.name}.`);
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
    if (this.isMutationLocked()) {
      return;
    }
    const sec = this.currentSection();
    if (!sec) return;

    this.validationError.set(null);
    try {
      const targetKey = getSectionDraftKey(sec);
      const deactivated = this.generator.deactivateSection(sec);
      this.pushHistoryCheckpoint();
      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) =>
          getSectionDraftKey(s) === targetKey
            ? { ...deactivated, draftKey: s.draftKey ?? deactivated.draftKey }
            : s,
        );
        return draft;
      });
      this.announceResult(`Section ${sec.name} deactivated.`);
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
    if (this.isMutationLocked()) {
      return;
    }
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
      this.pushHistoryCheckpoint();
      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) =>
          getSectionDraftKey(s) === targetKey
            ? { ...reactivated, draftKey: s.draftKey ?? reactivated.draftKey }
            : s,
        );
        return draft;
      });
      this.announceResult(`Section ${sec.name} reactivated.`);
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
    if (this.isMutationLocked()) {
      return;
    }
    const sec = this.currentSection();
    if (!sec) return;

    this.validationError.set(null);
    try {
      const remaining = this.generator.removeSection(sec, this.sections());
      this.pushHistoryCheckpoint();
      this.editorState.replaceDraft((draft) => {
        draft.sections = remaining;
        return draft;
      });

      if (remaining.length > 0) {
        this.selectSection(remaining[0]);
      } else {
        this.selectSection(null);
      }
      this.announceResult(`Draft section ${sec.name} removed.`);
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
    if (this.isMutationLocked()) {
      return;
    }
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
      this.pushHistoryCheckpoint();
      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) => {
          if (getSectionDraftKey(s) === targetKey) {
            return { ...s, ...safePartial };
          }
          return s;
        });
        return draft;
      });
      this.announceResult('Section properties updated.');
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Invalid section update';
      this.validationError.set(msg);
    }
  }

  // --- Seat Generation & Bulk Operations (Local Draft) ---

  onGenerateSeats(options: GenerateSeatsOptions): void {
    if (this.isMutationLocked()) {
      return;
    }
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
      this.pushHistoryCheckpoint();
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
      this.announceResult(`Generated ${seats.length} seats.`);
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
    if (this.isMutationLocked()) {
      return;
    }
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

      this.pushHistoryCheckpoint();
      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) =>
          getSectionDraftKey(s) === targetKey
            ? { ...updated, draftKey: s.draftKey ?? updated.draftKey }
            : s,
        );
        return draft;
      });

      this.announceResult('Bulk seat activation updated.');
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
    if (this.isMutationLocked()) {
      return;
    }
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

      this.pushHistoryCheckpoint();
      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) =>
          getSectionDraftKey(s) === targetKey
            ? { ...updated, draftKey: s.draftKey ?? updated.draftKey }
            : s,
        );
        return draft;
      });

      this.announceResult('Moved selected seats.');
      this.snackBar.open('Moved selected seats successfully!', 'Close', { duration: 2500 });
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to translate seats';
      this.validationError.set(msg);
      this.snackBar.open(msg, 'Close', { duration: 4000, panelClass: 'snack-error' });
    }
  }

  onBulkSetRowLabel(label: string): void {
    if (this.isMutationLocked()) {
      return;
    }
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

      this.pushHistoryCheckpoint();
      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) =>
          getSectionDraftKey(s) === targetKey
            ? { ...updated, draftKey: s.draftKey ?? updated.draftKey }
            : s,
        );
        return draft;
      });

      this.announceResult(`Row label set to ${label}.`);
      this.snackBar.open(`Row label set to "${label}"!`, 'Close', { duration: 2500 });
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to set row label';
      this.validationError.set(msg);
      this.snackBar.open(msg, 'Close', { duration: 4000, panelClass: 'snack-error' });
    }
  }

  onBulkRenumber(startNumber: number): void {
    if (this.isMutationLocked()) {
      return;
    }
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

      this.pushHistoryCheckpoint();
      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) =>
          getSectionDraftKey(s) === targetKey
            ? { ...updated, draftKey: s.draftKey ?? updated.draftKey }
            : s,
        );
        return draft;
      });

      this.announceResult(`Seats renumbered from ${startNumber}.`);
      this.snackBar.open(`Seats renumbered from #${startNumber}!`, 'Close', { duration: 2500 });
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to renumber seats';
      this.validationError.set(msg);
      this.snackBar.open(msg, 'Close', { duration: 4000, panelClass: 'snack-error' });
    }
  }

  onSeatSelectionChanged(keys: Set<string>): void {
    this.selectedSeatKeys.set(keys);
    this.announceResult(`${keys.size} seats selected.`);
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
  //
  // REV-P11-009-003: local-draft-only. This entry point predates the versioned
  // save flow and must never issue per-seat HTTP writes outside the
  // SaveVenueLayoutRequest/layoutVersion flow. It reuses the same local-draft
  // toggle semantics as onCanvasSeatToggle; the explicit versioned save()
  // persists everything. Zero HTTP by contract (covered by spec).
  toggleSeat(seat: VenueSectionSeat): void {
    if (this.isMutationLocked()) {
      return;
    }
    const sec = this.currentSection();
    if (!sec || !seat.seatId) return;
    const targetKey = getSectionDraftKey(sec);
    const targetSeat = (sec.seats || []).find((s) => s.seatId === seat.seatId);
    if (!targetSeat) return;
    const seatId = seat.seatId;
    const newState = !targetSeat.isActive;

    if (newState) {
      const cap = this.venue()?.capacity ?? 0;
      if (cap > 0 && this.totalConfiguredActiveSeats() + 1 > cap) {
        const msg = `Cannot activate seat: venue capacity limit (${cap}) reached`;
        this.validationError.set(msg);
        this.snackBar.open(msg, 'Close', { duration: 4000, panelClass: 'snack-error' });
        return;
      }
    }

    this.pushHistoryCheckpoint();
    try {
      this.editorState.replaceDraft((draft) => {
        draft.sections = draft.sections.map((s) => {
          if (getSectionDraftKey(s) !== targetKey) {
            return s;
          }
          return {
            ...s,
            seats: (s.seats || []).map((st) =>
              st.seatId === seatId ? { ...st, isActive: newState } : st,
            ),
          };
        });
        return draft;
      });
      this.validationError.set(null);
      this.announceResult('Seat toggled.');
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to toggle seat';
      this.validationError.set(msg);
    }
  }
}
