import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnDestroy,
  computed,
  effect,
  input,
  linkedSignal,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  LayoutGeometry,
  VenueLayoutElement,
  VenueSectionLayout,
  VenueSectionSeat,
} from '../../../../models/venue.model';
import {
  CornerHandle,
  DEFAULT_LAYOUT_BOUNDS,
  MAX_DIMENSION,
  MAX_POSITION,
  MAX_ROTATION,
  MAX_Z_INDEX,
  MAX_ZOOM,
  MIN_DIMENSION,
  MIN_POSITION,
  MIN_ROTATION,
  MIN_Z_INDEX,
  MIN_ZOOM,
  Point,
  SectionTransform,
  SortedCanvasItem,
  calculateCornerResize,
  calculateRotation,
  clampDimension,
  clampNumber,
  clampZoom,
  clientDeltaToWorld,
  clientPointToWorld,
  layoutBounds,
  normalizeRotation,
  snap,
  sortedLayoutItems,
} from '../../../utils/layout-geometry';
import {
  CustomerSeatPresentation,
  SectionNodeComponent,
} from '../section-node/section-node.component';

export type { CustomerSeatPresentation };
import { LayoutElementNodeComponent } from '../layout-element-node/layout-element-node.component';
import {
  isValidLayoutElementType,
  LayoutElementPaletteComponent,
} from '../layout-element-palette/layout-element-palette.component';

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
  | 'none'
  | 'pan'
  | 'pinch'
  | 'drag-section'
  | 'resize-section'
  | 'rotate-section'
  | 'drag-element'
  | 'resize-element'
  | 'rotate-element';

