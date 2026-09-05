import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { VenueSectionLayout, VenueSectionSeat } from '../../../../models/venue.model';
import {
  CornerHandle,
  computeEffectiveSectionDimensions,
  resolveSectionColor,
  sectionContentOffset,
} from '../../../utils/layout-geometry';

export type CanvasToolMode = 'select' | 'toggle' | 'paint';

/**
 * Customer-facing seat presentation override (TASK-P11-012).
 *
 * Lets read-only consumers (customer seat map, admin preview) reuse the
 * shared spatial renderer while presenting booking statuses, pricing-tier
 * colors, accessible labels/tooltips, legend highlight dimming, and conflict
 * flags. Editor flows leave `customerSeatStates` unset and keep the exact
 * legacy behavior (section/shape colors, all-interactive tabindex).
 */
export interface CustomerSeatPresentation {
  status: 'AVAILABLE' | 'HELD' | 'SOLD' | 'RESERVED' | 'DISABLED';
  /** Tier color for AVAILABLE seats, muted gray for held/sold/unavailable. */
  color?: string;
  /** Full accessible label (section, row, seat, category, price, status). */
  ariaLabel?: string;
  /** Native SVG tooltip text (category, price, status). */
  tooltip?: string;
  /** True when another legend category is highlighted. */
  dimmed?: boolean;
  /** True when a live update flagged this seat as conflicted. */
  conflicted?: boolean;
}

