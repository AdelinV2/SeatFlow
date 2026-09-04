import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnDestroy,
  computed,
  effect,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  VenueLayoutElement,
  VenueSectionLayout,
  VenueSectionSeat,
} from '../../../../models/venue.model';
import {
  CornerHandle,
  DEFAULT_LAYOUT_BOUNDS,
  MAX_POSITION,
  MIN_DIMENSION,
  MIN_POSITION,
  Point,
  SectionTransform,
  SortedCanvasItem,
  calculateCornerResize,
  calculateRotation,
  clampNumber,
  clampZoom,
  clientDeltaToWorld,
  clientPointToWorld,
  layoutBounds,
  MAX_ZOOM,
  MIN_ZOOM,
  snap,
  sortedLayoutItems,
} from '../../../utils/layout-geometry';
import { SectionNodeComponent } from '../section-node/section-node.component';

export interface SectionTransformChangeEvent {
  sectionId: string | null;
  /** Stable client-side draft key (REV-002); falls back to sectionId when absent. */
  draftKey?: string | null;
  positionX: number;
  positionY: number;
  width: number;
  height: number;
  rotationDeg: number;
  zIndex?: number;
}

export interface CanvasSeatSelectedEvent {
  seat: VenueSectionSeat;
  section: VenueSectionLayout;
  /** True when Ctrl/Cmd was held; designer preserves/toggles multi-selection. */
  additive: boolean;
}

/** Stable canvas identity: draftKey wins so multiple null-ID drafts stay distinct. */
export function getCanvasSectionKey(section: VenueSectionLayout): string {
  const draftKey = (section as VenueSectionLayout).draftKey;
  if (draftKey) {
    return draftKey;
  }
  return section.sectionId ?? '';
}

function isModifierPressed(event: MouseEvent | KeyboardEvent): boolean {
  const anyEvent = event as MouseEvent;
  return Boolean(anyEvent.ctrlKey || anyEvent.metaKey);
}

type InteractionMode =
  'none' | 'pan' | 'pinch' | 'drag-section' | 'resize-section' | 'rotate-section';