@Component({
  selector: 'app-layout-canvas',
  standalone: true,
  imports: [
    CommonModule,
    SectionNodeComponent,
    LayoutElementNodeComponent,
    LayoutElementPaletteComponent,
  ],
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
  /**
   * Customer presentation overrides per stable seat key (TASK-P11-012).
   * Null preserves pure editor rendering. Read-only consumers pass booking
   * statuses, tier colors, labels, dimming, and conflict flags.
   */
  readonly customerSeatStates = input<ReadonlyMap<string, CustomerSeatPresentation> | null>(null);
  /**
   * Roving-tabindex anchor for customer keyboard navigation. Null preserves
   * the legacy behavior where every interactive seat is tabbable.
   */
  readonly rovingActiveSeatKey = input<string | null>(null);
  /**
   * One-shot auto-fit for read-only consumers (TASK-P11-012 FIX A).
   * When true, the canvas fits sections AND layout elements into the viewport
   * on the first arrival of non-empty data. Manual pan/zoom afterwards is
   * preserved: the fit runs at most once per component instance (plus explicit
   * Fit-button calls), never on status/selection updates. Editor canvases keep
   * the default false so draft edits never yank the designer's viewport.
   * No animation is used (reduced-motion safe: transform is set directly).
   */
  readonly autoFitOnLoad = input<boolean>(false);

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

  // Element Outputs
  readonly elementsChange = output<VenueLayoutElement[]>();
  readonly elementSelected = output<VenueLayoutElement | null>();
  readonly elementTransformChanged = output<{
    element: VenueLayoutElement;
    index: number;
  }>();
  readonly elementUpdated = output<VenueLayoutElement>();
  readonly elementRemoved = output<VenueLayoutElement>();
  readonly elementDuplicated = output<VenueLayoutElement>();

  // Signals
  readonly zoomLevel = signal<number>(1.0);
  readonly panX = signal<number>(0);
  readonly panY = signal<number>(0);
  readonly isDragging = signal<boolean>(false);

  // Internal draft elements linked to input elements
  readonly internalElements = linkedSignal<VenueLayoutElement[]>(() => {
    return [...(this.elements() ?? [])];
  });
  readonly selectedElementKey = signal<string | null>(null);
  readonly elementValidationError = signal<string | null>(null);

  // Aliases for convenience/spec compliance
  readonly zoom = this.zoomLevel;
  readonly dragging = this.isDragging;

  // Normalized selection set for sections
  readonly selectedIdSet = computed<Set<string>>(() => {
    const raw = this.selectedSectionIds();
    if (raw instanceof Set) {
      return raw;
    }
    return new Set(raw ?? []);
  });

  // Selected element computed
  readonly selectedElement = computed<VenueLayoutElement | null>(() => {
    const key = this.selectedElementKey();
    if (!key) {
      return null;
    }
    const list = this.internalElements();
    for (let i = 0; i < list.length; i++) {
      if (this.getElementKey(list[i], i) === key) {
        return list[i];
      }
    }
    return null;
  });

  // Rendering items ordered by (zIndex, stable tie-break)
  readonly sortedItems = computed<SortedCanvasItem[]>(() => {
    return sortedLayoutItems(this.sections(), this.internalElements());
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
    // One-shot read-only auto-fit: runs when autoFitOnLoad is enabled and the
    // first non-empty sections/elements payload arrives. The done-flag guards
    // against re-fitting on every status update or selection change, so manual
    // touch/mouse pan/zoom afterwards is never overridden. Signal writes are
    // natively allowed in effects (Angular 22); the fit sets zoom/pan directly
    // with no animation (reduced-motion safe). tryAutoFit consumes the one-shot
    // only against a real measured rect, so data arriving before first layout
    // (zero-size rect) retries instead of locking in the fallback geometry.
    effect(() => {
      if (!this.autoFitOnLoad() || this.autoFitDone) {
        return;
      }
      const hasContent = this.sections().length > 0 || this.internalElements().length > 0;
      if (!hasContent) {
        return;
      }
      this.tryAutoFit();
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
  private activeLayoutElement: VenueLayoutElement | null = null;
  private initialElementGeometry: LayoutGeometry | null = null;
  /**
   * Index of the element under gesture in internalElements. Gestures must track
   * by index, not object identity: the designer feedback loop replaces the list
   * with draft clones on every emitted change, so a captured reference detaches
   * after the first pointermove and the drag would freeze.
   */
  private activeElementIndex: number | null = null;
  private activeHandle: CornerHandle | 'rotate' | null = null;

  // Pinch zoom state
  private pinchStartDistance = 0;
  private pinchStartZoom = 1;
  private pinchStartMidpoint: Point = { x: 0, y: 0 };
  private pinchStartPan: Point = { x: 0, y: 0 };

  private capturedElement: Element | null = null;
  private capturedPointerId: number | null = null;
  /**
   * One-shot guard for autoFitOnLoad. Set on first fit so later input
   * emissions (live seat statuses, selection, legend highlight) never re-fit.
   */
  private autoFitDone = false;

  private readonly wheelHandler = (event: WheelEvent): void => {
    this.onWheel(event);
  };

  ngAfterViewInit(): void {
    const viewport = this.svgViewport()?.nativeElement;
    if (viewport) {
      viewport.addEventListener('wheel', this.wheelHandler, { passive: false });
    }
    // Backstop for data that arrived before the viewport was measurable: the
    // constructor effect skips zero-size rects without consuming the one-shot,
    // so this retries the fit once the viewport can actually be measured.
    if (this.autoFitOnLoad() && !this.autoFitDone) {
      const hasContent = this.sections().length > 0 || this.internalElements().length > 0;
      if (hasContent) {
        this.tryAutoFit();
      }
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
    const bounds = layoutBounds(this.sections(), this.internalElements());
    const rect = this.getContainerRect();
    const viewW = rect.width > 0 ? rect.width : 1000;
    const viewH = rect.height > 0 ? rect.height : 800;

    if (
      bounds.width === DEFAULT_LAYOUT_BOUNDS.width &&
      bounds.height === DEFAULT_LAYOUT_BOUNDS.height &&
      this.sections().length === 0 &&
      this.internalElements().length === 0
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
    if (event.button !== 0) {
      return;
    }

    const target = event.target as Element;
    if (
      target.closest('.section-node') ||
      target.closest('.layout-element-node') ||
      target.closest('.transform-handle')
    ) {
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
      case 'drag-element': {
        const target = this.activeGestureElement();
        if (!target) {
          return;
        }
        const clientDelta = {
          x: event.clientX - this.dragStartClient.x,
          y: event.clientY - this.dragStartClient.y,
        };
        const worldDelta = clientDeltaToWorld(clientDelta, this.zoomLevel());

        let newX = target.initial.x + worldDelta.x;
        let newY = target.initial.y + worldDelta.y;

        const step = this.snapStep();
        if (step > 0) {
          newX = snap(newX, step);
          newY = snap(newY, step);
        }

        newX = clampNumber(Number(newX.toFixed(3)), MIN_POSITION, MAX_POSITION);
        newY = clampNumber(Number(newY.toFixed(3)), MIN_POSITION, MAX_POSITION);

        const updated: VenueLayoutElement = {
          ...target.element,
          geometry: {
            ...target.element.geometry,
            x: newX,
            y: newY,
          },
        };

        this.activeLayoutElement = updated;
        this.internalElements.update((list) =>
          list.map((el, i) => (i === target.index ? updated : el)),
        );
        this.elementTransformChanged.emit({ element: updated, index: target.index });
        this.elementsChange.emit(this.internalElements());
        break;
      }
      case 'resize-element': {
        const target = this.activeGestureElement();
        if (!target || !this.activeHandle || this.activeHandle === 'rotate') {
          return;
        }
        const clientDelta = {
          x: event.clientX - this.dragStartClient.x,
          y: event.clientY - this.dragStartClient.y,
        };
        const worldDelta = clientDeltaToWorld(clientDelta, this.zoomLevel());

        const resize = calculateCornerResize(
          {
            positionX: target.initial.x,
            positionY: target.initial.y,
            width: target.initial.width,
            height: target.initial.height,
            rotationDeg: target.initial.rotationDeg,
          },
          this.activeHandle as CornerHandle,
          worldDelta,
          this.snapStep(),
          MIN_DIMENSION,
        );

        const targetEl = target.element;
        const updated: VenueLayoutElement = {
          ...targetEl,
          geometry: {
            ...targetEl.geometry,
            x: resize.positionX,
            y: resize.positionY,
            width: resize.width,
            height: resize.height,
          },
        };

        this.activeLayoutElement = updated;
        this.internalElements.update((list) =>
          list.map((el, i) => (i === target.index ? updated : el)),
        );
        this.elementTransformChanged.emit({ element: updated, index: target.index });
        this.elementsChange.emit(this.internalElements());
        break;
      }
      case 'rotate-element': {
        const target = this.activeGestureElement();
        if (!target) {
          return;
        }
        const containerRect = this.getContainerRect();
        const currentWorld = clientPointToWorld(
          { x: event.clientX, y: event.clientY },
          containerRect,
          { x: this.panX(), y: this.panY() },
          this.zoomLevel(),
        );

        const newRot = calculateRotation(
          {
            positionX: target.initial.x,
            positionY: target.initial.y,
            width: target.initial.width,
            height: target.initial.height,
            rotationDeg: target.initial.rotationDeg,
          },
          currentWorld,
          this.snapStep(),
        );

        const targetEl = target.element;
        const updated: VenueLayoutElement = {
          ...targetEl,
          geometry: {
            ...targetEl.geometry,
            rotationDeg: newRot,
          },
        };

        this.activeLayoutElement = updated;
        this.internalElements.update((list) =>
          list.map((el, i) => (i === target.index ? updated : el)),
        );
        this.elementTransformChanged.emit({ element: updated, index: target.index });
        this.elementsChange.emit(this.internalElements());
        break;
      }
    }
  }

  onCanvasPointerUp(event: PointerEvent): void {
    const wasPan = this.mode === 'pan';
    const distance = this.totalDragDistance;

    this.releasePointer(event.pointerId);
    this.activePointers.delete(event.pointerId);

    // Background primary click clears section selection & element selection
    if (wasPan && distance < 4) {
      this.selectionChanged.emit(new Set());
      this.deselectElement();
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

    this.deselectElement();

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

    this.deselectElement();

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

    this.deselectElement();

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

  // --- Element Event Handlers from Child LayoutElementNodes ---

  /**
   * Resolves the element under the active element gesture by list index.
   * The live list member (not the captured pointerdown reference) is returned
   * so gestures survive parent feedback round-trips that replace list items
   * with draft clones. Returns null when the gesture has no valid target.
   */
  private activeGestureElement(): {
    element: VenueLayoutElement;
    index: number;
    initial: LayoutGeometry;
  } | null {
    const idx = this.activeElementIndex;
    const initial = this.initialElementGeometry;
    if (!this.editable() || !this.activeLayoutElement || !initial) {
      return null;
    }
    if (idx === null || idx < 0 || idx >= this.internalElements().length) {
      return null;
    }
    return { element: this.internalElements()[idx], index: idx, initial };
  }

  getElementKey(element: VenueLayoutElement, index?: number): string {
    if (element.elementId) {
      return `elem-${element.elementId}`;
    }
    const idx = index ?? this.internalElements().indexOf(element);
    return `elem-idx-${idx >= 0 ? idx : 0}`;
  }

  isElementSelected(element: VenueLayoutElement, index?: number): boolean {
    const key = this.selectedElementKey();
    if (!key) {
      return false;
    }
    return this.getElementKey(element, index) === key;
  }

  selectElement(element: VenueLayoutElement | null, index?: number): void {
    if (!element) {
      this.deselectElement();
      return;
    }
    const key = this.getElementKey(element, index);
    this.selectedElementKey.set(key);
    this.elementValidationError.set(null);
    this.selectionChanged.emit(new Set());
    this.elementSelected.emit(element);
  }

  deselectElement(): void {
    this.selectedElementKey.set(null);
    this.elementValidationError.set(null);
    this.elementSelected.emit(null);
  }

  onElementClick(event: { event: MouseEvent; element: VenueLayoutElement }): void {
    if (!this.editable()) {
      return;
    }
    const idx = this.internalElements().indexOf(event.element);
    this.selectElement(event.element, idx);
  }

  onElementPointerDown(event: { event: PointerEvent; element: VenueLayoutElement }): void {
    if (!this.editable()) {
      return;
    }

    const idx = this.internalElements().indexOf(event.element);
    this.selectElement(event.element, idx);

    const viewport = this.svgViewport()?.nativeElement;
    if (viewport) {
      viewport.setPointerCapture(event.event.pointerId);
      this.capturedElement = viewport;
      this.capturedPointerId = event.event.pointerId;
    }

    this.mode = 'drag-element';
    this.activeLayoutElement = event.element;
    this.activeElementIndex = idx;
    this.initialElementGeometry = { ...event.element.geometry };
    this.dragStartClient = { x: event.event.clientX, y: event.event.clientY };
    this.lastDragClient = { x: event.event.clientX, y: event.event.clientY };
    this.totalDragDistance = 0;
    this.isDragging.set(true);
    this.activePointers.set(event.event.pointerId, {
      x: event.event.clientX,
      y: event.event.clientY,
    });
  }

  onElementHandlePointerDown(event: {
    event: PointerEvent;
    element: VenueLayoutElement;
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

    this.activeLayoutElement = event.element;
    this.activeElementIndex = this.internalElements().indexOf(event.element);
    this.activeHandle = event.handle;
    this.initialElementGeometry = { ...event.element.geometry };
    this.dragStartClient = { x: event.event.clientX, y: event.event.clientY };
    this.lastDragClient = { x: event.event.clientX, y: event.event.clientY };
    this.totalDragDistance = 0;
    this.isDragging.set(true);
    this.mode = event.handle === 'rotate' ? 'rotate-element' : 'resize-element';
    this.activePointers.set(event.event.pointerId, {
      x: event.event.clientX,
      y: event.event.clientY,
    });
  }

  onElementKeyDown(event: { event: KeyboardEvent; element: VenueLayoutElement }): void {
    if (!this.editable()) {
      return;
    }
    const ke = event.event;
    const step = this.snapStep() > 0 ? this.snapStep() : 10;
    if (ke.key === 'ArrowUp') {
      ke.preventDefault();
      this.updateSelectedElementGeometry({ y: event.element.geometry.y - step });
    } else if (ke.key === 'ArrowDown') {
      ke.preventDefault();
      this.updateSelectedElementGeometry({ y: event.element.geometry.y + step });
    } else if (ke.key === 'ArrowLeft') {
      ke.preventDefault();
      this.updateSelectedElementGeometry({ x: event.element.geometry.x - step });
    } else if (ke.key === 'ArrowRight') {
      ke.preventDefault();
      this.updateSelectedElementGeometry({ x: event.element.geometry.x + step });
    } else if (ke.key === 'Delete' || ke.key === 'Backspace') {
      ke.preventDefault();
      this.removeElement(event.element);
    } else if ((ke.ctrlKey || ke.metaKey) && ke.key.toLowerCase() === 'd') {
      ke.preventDefault();
      this.duplicateElement(event.element);
    }
  }

  onElementCreatedFromPalette(element: VenueLayoutElement): void {
    if (!this.editable()) {
      return;
    }
    if (!element || !isValidLayoutElementType(element.type)) {
      this.elementValidationError.set(`Unsupported layout element type: ${element?.type}`);
      return;
    }
    // Creation contract (TASK-P11-008 §5): new elements are always null-ID and
    // LABEL requires visible non-blank text. Reject before any state mutation.
    if (element.type === 'LABEL' && (!element.label || element.label.trim() === '')) {
      this.elementValidationError.set('LABEL requires visible non-blank text');
      return;
    }
    const g = element.geometry;
    const coords = [g?.x, g?.y, g?.width, g?.height, g?.rotationDeg];
    if (!g || !coords.every((v) => Number.isFinite(v))) {
      this.elementValidationError.set('Invalid element geometry');
      return;
    }
    const normalized: VenueLayoutElement = {
      elementId: null,
      type: element.type,
      label: element.type === 'LABEL' ? element.label!.trim() : (element.label ?? null),
      geometry: {
        x: clampNumber(g.x, MIN_POSITION, MAX_POSITION),
        y: clampNumber(g.y, MIN_POSITION, MAX_POSITION),
        width: clampDimension(g.width, MIN_DIMENSION, MAX_DIMENSION),
        height: clampDimension(g.height, MIN_DIMENSION, MAX_DIMENSION),
        rotationDeg: clampNumber(normalizeRotation(g.rotationDeg), MIN_ROTATION, MAX_ROTATION),
      },
      zIndex: clampNumber(
        Number.isFinite(element.zIndex) ? element.zIndex : 0,
        MIN_Z_INDEX,
        MAX_Z_INDEX,
      ),
    };
    this.elementValidationError.set(null);
    this.internalElements.update((list) => [...list, normalized]);
    const idx = this.internalElements().length - 1;
    this.selectElement(normalized, idx);
    this.elementsChange.emit(this.internalElements());
  }

  duplicateSelectedElement(): VenueLayoutElement | null {
    const selected = this.selectedElement();
    if (!selected) {
      return null;
    }
    return this.duplicateElement(selected);
  }

  duplicateElement(element: VenueLayoutElement): VenueLayoutElement | null {
    if (!this.editable() || !element) {
      return null;
    }
    const newX = clampNumber(element.geometry.x + 20, MIN_POSITION, MAX_POSITION);
    const newY = clampNumber(element.geometry.y + 20, MIN_POSITION, MAX_POSITION);

    let maxZ = -1;
    for (const el of this.internalElements()) {
      if (Number.isFinite(el?.zIndex) && el.zIndex > maxZ) {
        maxZ = el.zIndex;
      }
    }
    for (const s of this.sections()) {
      if (Number.isFinite(s?.zIndex) && s.zIndex > maxZ) {
        maxZ = s.zIndex;
      }
    }
    const nextZ = clampNumber(maxZ + 1, MIN_Z_INDEX, MAX_Z_INDEX);

    const copy: VenueLayoutElement = {
      elementId: null,
      type: element.type,
      label: element.label,
      geometry: {
        x: newX,
        y: newY,
        width: element.geometry.width,
        height: element.geometry.height,
        rotationDeg: element.geometry.rotationDeg,
      },
      zIndex: nextZ,
    };

    this.internalElements.update((list) => [...list, copy]);
    const newIdx = this.internalElements().length - 1;
    this.selectElement(copy, newIdx);
    this.elementDuplicated.emit(copy);
    this.elementsChange.emit(this.internalElements());
    return copy;
  }

  removeSelectedElement(): void {
    const selected = this.selectedElement();
    if (selected) {
      this.removeElement(selected);
    }
  }

  removeElement(element: VenueLayoutElement): void {
    if (!this.editable() || !element) {
      return;
    }
    // Capture selection BEFORE filtering: for null-ID elements the selection key
    // is index-based, so a post-removal lookup would resolve to the next element
    // occupying the freed index and wrongly keep the inspector open on it.
    const wasSelected = this.selectedElement() === element;
    this.internalElements.update((list) => list.filter((el) => el !== element));
    if (wasSelected) {
      this.deselectElement();
    }
    this.elementRemoved.emit(element);
    this.elementsChange.emit(this.internalElements());
  }

  updateSelectedElementLabel(newLabel: string): boolean {
    const selected = this.selectedElement();
    if (!this.editable() || !selected) {
      return false;
    }
    if (selected.type === 'LABEL' && (!newLabel || newLabel.trim() === '')) {
      this.elementValidationError.set('LABEL requires visible non-blank text');
      return false;
    }
    this.elementValidationError.set(null);
    const formattedLabel = selected.type === 'LABEL' ? newLabel.trim() : newLabel || null;
    const updated: VenueLayoutElement = {
      ...selected,
      label: formattedLabel,
    };
    this.internalElements.update((list) => list.map((el) => (el === selected ? updated : el)));
    this.elementUpdated.emit(updated);
    this.elementsChange.emit(this.internalElements());
    return true;
  }

  updateSelectedElementGeometry(geom: Partial<LayoutGeometry>): void {
    const selected = this.selectedElement();
    if (!this.editable() || !selected) {
      return;
    }
    const current = selected.geometry;
    const newX = geom.x !== undefined ? clampNumber(geom.x, MIN_POSITION, MAX_POSITION) : current.x;
    const newY = geom.y !== undefined ? clampNumber(geom.y, MIN_POSITION, MAX_POSITION) : current.y;
    const newW =
      geom.width !== undefined
        ? clampDimension(geom.width, MIN_DIMENSION, MAX_DIMENSION)
        : current.width;
    const newH =
      geom.height !== undefined
        ? clampDimension(geom.height, MIN_DIMENSION, MAX_DIMENSION)
        : current.height;
    const newRot =
      geom.rotationDeg !== undefined
        ? clampNumber(normalizeRotation(geom.rotationDeg), MIN_ROTATION, MAX_ROTATION)
        : current.rotationDeg;

    const updated: VenueLayoutElement = {
      ...selected,
      geometry: {
        x: newX,
        y: newY,
        width: newW,
        height: newH,
        rotationDeg: newRot,
      },
    };
    this.internalElements.update((list) => list.map((el) => (el === selected ? updated : el)));
    this.elementUpdated.emit(updated);
    this.elementsChange.emit(this.internalElements());
  }

  updateSelectedElementZIndex(newZ: number): void {
    const selected = this.selectedElement();
    if (!this.editable() || !selected) {
      return;
    }
    const clampedZ = clampNumber(newZ, MIN_Z_INDEX, MAX_Z_INDEX);
    const updated: VenueLayoutElement = {
      ...selected,
      zIndex: clampedZ,
    };
    this.internalElements.update((list) => list.map((el) => (el === selected ? updated : el)));
    this.elementUpdated.emit(updated);
    this.elementsChange.emit(this.internalElements());
  }

  onNumericParamChange(
    param: 'x' | 'y' | 'width' | 'height' | 'rotationDeg' | 'zIndex',
    event: Event,
  ): void {
    const input = event.target as HTMLInputElement;
    const val = parseFloat(input.value);
    if (!Number.isFinite(val)) {
      return;
    }
    if (param === 'zIndex') {
      this.updateSelectedElementZIndex(val);
    } else {
      this.updateSelectedElementGeometry({ [param]: val });
    }
  }

  onLabelInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.updateSelectedElementLabel(input.value);
  }

  onLabelChange(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.updateSelectedElementLabel(input.value);
  }

  // --- Seat & Guide Handlers ---

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
    this.activeLayoutElement = null;
    this.activeElementIndex = null;
    this.initialElementGeometry = null;
    this.activeHandle = null;
    this.capturedElement = null;
    this.capturedPointerId = null;
    this.pinchStartDistance = 0;
  }

  /**
   * One-shot auto-fit that only runs against a really measured viewport rect.
   * Returns true when the fit ran (consuming the one-shot); false when the
   * viewport is not laid out yet, leaving the one-shot pending so the next
   * trigger (AfterViewInit backstop or a later input emission) retries with
   * real dimensions instead of locking in fallback geometry.
   */
  private tryAutoFit(): boolean {
    const el = this.svgViewport()?.nativeElement;
    const rect = el?.getBoundingClientRect();
    if (!rect || rect.width <= 0 || rect.height <= 0) {
      return false;
    }
    this.autoFitDone = true;
    this.fitToLayout();
    return true;
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