@Component({
  selector: 'g[app-section-node], app-section-node',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './section-node.component.html',
  styleUrl: './section-node.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SectionNodeComponent {
  readonly section = input.required<VenueSectionLayout>();
  readonly selected = input<boolean>(false);
  readonly editable = input<boolean>(true);
  readonly snapStep = input<number>(0);
  readonly toolMode = input<CanvasToolMode>('select');
  readonly paintColor = input<string>('');
  readonly selectedSeatKeys = input<ReadonlySet<string>>(new Set<string>());
  readonly customerSeatStates = input<ReadonlyMap<string, CustomerSeatPresentation> | null>(null);
  /**
   * When non-null, interactive seats use roving tabindex: only the seat whose
   * stable key matches receives `tabindex="0"`, all others get `-1`.
   * Null preserves the legacy behavior (every interactive seat focusable).
   */
  readonly rovingActiveSeatKey = input<string | null>(null);

  readonly sectionClick = output<{ event: MouseEvent; section: VenueSectionLayout }>();
  readonly sectionPointerDown = output<{ event: PointerEvent; section: VenueSectionLayout }>();
  readonly handlePointerDown = output<{
    event: PointerEvent;
    section: VenueSectionLayout;
    handle: CornerHandle | 'rotate';
  }>();
  readonly seatClick = output<{
    event: MouseEvent | KeyboardEvent;
    seat: VenueSectionSeat;
    section: VenueSectionLayout;
  }>();
  readonly seatToggle = output<{
    seat: VenueSectionSeat;
    section: VenueSectionLayout;
  }>();
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

  readonly handleSize = 10;
  readonly halfHandle = 5;

  readonly effectiveDimensions = computed(() => computeEffectiveSectionDimensions(this.section()));
  readonly effectiveWidth = computed(() => this.effectiveDimensions().width);
  readonly effectiveHeight = computed(() => this.effectiveDimensions().height);

  readonly transformString = computed(() => {
    const s = this.section();
    const px = Number.isFinite(s.positionX) ? s.positionX : 0;
    const py = Number.isFinite(s.positionY) ? s.positionY : 0;
    const rot = Number.isFinite(s.rotationDeg) ? s.rotationDeg : 0;
    const w = this.effectiveWidth();
    const h = this.effectiveHeight();
    return `translate(${px} ${py}) rotate(${rot} ${w / 2} ${h / 2})`;
  });

  readonly isSelectable = computed(() => {
    return this.editable() || this.section().isActive;
  });

  /**
   * Visual-only centering of seat content inside the section box
   * (TASK-P11-012 FIX B). Applied as a transform on the seats layer so seat
   * data, stable keys, rotation, and grid keyboard navigation stay exact.
   * Shared by editor, customer, and admin preview renderers.
   */
  readonly contentOffset = computed(() => sectionContentOffset(this.section()));

  /** Computes unique rows with their Y center position for canvas row guides. */
  readonly rowHeaders = computed(() => {
    const sec = this.section();
    const dy = this.contentOffset().dy;
    const map = new Map<string, { rowLabel: string; y: number; count: number }>();
    for (const seat of sec.seats || []) {
      const existing = map.get(seat.rowLabel);
      if (!existing) {
        map.set(seat.rowLabel, { rowLabel: seat.rowLabel, y: seat.positionY + dy, count: 1 });
      } else {
        existing.count++;
      }
    }
    return Array.from(map.values());
  });

  /** Computes unique columns with their X center position for canvas column guides. */
  readonly colHeaders = computed(() => {
    const sec = this.section();
    const dx = this.contentOffset().dx;
    const map = new Map<number, { colIndex: number; x: number; count: number }>();
    for (const seat of sec.seats || []) {
      const existing = map.get(seat.gridX);
      if (!existing) {
        map.set(seat.gridX, { colIndex: seat.gridX, x: seat.positionX + dx, count: 1 });
      } else {
        existing.count++;
      }
    }
    return Array.from(map.values()).sort((a, b) => a.colIndex - b.colIndex);
  });

  getSeatKey(seat: VenueSectionSeat): string {
    return seat.seatId || `${seat.gridY}_${seat.gridX}`;
  }

  isSeatSelected(seat: VenueSectionSeat): boolean {
    const keys = this.selectedSeatKeys();
    const key = this.getSeatKey(seat);
    return keys.has(key) || (!!seat.seatId && keys.has(seat.seatId));
  }

  getSectionColor(): string {
    return resolveSectionColor(this.section());
  }

  customerState(seat: VenueSectionSeat): CustomerSeatPresentation | null {
    const states = this.customerSeatStates();
    if (!states) {
      return null;
    }
    return states.get(this.getSeatKey(seat)) ?? null;
  }

  seatTabIndex(seat: VenueSectionSeat): number {
    if (!this.isSeatInteractive(seat)) {
      return -1;
    }
    const rovingKey = this.rovingActiveSeatKey();
    if (rovingKey === null || rovingKey === undefined) {
      return 0;
    }
    return this.getSeatKey(seat) === rovingKey ? 0 : -1;
  }

  seatAriaLabel(seat: VenueSectionSeat): string {
    const override = this.customerState(seat)?.ariaLabel;
    if (override) {
      return override;
    }
    return (
      'Row ' + seat.rowLabel + ', Seat ' + seat.seatNumber + (seat.isActive ? '' : ' (inactive)')
    );
  }

  getSeatColor(seat: VenueSectionSeat): string {
    const customerColor = this.customerState(seat)?.color;
    if (customerColor) {
      return customerColor;
    }
    const meta = this.section().shapeMetadata as Record<string, unknown> | null;
    if (meta && typeof meta['seatColors'] === 'object' && meta['seatColors'] !== null) {
      const seatColors = meta['seatColors'] as Record<string, string>;
      const k1 = `${seat.rowLabel}_${seat.seatNumber}`;
      const k2 = `${seat.gridY}_${seat.gridX}`;
      const k3 = seat.seatId ?? '';
      if (seatColors[k1]) return seatColors[k1];
      if (seatColors[k2]) return seatColors[k2];
      if (k3 && seatColors[k3]) return seatColors[k3];
    }
    return this.getSectionColor();
  }

  isSeatColorLight(seat: VenueSectionSeat): boolean {
    const color = this.getSeatColor(seat);
    return this.isColorLight(color);
  }

  isColorLight(hexColor: string): boolean {
    if (!hexColor) return false;
    let hex = hexColor.replace('#', '').trim();
    if (hex.length === 3) {
      hex = hex
        .split('')
        .map((c) => c + c)
        .join('');
    }
    if (hex.length !== 6) return false;
    const r = parseInt(hex.substring(0, 2), 16);
    const g = parseInt(hex.substring(2, 4), 16);
    const b = parseInt(hex.substring(4, 6), 16);
    if (isNaN(r) || isNaN(g) || isNaN(b)) return false;
    // Standard ITU-R BT.601 perceived luminance
    const luminance = (r * 299 + g * 587 + b * 114) / 1000;
    return luminance > 165;
  }

  private lastPointerDownSeatKey: string | null = null;
  private lastPointerDownTime = 0;

  isSeatInteractive(seat: VenueSectionSeat): boolean {
    return this.editable() || (this.section().isActive && seat.isActive);
  }

  onSectionPointerDown(event: PointerEvent): void {
    const target = event.target as Element | null;
    if (
      target?.closest?.('.transform-handle') ||
      target?.closest?.('.canvas-guide-badge') ||
      target?.closest?.('.seat-item')
    ) {
      return;
    }
    if (!this.editable()) {
      event.stopPropagation();
      return;
    }
    this.sectionPointerDown.emit({ event, section: this.section() });
  }

  onSectionClick(event: MouseEvent): void {
    const target = event.target as Element | null;
    if (
      target?.closest?.('.transform-handle') ||
      target?.closest?.('.canvas-guide-badge') ||
      target?.closest?.('.seat-item')
    ) {
      return;
    }
    if (!this.editable() && !this.section().isActive) {
      event.stopPropagation();
      return;
    }
    this.sectionClick.emit({ event, section: this.section() });
  }

  onHandlePointerDown(event: PointerEvent, handle: CornerHandle | 'rotate'): void {
    event.stopPropagation();
    event.preventDefault();
    this.handlePointerDown.emit({ event, section: this.section(), handle });
  }

  onSeatPointerDown(event: PointerEvent, seat: VenueSectionSeat): void {
    event.stopPropagation();
    if (!this.isSeatInteractive(seat)) {
      return;
    }

    const key = `${seat.rowLabel}_${seat.seatNumber}`;
    const now = Date.now();

    if (this.editable()) {
      if (this.toolMode() === 'toggle' || event.altKey) {
        this.lastPointerDownSeatKey = key;
        this.lastPointerDownTime = now;
        this.seatToggle.emit({ seat, section: this.section() });
        return;
      }
      if (this.toolMode() === 'paint') {
        this.lastPointerDownSeatKey = key;
        this.lastPointerDownTime = now;
        const color = this.paintColor() || this.getSectionColor();
        this.seatPaint.emit({ seat, section: this.section(), color });
        return;
      }
    }
  }

  onSeatClick(event: MouseEvent, seat: VenueSectionSeat): void {
    event.stopPropagation();
    if (!this.isSeatInteractive(seat)) {
      return;
    }

    const key = `${seat.rowLabel}_${seat.seatNumber}`;
    const now = Date.now();
    // If already handled by pointerdown within last 400ms, ignore the synthetic/subsequent click event
    if (this.lastPointerDownSeatKey === key && now - this.lastPointerDownTime < 400) {
      return;
    }

    if (this.editable()) {
      if (this.toolMode() === 'toggle' || event.altKey) {
        this.seatToggle.emit({ seat, section: this.section() });
        return;
      }
      if (this.toolMode() === 'paint') {
        const color = this.paintColor() || this.getSectionColor();
        this.seatPaint.emit({ seat, section: this.section(), color });
        return;
      }
    }

    this.seatClick.emit({ event, seat, section: this.section() });
  }

  onSeatDblClick(event: MouseEvent, seat: VenueSectionSeat): void {
    event.stopPropagation();
    event.preventDefault();
    if (!this.editable()) {
      return;
    }
    // Double click directly toggles active/aisle
    this.seatToggle.emit({ seat, section: this.section() });
  }

  onSeatKeyDown(event: KeyboardEvent, seat: VenueSectionSeat): void {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      event.stopPropagation();
      if (!this.isSeatInteractive(seat)) {
        return;
      }
      if (this.editable() && this.toolMode() === 'toggle') {
        this.seatToggle.emit({ seat, section: this.section() });
        return;
      }
      // REV-008: keyboard users paint exactly like the pointer path in paint mode.
      if (this.editable() && this.toolMode() === 'paint') {
        const color = this.paintColor() || this.getSectionColor();
        this.seatPaint.emit({ seat, section: this.section(), color });
        return;
      }
      this.seatClick.emit({ event, seat, section: this.section() });
    }
  }

  onRowClick(event: MouseEvent, rowLabel: string): void {
    event.stopPropagation();
    this.rowClick.emit({ event, rowLabel, section: this.section() });
  }

  onRowDblClick(event: MouseEvent, rowLabel: string): void {
    event.stopPropagation();
    event.preventDefault();
    this.rowDblClick.emit({ event, rowLabel, section: this.section() });
  }

  onColClick(event: MouseEvent, colIndex: number): void {
    event.stopPropagation();
    this.colClick.emit({ event, colIndex, section: this.section() });
  }

  onColDblClick(event: MouseEvent, colIndex: number): void {
    event.stopPropagation();
    event.preventDefault();
    this.colDblClick.emit({ event, colIndex, section: this.section() });
  }
}