@Component({
  selector: 'app-layout-canvas',
  standalone: true,
  imports: [CommonModule, SectionNodeComponent],
  templateUrl: './layout-canvas.component.html',
  styleUrl: './layout-canvas.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LayoutCanvasComponent implements AfterViewInit, OnDestroy {
  readonly svgContainer = viewChild<ElementRef<HTMLDivElement>>('svgContainer');
  readonly svgViewport = viewChild<ElementRef<SVGSVGElement>>('svgViewport');

  // Inputs
  readonly sections = input<VenueSectionLayout[]>([]);
  readonly elements = input<VenueLayoutElement[]>([]);
  readonly selectedSectionIds = input<Set<string> | string[]>(new Set());
  readonly editable = input<boolean>(true);
  readonly snapStep = input<number>(0);
  readonly toolMode = input<'select' | 'toggle' | 'paint'>('select');
  readonly paintColor = input<string>('');
  readonly selectedSeatKeys = input<ReadonlySet<string>>(new Set<string>());

  // Outputs
  readonly sectionTransformChanged = output<SectionTransformChangeEvent>();
  readonly selectionChanged = output<Set<string>>();
  readonly seatSelected = output<CanvasSeatSelectedEvent>();
  readonly seatToggle = output<{ seat: VenueSectionSeat; section: VenueSectionLayout }>();
  readonly seatPaint = output<{
    seat: VenueSectionSeat;
    section: VenueSectionLayout;
    color: string;
  }>();
  readonly rowClick = output<{
    event: MouseEvent;
    rowLabel: string;
    section: VenueSectionLayout;
  }>();
  readonly rowDblClick = output<{
    event: MouseEvent;
    rowLabel: string;
    section: VenueSectionLayout;
  }>();
  readonly colClick = output<{
    event: MouseEvent;
    colIndex: number;
    section: VenueSectionLayout;
  }>();
  readonly colDblClick = output<{
    event: MouseEvent;
    colIndex: number;
    section: VenueSectionLayout;
  }>();
  readonly toolModeChange = output<'select' | 'toggle' | 'paint'>();

  // Signals
  readonly zoomLevel = signal<number>(1.0);
  readonly panX = signal<number>(0);
  readonly panY = signal<number>(0);
  readonly isDragging = signal<boolean>(false);

  // Aliases for convenience/spec compliance
  readonly zoom = this.zoomLevel;
  readonly dragging = this.isDragging;

  // Normalized selection set
  readonly selectedIdSet = computed<Set<string>>(() => {
    const raw = this.selectedSectionIds();
    if (raw instanceof Set) {
      return raw;
    }
    return new Set(raw ?? []);
  });

  // Rendering items ordered by (zIndex, stable tie-break)
  readonly sortedItems = computed<SortedCanvasItem[]>(() => {
    return sortedLayoutItems(this.sections(), this.elements());
  });

  // Signal for internal mode tracking (instantaneous local response)
  readonly internalToolMode = signal<'select' | 'toggle' | 'paint'>('select');

  // Computed active tool mode
  readonly currentMode = computed<'select' | 'toggle' | 'paint'>(() => {
    return this.internalToolMode();
  });

  constructor() {
    effect(() => {
      const mode = this.toolMode();
      this.internalToolMode.set(mode);
    });
  }

  // World transform string for the root content group
  readonly viewportTransform = computed(() => {
    return `translate(${this.panX()} ${this.panY()}) scale(${this.zoomLevel()})`;
  });

  // Private interaction state
  private mode: InteractionMode = 'none';
  private readonly activePointers = new Map<number, Point>();
  private dragStartClient: Point = { x: 0, y: 0 };
  private lastDragClient: Point = { x: 0, y: 0 };
  private totalDragDistance = 0;
  private activeSection: VenueSectionLayout | null = null;
  private initialTransform: SectionTransform | null = null;
  private activeHandle: CornerHandle | 'rotate' | null = null;

  // Pinch zoom state
  private pinchStartDistance = 0;
  private pinchStartZoom = 1;
  private pinchStartMidpoint: Point = { x: 0, y: 0 };
  private pinchStartPan: Point = { x: 0, y: 0 };

  private capturedElement: Element | null = null;
  private capturedPointerId: number | null = null;

  private readonly wheelHandler = (event: WheelEvent): void => {
    this.onWheel(event);
  };

  ngAfterViewInit(): void {
    const viewport = this.svgViewport()?.nativeElement;
    if (viewport) {
      viewport.addEventListener('wheel', this.wheelHandler, { passive: false });
    }
  }

  ngOnDestroy(): void {
    const viewport = this.svgViewport()?.nativeElement;
    if (viewport) {
      viewport.removeEventListener('wheel', this.wheelHandler);
    }
    this.clearInteractionState();
  }

  // --- Zoom & Pan API ---

  setZoom(newZoom: number, anchorClientPoint?: Point): void {
    const clamped = !Number.isFinite(newZoom) || newZoom <= 0 ? 1.0 : clampZoom(newZoom);
    if (anchorClientPoint) {
      const containerRect = this.getContainerRect();
      const cursorX = anchorClientPoint.x - containerRect.left;
      const cursorY = anchorClientPoint.y - containerRect.top;

      const worldX = (cursorX - this.panX()) / this.zoomLevel();
      const worldY = (cursorY - this.panY()) / this.zoomLevel();

      const newPanX = cursorX - worldX * clamped;
      const newPanY = cursorY - worldY * clamped;

      this.zoomLevel.set(clamped);
      this.panX.set(Number(newPanX.toFixed(2)));
      this.panY.set(Number(newPanY.toFixed(2)));
    } else {
      this.zoomLevel.set(clamped);
    }
  }

  zoomIn(): void {
    const rect = this.getContainerRect();
    const center = { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
    const nextZoom = Math.min(MAX_ZOOM, Number((this.zoomLevel() + 0.25).toFixed(2)));
    this.setZoom(nextZoom, center);
  }

  zoomOut(): void {
    const rect = this.getContainerRect();
    const center = { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
    const nextZoom = Math.max(MIN_ZOOM, Number((this.zoomLevel() - 0.25).toFixed(2)));
    this.setZoom(nextZoom, center);
  }

  resetView(): void {
    this.zoomLevel.set(1.0);
    this.panX.set(0);
    this.panY.set(0);
  }

  fitToLayout(): void {
    const bounds = layoutBounds(this.sections(), this.elements());
    const rect = this.getContainerRect();
    const viewW = rect.width > 0 ? rect.width : 1000;
    const viewH = rect.height > 0 ? rect.height : 800;

    if (
      bounds.width === DEFAULT_LAYOUT_BOUNDS.width &&
      bounds.height === DEFAULT_LAYOUT_BOUNDS.height &&
      this.sections().length === 0 &&
      this.elements().length === 0
    ) {
      this.resetView();
      return;
    }

    const padding = 60;
    const availableW = Math.max(100, viewW - padding * 2);
    const availableH = Math.max(100, viewH - padding * 2);

    const scaleX = availableW / bounds.width;
    const scaleY = availableH / bounds.height;
    const fitZoom = clampZoom(Math.min(scaleX, scaleY));

    const centerX = bounds.minX + bounds.width / 2;
    const centerY = bounds.minY + bounds.height / 2;

    const newPanX = viewW / 2 - centerX * fitZoom;
    const newPanY = viewH / 2 - centerY * fitZoom;

    this.zoomLevel.set(fitZoom);
    this.panX.set(Number(newPanX.toFixed(2)));
    this.panY.set(Number(newPanY.toFixed(2)));
  }

  // --- Wheel Event ---

  onWheel(event: WheelEvent): void {
    event.preventDefault();
    const delta = event.deltaY < 0 ? 0.15 : -0.15;
    this.setZoom(this.zoomLevel() + delta, { x: event.clientX, y: event.clientY });
  }

  // --- Pointer Handlers on SVG Canvas ---

  onCanvasPointerDown(event: PointerEvent): void {
    // Only primary button initiates canvas pan or selection
    if (event.button !== 0) {
      return;
    }

    // Ignore if clicked on a section node or handle directly
    const target = event.target as Element;
    if (target.closest('.section-node') || target.closest('.transform-handle')) {
      return;
    }

    const viewport = this.svgViewport()?.nativeElement;
    if (viewport) {
      viewport.setPointerCapture(event.pointerId);
      this.capturedElement = viewport;
      this.capturedPointerId = event.pointerId;
    }

    this.activePointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
    this.totalDragDistance = 0;

    if (this.activePointers.size === 1) {
      this.mode = 'pan';
      this.isDragging.set(true);
      this.dragStartClient = { x: event.clientX, y: event.clientY };
      this.lastDragClient = { x: event.clientX, y: event.clientY };
    } else if (this.activePointers.size === 2) {
      this.beginPinch();
    }
  }

  onCanvasPointerMove(event: PointerEvent): void {
    if (!this.activePointers.has(event.pointerId)) {
      return;
    }

    this.activePointers.set(event.pointerId, { x: event.clientX, y: event.clientY });

    // Multi-touch pinch zoom
    if (this.activePointers.size >= 2) {
      this.handlePinchMove();
      return;
    }

    const dx = event.clientX - this.lastDragClient.x;
    const dy = event.clientY - this.lastDragClient.y;
    this.totalDragDistance += Math.hypot(dx, dy);
    this.lastDragClient = { x: event.clientX, y: event.clientY };

    switch (this.mode) {
      case 'pan': {
        this.panX.update((v) => Number((v + dx).toFixed(2)));
        this.panY.update((v) => Number((v + dy).toFixed(2)));
        break;
      }
      case 'drag-section': {
        if (!this.editable() || !this.activeSection || !this.initialTransform) {
          return;
        }
        const clientDelta = {
          x: event.clientX - this.dragStartClient.x,
          y: event.clientY - this.dragStartClient.y,
        };
        const worldDelta = clientDeltaToWorld(clientDelta, this.zoomLevel());

        let newX = this.initialTransform.positionX + worldDelta.x;
        let newY = this.initialTransform.positionY + worldDelta.y;

        const step = this.snapStep();
        if (step > 0) {
          newX = snap(newX, step);
          newY = snap(newY, step);
        }

        newX = clampNumber(Number(newX.toFixed(3)), MIN_POSITION, MAX_POSITION);
        newY = clampNumber(Number(newY.toFixed(3)), MIN_POSITION, MAX_POSITION);

        this.sectionTransformChanged.emit({
          sectionId: this.activeSection.sectionId,
          draftKey: getCanvasSectionKey(this.activeSection),
          positionX: newX,
          positionY: newY,
          width: this.initialTransform.width,
          height: this.initialTransform.height,
          rotationDeg: this.initialTransform.rotationDeg,
          zIndex: this.initialTransform.zIndex,
        });
        break;
      }
      case 'resize-section': {
        if (
          !this.editable() ||
          !this.activeSection ||
          !this.initialTransform ||
          !this.activeHandle ||
          this.activeHandle === 'rotate'
        ) {
          return;
        }
        const clientDelta = {
          x: event.clientX - this.dragStartClient.x,
          y: event.clientY - this.dragStartClient.y,
        };
        const worldDelta = clientDeltaToWorld(clientDelta, this.zoomLevel());

        const resize = calculateCornerResize(
          this.initialTransform,
          this.activeHandle as CornerHandle,
          worldDelta,
          this.snapStep(),
          MIN_DIMENSION,
        );

        this.sectionTransformChanged.emit({
          sectionId: this.activeSection.sectionId,
          draftKey: getCanvasSectionKey(this.activeSection),
          positionX: resize.positionX,
          positionY: resize.positionY,
          width: resize.width,
          height: resize.height,
          rotationDeg: this.initialTransform.rotationDeg,
          zIndex: this.initialTransform.zIndex,
        });
        break;
      }
      case 'rotate-section': {
        if (!this.editable() || !this.activeSection || !this.initialTransform) {
          return;
        }
        const containerRect = this.getContainerRect();
        const currentWorld = clientPointToWorld(
          { x: event.clientX, y: event.clientY },
          containerRect,
          { x: this.panX(), y: this.panY() },
          this.zoomLevel(),
        );

        const newRot = calculateRotation(this.initialTransform, currentWorld, this.snapStep());

        this.sectionTransformChanged.emit({
          sectionId: this.activeSection.sectionId,
          draftKey: getCanvasSectionKey(this.activeSection),
          positionX: this.initialTransform.positionX,
          positionY: this.initialTransform.positionY,
          width: this.initialTransform.width,
          height: this.initialTransform.height,
          rotationDeg: newRot,
          zIndex: this.initialTransform.zIndex,
        });
        break;
      }
    }
  }

  onCanvasPointerUp(event: PointerEvent): void {
    const wasPan = this.mode === 'pan';
    const distance = this.totalDragDistance;

    this.releasePointer(event.pointerId);
    this.activePointers.delete(event.pointerId);

    // Background primary click clears selection
    if (wasPan && distance < 4) {
      this.selectionChanged.emit(new Set());
    }

    if (this.activePointers.size === 0) {
      this.clearInteractionState();
    }
  }

  onCanvasPointerCancel(event: PointerEvent): void {
    this.releasePointer(event.pointerId);
    this.clearInteractionState();
  }

  onCanvasLostPointerCapture(event: PointerEvent): void {
    this.clearInteractionState();
  }

  // --- Section Event Handlers from Child SectionNodes ---

  onSectionClick(event: { event: MouseEvent; section: VenueSectionLayout }): void {
    if (!this.editable() && !event.section.isActive) {
      return;
    }

    const secId = getCanvasSectionKey(event.section);
    const isMultiSelect = event.event.ctrlKey || event.event.metaKey;

    if (isMultiSelect) {
      const updated = new Set(this.selectedIdSet());
      if (updated.has(secId)) {
        updated.delete(secId);
      } else {
        updated.add(secId);
      }
      this.selectionChanged.emit(updated);
    } else {
      this.selectionChanged.emit(new Set([secId]));
    }
  }

  onSectionPointerDown(event: { event: PointerEvent; section: VenueSectionLayout }): void {
    if (!this.editable()) {
      return;
    }

    const secId = getCanvasSectionKey(event.section);
    if (!this.selectedIdSet().has(secId) && !event.event.ctrlKey && !event.event.metaKey) {
      this.selectionChanged.emit(new Set([secId]));
    }

    const viewport = this.svgViewport()?.nativeElement;
    if (viewport) {
      viewport.setPointerCapture(event.event.pointerId);
      this.capturedElement = viewport;
      this.capturedPointerId = event.event.pointerId;
    }

    this.mode = 'drag-section';
    this.activeSection = event.section;
    this.initialTransform = {
      positionX: event.section.positionX,
      positionY: event.section.positionY,
      width: event.section.width,
      height: event.section.height,
      rotationDeg: event.section.rotationDeg,
      zIndex: event.section.zIndex,
    };
    this.dragStartClient = { x: event.event.clientX, y: event.event.clientY };
    this.lastDragClient = { x: event.event.clientX, y: event.event.clientY };
    this.totalDragDistance = 0;
    this.isDragging.set(true);
    this.activePointers.set(event.event.pointerId, {
      x: event.event.clientX,
      y: event.event.clientY,
    });
  }

  onHandlePointerDown(event: {
    event: PointerEvent;
    section: VenueSectionLayout;
    handle: CornerHandle | 'rotate';
  }): void {
    if (!this.editable()) {
      return;
    }

    const viewport = this.svgViewport()?.nativeElement;
    if (viewport) {
      viewport.setPointerCapture(event.event.pointerId);
      this.capturedElement = viewport;
      this.capturedPointerId = event.event.pointerId;
    }

    this.activeSection = event.section;
    this.activeHandle = event.handle;
    this.initialTransform = {
      positionX: event.section.positionX,
      positionY: event.section.positionY,
      width: event.section.width,
      height: event.section.height,
      rotationDeg: event.section.rotationDeg,
      zIndex: event.section.zIndex,
    };
    this.dragStartClient = { x: event.event.clientX, y: event.event.clientY };
    this.lastDragClient = { x: event.event.clientX, y: event.event.clientY };
    this.totalDragDistance = 0;
    this.isDragging.set(true);
    this.mode = event.handle === 'rotate' ? 'rotate-section' : 'resize-section';
    this.activePointers.set(event.event.pointerId, {
      x: event.event.clientX,
      y: event.event.clientY,
    });
  }

  onSeatClick(event: {
    event: MouseEvent | KeyboardEvent;
    seat: VenueSectionSeat;
    section: VenueSectionLayout;
  }): void {
    if (!this.editable() && (!event.section.isActive || !event.seat.isActive)) {
      return;
    }
    this.seatSelected.emit({
      seat: event.seat,
      section: event.section,
      additive: isModifierPressed(event.event),
    });
  }

  setToolMode(mode: 'select' | 'toggle' | 'paint'): void {
    this.internalToolMode.set(mode);
    this.toolModeChange.emit(mode);
  }

  onSeatToggle(event: { seat: VenueSectionSeat; section: VenueSectionLayout }): void {
    this.seatToggle.emit(event);
  }

  onSeatPaint(event: { seat: VenueSectionSeat; section: VenueSectionLayout; color: string }): void {
    this.seatPaint.emit(event);
  }

  onRowClick(event: { event: MouseEvent; rowLabel: string; section: VenueSectionLayout }): void {
    this.rowClick.emit(event);
  }

  onRowDblClick(event: { event: MouseEvent; rowLabel: string; section: VenueSectionLayout }): void {
    this.rowDblClick.emit(event);
  }

  onColClick(event: { event: MouseEvent; colIndex: number; section: VenueSectionLayout }): void {
    this.colClick.emit(event);
  }

  onColDblClick(event: { event: MouseEvent; colIndex: number; section: VenueSectionLayout }): void {
    this.colDblClick.emit(event);
  }

  // --- Pinch-to-zoom helpers ---

  private beginPinch(): void {
    const [first, second] = [...this.activePointers.values()];
    if (!first || !second) {
      return;
    }
    this.mode = 'pinch';
    this.pinchStartDistance = Math.hypot(second.x - first.x, second.y - first.y);
    this.pinchStartZoom = this.zoomLevel();
    this.pinchStartMidpoint = {
      x: (first.x + second.x) / 2,
      y: (first.y + second.y) / 2,
    };
    this.pinchStartPan = { x: this.panX(), y: this.panY() };
  }

  private handlePinchMove(): void {
    const [first, second] = [...this.activePointers.values()];
    if (!first || !second || this.pinchStartDistance <= 0) {
      return;
    }
    const currentDistance = Math.hypot(second.x - first.x, second.y - first.y);
    const scale = currentDistance / this.pinchStartDistance;
    const newZoom = clampZoom(this.pinchStartZoom * scale);

    const currentMidpoint = {
      x: (first.x + second.x) / 2,
      y: (first.y + second.y) / 2,
    };

    const containerRect = this.getContainerRect();
    const cursorX = currentMidpoint.x - containerRect.left;
    const cursorY = currentMidpoint.y - containerRect.top;

    const worldMidX = (cursorX - this.pinchStartPan.x) / this.pinchStartZoom;
    const worldMidY = (cursorY - this.pinchStartPan.y) / this.pinchStartZoom;

    const newPanX = cursorX - worldMidX * newZoom;
    const newPanY = cursorY - worldMidY * newZoom;

    this.zoomLevel.set(newZoom);
    this.panX.set(Number(newPanX.toFixed(2)));
    this.panY.set(Number(newPanY.toFixed(2)));
  }

  private releasePointer(pointerId: number): void {
    if (this.capturedElement && this.capturedPointerId === pointerId) {
      try {
        this.capturedElement.releasePointerCapture(pointerId);
      } catch {
        // Safe ignore
      }
      this.capturedElement = null;
      this.capturedPointerId = null;
    }
  }

  private clearInteractionState(): void {
    if (this.capturedElement && this.capturedPointerId !== null) {
      try {
        this.capturedElement.releasePointerCapture(this.capturedPointerId);
      } catch {
        // Safe ignore
      }
    }
    this.mode = 'none';
    this.isDragging.set(false);
    this.activePointers.clear();
    this.activeSection = null;
    this.initialTransform = null;
    this.activeHandle = null;
    this.capturedElement = null;
    this.capturedPointerId = null;
    this.pinchStartDistance = 0;
  }

  private getContainerRect(): { left: number; top: number; width: number; height: number } {
    const el = this.svgViewport()?.nativeElement;
    if (el) {
      const rect = el.getBoundingClientRect();
      return {
        left: rect.left,
        top: rect.top,
        width: rect.width,
        height: rect.height,
      };
    }
    return { left: 0, top: 0, width: 1000, height: 800 };
  }

  getElementTransform(elem: VenueLayoutElement): string {
    const g = elem.geometry;
    const cx = g.width / 2;
    const cy = g.height / 2;
    return `translate(${g.x} ${g.y}) rotate(${g.rotationDeg} ${cx} ${cy})`;
  }
}
