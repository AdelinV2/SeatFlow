import { CommonModule } from '@angular/common';
import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  input,
  OnDestroy,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { EventSeatMapResponse, Seat, SeatMapSectionResponse } from '../../../models/seat.model';
import { KeyboardSeatNavDirective } from '../../../core/a11y/keyboard-seat-nav.directive';
import { CurrencyFormatPipe } from '../../../shared/pipes/currency-format.pipe';

interface PointerPosition {
  x: number;
  y: number;
}

export interface ColumnHeader {
  number: number;
  x: number;
}

export interface RowHeader {
  label: string;
  y: number;
}

export interface PricingTierDetail {
  id?: string;
  sectionId: string;
  categoryName: string;
  price: number;
  currency: string;
}

export interface SeatRowGroup {
  rowIndex: number;
  label: string;
  seats: Seat[];
}

export interface SectionDetail {
  id: string;
  name: string;
  minPrice: number;
  maxPrice: number;
  currency: string;
  pricingTiers: PricingTierDetail[];
  availableSeats: number;
  totalSeats: number;
}

@Component({
  selector: 'app-seat-map',
  standalone: true,
  imports: [CommonModule, MatTooltipModule, CurrencyFormatPipe, KeyboardSeatNavDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './seat-map.component.html',
  styleUrl: './seat-map.component.scss',
})
export class SeatMapComponent implements AfterViewInit, OnDestroy {
  private readonly snackBar = inject(MatSnackBar);
  readonly svgViewport = viewChild<ElementRef<SVGSVGElement>>('svgViewport');
  private readonly activePointers = new Map<number, PointerPosition>();
  private pinchStartDistance = 0;
  private pinchStartZoom = 1;
  private pinchStartMidpoint: PointerPosition = { x: 0, y: 0 };
  private pinchStartPan: PointerPosition = { x: 0, y: 0 };
  private lastDragPosition: PointerPosition | null = null;

  readonly seatSpacing = 44;
  readonly gridStartY = 145;

  readonly seats = input.required<Seat[]>();
  readonly sectionsData = input<SeatMapSectionResponse[]>([]);
  readonly maxSeats = input(10);
  readonly selectedSeatIds = input<Set<string>>(new Set());
  readonly conflictingSeatIds = input<Set<string>>(new Set());
  readonly seatToggled = output<Seat>();

  readonly zoomLevel = signal(1);
  readonly panX = signal(0);
  readonly panY = signal(0);
  readonly isDragging = signal(false);
  readonly isolatedSectionId = signal<string | null>(null);
  readonly animatingSeatIds = signal<Set<string>>(new Set());
  readonly liveAnnouncement = signal<string>('');
  readonly activeSeatId = signal<string | null>(null);

  readonly sectionDetails = computed<SectionDetail[]>(() => {
    const rawSections = this.sectionsData();
    const sectionMap = new Map<
      string,
      {
        name: string;
        currency: string;
        pricingTiers: PricingTierDetail[];
        availableSeats: number;
        totalSeats: number;
        seatPrices: number[];
      }
    >();

    for (const rawSec of rawSections) {
      const tiers: PricingTierDetail[] = (rawSec.pricingTiers ?? []).map((t) => ({
        id: t.id,
        sectionId: rawSec.sectionId,
        categoryName: t.categoryName || 'Standard',
        price: Number(t.price),
        currency: t.currency || 'USD',
      }));
      tiers.sort((a, b) => a.price - b.price);

      sectionMap.set(rawSec.sectionId, {
        name: rawSec.name,
        currency: tiers[0]?.currency || 'USD',
        pricingTiers: tiers,
        availableSeats: 0,
        totalSeats: 0,
        seatPrices: [],
      });
    }

    for (const seat of this.seats()) {
      let entry = sectionMap.get(seat.sectionId);
      if (!entry) {
        entry = {
          name: seat.sectionName ?? 'Section',
          currency: seat.currency ?? 'USD',
          pricingTiers: [],
          availableSeats: 0,
          totalSeats: 0,
          seatPrices: [],
        };
        sectionMap.set(seat.sectionId, entry);
      }
      if (seat.price > 0) {
        entry.seatPrices.push(seat.price);
        if (seat.currency) {
          entry.currency = seat.currency;
        }
      }
      if (seat.isActive) {
        entry.totalSeats += 1;
        if (seat.status === 'AVAILABLE') {
          entry.availableSeats += 1;
        }
      }
    }

    return [...sectionMap.entries()].map(([id, info]) => {
      let tiers = info.pricingTiers;
      if (tiers.length === 0 && info.seatPrices.length > 0) {
        const uniquePrices = [...new Set(info.seatPrices)].sort((a, b) => a - b);
        tiers = uniquePrices.map((price) => ({
          sectionId: id,
          categoryName: 'Standard',
          price,
          currency: info.currency,
        }));
      }

      const allPrices =
        tiers.length > 0
          ? tiers.map((t) => t.price)
          : info.seatPrices.length > 0
            ? info.seatPrices
            : [0];

      const minPrice = Math.min(...allPrices);
      const maxPrice = Math.max(...allPrices);

      return {
        id,
        name: info.name,
        minPrice,
        maxPrice,
        currency: info.currency,
        pricingTiers: tiers,
        availableSeats: info.availableSeats,
        totalSeats: info.totalSeats,
      };
    });
  });

  readonly sections = computed(() => {
    return this.sectionDetails().map((s) => ({ id: s.id, name: s.name }));
  });

  readonly activeSectionSummary = computed<SectionDetail | null>(() => {
    const activeId = this.activeSectionId();
    return this.sectionDetails().find((s) => s.id === activeId) ?? null;
  });

  readonly activeSectionId = computed(() => {
    const requested = this.isolatedSectionId();
    const sections = this.sections();
    return sections.some((section) => section.id === requested)
      ? requested
      : (sections[0]?.id ?? null);
  });

  readonly visibleSeats = computed(() => {
    const activeSectionId = this.activeSectionId();
    return activeSectionId ? this.seats().filter((seat) => seat.sectionId === activeSectionId) : [];
  });

  readonly bookableSeats = computed(() => {
    return this.visibleSeats().filter((seat) => seat.isActive && seat.status !== 'DISABLED');
  });

  readonly seatsByRow = computed<SeatRowGroup[]>(() => {
    const seats = this.bookableSeats();
    const b = this.bounds();
    const rowsMap = new Map<number, { rowIndex: number; label: string; seats: Seat[] }>();

    for (const seat of seats) {
      const rowIndex = seat.gridY - b.minRow + 1;
      let row = rowsMap.get(seat.gridY);
      if (!row) {
        row = { rowIndex, label: seat.rowLabel, seats: [] };
        rowsMap.set(seat.gridY, row);
      }
      row.seats.push(seat);
    }

    return [...rowsMap.values()].sort((a, b) => a.rowIndex - b.rowIndex);
  });

  readonly effectiveActiveSeatId = computed<string | null>(() => {
    const current = this.activeSeatId();
    const available = this.bookableSeats();
    if (current && available.some((s) => s.id === current)) {
      return current;
    }
    const firstAvailable = available.find((s) => s.status === 'AVAILABLE') ?? available[0];
    return firstAvailable?.id ?? null;
  });

  isSeatFocused(seat: Seat): boolean {
    return this.effectiveActiveSeatId() === seat.id;
  }

  onSeatFocus(seat: Seat): void {
    this.activeSeatId.set(seat.id);
  }

  readonly bounds = computed(() => {
    const seats = this.visibleSeats();
    if (seats.length === 0) {
      return { minCol: 0, maxCol: 9, minRow: 0, maxRow: 4, colCount: 10, rowCount: 5 };
    }
    let minCol = Number.POSITIVE_INFINITY;
    let maxCol = Number.NEGATIVE_INFINITY;
    let minRow = Number.POSITIVE_INFINITY;
    let maxRow = Number.NEGATIVE_INFINITY;
    for (const s of seats) {
      minCol = Math.min(minCol, s.gridX);
      maxCol = Math.max(maxCol, s.gridX);
      minRow = Math.min(minRow, s.gridY);
      maxRow = Math.max(maxRow, s.gridY);
    }
    return {
      minCol: Number.isFinite(minCol) ? minCol : 0,
      maxCol: Number.isFinite(maxCol) ? maxCol : 9,
      minRow: Number.isFinite(minRow) ? minRow : 0,
      maxRow: Number.isFinite(maxRow) ? maxRow : 4,
      colCount: Math.max(1, (maxCol - minCol) + 1),
      rowCount: Math.max(1, (maxRow - minRow) + 1),
    };
  });

  readonly gridWidth = computed(() => {
    return (this.bounds().colCount - 1) * this.seatSpacing;
  });

  readonly mapWidth = computed(() => {
    return Math.max(680, this.gridWidth() + 160);
  });

  readonly gridStartX = computed(() => {
    return (this.mapWidth() - this.gridWidth()) / 2;
  });

  readonly mapHeight = computed(() => {
    return this.gridStartY + (this.bounds().rowCount - 1) * this.seatSpacing + 80;
  });

  readonly stageWidth = computed(() => {
    return Math.min(this.mapWidth() - 96, Math.max(360, this.gridWidth() + 100));
  });

  readonly stageX = computed(() => {
    return (this.mapWidth() - this.stageWidth()) / 2;
  });

  readonly columnHeaders = computed<ColumnHeader[]>(() => {
    const b = this.bounds();
    const headers: ColumnHeader[] = [];
    for (let c = b.minCol; c <= b.maxCol; c++) {
      headers.push({
        number: c + 1,
        x: this.gridStartX() + (c - b.minCol) * this.seatSpacing,
      });
    }
    return headers;
  });

  readonly rowHeaders = computed<RowHeader[]>(() => {
    const b = this.bounds();
    const rowsMap = new Map<number, string>();
    for (const seat of this.visibleSeats()) {
      if (!rowsMap.has(seat.gridY)) {
        rowsMap.set(seat.gridY, seat.rowLabel);
      }
    }
    const headers: RowHeader[] = [];
    for (const [gridY, label] of rowsMap.entries()) {
      headers.push({
        label,
        y: this.gridStartY + (gridY - b.minRow) * this.seatSpacing + 4,
      });
    }
    headers.sort((a, b) => a.y - b.y);
    return headers;
  });

  readonly viewBox = computed(() => `0 0 ${this.mapWidth()} ${this.mapHeight()}`);
  readonly transformMatrix = computed(
    () => `translate(${this.panX()} ${this.panY()}) scale(${this.zoomLevel()})`,
  );

  handleSeatClick(seat: Seat): void {
    this.activeSeatId.set(seat.id);
    const selected = this.selectedSeatIds().has(seat.id);
    if (!selected && (!seat.isActive || ['SOLD', 'RESERVED', 'DISABLED'].includes(seat.status))) {
      this.liveAnnouncement.set(`Seat in row ${seat.rowLabel}, seat ${seat.seatNumber} is unavailable.`);
      return;
    }
    if (!selected && seat.status === 'HELD') {
      this.liveAnnouncement.set(`Seat in row ${seat.rowLabel}, seat ${seat.seatNumber} is currently held.`);
      this.snackBar.open('This seat is currently held by another customer.', 'Close', {
        duration: 3000,
        panelClass: 'snack-warning',
      });
      return;
    }
    if (!selected && this.selectedSeatIds().size >= this.maxSeats()) {
      this.liveAnnouncement.set(`Maximum limit of ${this.maxSeats()} seats reached.`);
      this.snackBar.open(`Maximum ${this.maxSeats()} seats allowed per reservation.`, 'Close', {
        duration: 3500,
        panelClass: 'snack-warning',
      });
      return;
    }

    if (selected) {
      this.liveAnnouncement.set(`Deselected seat in row ${seat.rowLabel}, seat ${seat.seatNumber}.`);
    } else {
      this.liveAnnouncement.set(
        `Selected seat in row ${seat.rowLabel}, seat ${seat.seatNumber}, price ${this.formatPrice(seat.price, seat.currency)}.`,
      );
    }

    this.animatingSeatIds.update((current) => new Set(current).add(seat.id));
    this.seatToggled.emit(seat);
  }

  handleSeatNavigate(coords: { row: number; col: number }): void {
    const b = this.bounds();
    const targetGridY = b.minRow + coords.row;
    const targetGridX = b.minCol + coords.col;

    const targetSeat = this.bookableSeats().find(
      (s) => s.gridY === targetGridY && s.gridX === targetGridX,
    );

    if (targetSeat) {
      this.activeSeatId.set(targetSeat.id);
      const el = this.svgViewport()?.nativeElement.querySelector<SVGGElement>(
        `[data-seat-id="${targetSeat.id}"]`,
      );
      if (el) {
        el.focus();
      }
    }
  }

  clearSeatAnimation(seatId: string): void {
    this.animatingSeatIds.update((current) => {
      const updated = new Set(current);
      updated.delete(seatId);
      return updated;
    });
  }

  isolateSection(sectionId: string): void {
    this.isolatedSectionId.set(sectionId);
    this.resetView();
  }

  zoomIn(): void {
    this.setZoom(this.zoomLevel() + 0.2);
  }

  zoomOut(): void {
    this.setZoom(this.zoomLevel() - 0.2);
  }

  setZoomFromInput(event: Event): void {
    this.setZoom(Number((event.target as HTMLInputElement).value));
  }

  private readonly wheelHandler = (event: WheelEvent): void => {
    this.onWheel(event);
  };

  ngAfterViewInit(): void {
    const el = this.svgViewport()?.nativeElement;
    if (el) {
      el.addEventListener('wheel', this.wheelHandler, { passive: false });
    }
  }

  ngOnDestroy(): void {
    const el = this.svgViewport()?.nativeElement;
    if (el) {
      el.removeEventListener('wheel', this.wheelHandler);
    }
  }

  onWheel(event: WheelEvent): void {
    event.preventDefault();
    this.setZoom(this.zoomLevel() + (event.deltaY < 0 ? 0.12 : -0.12));
  }

  resetView(): void {
    this.zoomLevel.set(1);
    this.panX.set(0);
    this.panY.set(0);
  }

  onPointerDown(event: PointerEvent): void {
    if ((event.target as Element).closest('.seat-node')) {
      return;
    }

    (event.currentTarget as SVGSVGElement).setPointerCapture(event.pointerId);
    this.activePointers.set(event.pointerId, { x: event.clientX, y: event.clientY });

    if (this.activePointers.size === 1) {
      this.isDragging.set(true);
      this.lastDragPosition = { x: event.clientX, y: event.clientY };
    } else if (this.activePointers.size === 2) {
      this.beginPinch();
    }
  }

  onPointerMove(event: PointerEvent): void {
    if (!this.activePointers.has(event.pointerId)) {
      return;
    }

    this.activePointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
    if (this.activePointers.size >= 2) {
      const [first, second] = [...this.activePointers.values()];
      if (!first || !second || this.pinchStartDistance === 0) {
        return;
      }
      const distance = Math.hypot(second.x - first.x, second.y - first.y);
      const midpoint = this.midpoint(first, second);
      this.setZoom(this.pinchStartZoom * (distance / this.pinchStartDistance));
      this.panX.set(this.pinchStartPan.x + midpoint.x - this.pinchStartMidpoint.x);
      this.panY.set(this.pinchStartPan.y + midpoint.y - this.pinchStartMidpoint.y);
      return;
    }

    if (this.lastDragPosition) {
      this.panX.update((value) => value + event.clientX - this.lastDragPosition!.x);
      this.panY.update((value) => value + event.clientY - this.lastDragPosition!.y);
      this.lastDragPosition = { x: event.clientX, y: event.clientY };
    }
  }

  onPointerEnd(event: PointerEvent): void {
    const viewport = event.currentTarget as SVGSVGElement;
    if (viewport.hasPointerCapture(event.pointerId)) {
      viewport.releasePointerCapture(event.pointerId);
    }
    this.activePointers.delete(event.pointerId);
    this.isDragging.set(this.activePointers.size > 0);
    this.pinchStartDistance = 0;

    const remaining = [...this.activePointers.values()][0];
    this.lastDragPosition = remaining ?? null;
  }

  seatX(seat: Seat): number {
    return this.gridStartX() + (seat.gridX - this.bounds().minCol) * this.seatSpacing;
  }

  seatY(seat: Seat): number {
    return this.gridStartY + (seat.gridY - this.bounds().minRow) * this.seatSpacing;
  }

  seatLabel(seat: Seat): string {
    return `${seat.sectionName ?? 'Section'}, row ${seat.rowLabel}, seat ${seat.seatNumber}, ${this.formatPrice(seat.price, seat.currency)}, ${seat.status.toLowerCase()}`;
  }

  seatTooltip(seat: Seat): string {
    const sec = this.sectionDetails().find((s) => s.id === seat.sectionId);
    if (sec && sec.pricingTiers.length > 1) {
      const tiersStr = sec.pricingTiers
        .map((t) => `${t.categoryName}: ${this.formatPrice(t.price, t.currency)}`)
        .join(', ');
      return `${seat.sectionName ?? 'Section'} · Row ${seat.rowLabel}, Seat ${seat.seatNumber} · Rates: ${tiersStr} · ${seat.status.toLowerCase()}`;
    }
    return `${seat.sectionName ?? 'Section'} · Row ${seat.rowLabel}, Seat ${seat.seatNumber} · ${this.formatPrice(seat.price, seat.currency)} · ${seat.status.toLowerCase()}`;
  }

  private formatPrice(price: number, currency = 'USD'): string {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: currency || 'USD',
    }).format(price);
  }

  isUnavailable(seat: Seat): boolean {
    return !seat.isActive || ['SOLD', 'RESERVED', 'DISABLED'].includes(seat.status);
  }

  private setZoom(value: number): void {
    this.zoomLevel.set(Math.min(2.5, Math.max(0.5, Number.isFinite(value) ? value : 1)));
  }

  private beginPinch(): void {
    const [first, second] = [...this.activePointers.values()];
    if (!first || !second) {
      return;
    }
    this.pinchStartDistance = Math.hypot(second.x - first.x, second.y - first.y);
    this.pinchStartZoom = this.zoomLevel();
    this.pinchStartMidpoint = this.midpoint(first, second);
    this.pinchStartPan = { x: this.panX(), y: this.panY() };
    this.lastDragPosition = null;
  }

  private midpoint(first: PointerPosition, second: PointerPosition): PointerPosition {
    return { x: (first.x + second.x) / 2, y: (first.y + second.y) / 2 };
  }
}
