import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Seat } from '../../../models/seat.model';

interface PointerPosition {
  x: number;
  y: number;
}

@Component({
  selector: 'app-seat-map',
  standalone: true,
  imports: [CommonModule, MatTooltipModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './seat-map.component.html',
  styleUrl: './seat-map.component.scss',
})
export class SeatMapComponent {
  private readonly snackBar = inject(MatSnackBar);
  private readonly activePointers = new Map<number, PointerPosition>();
  private pinchStartDistance = 0;
  private pinchStartZoom = 1;
  private pinchStartMidpoint: PointerPosition = { x: 0, y: 0 };
  private pinchStartPan: PointerPosition = { x: 0, y: 0 };
  private lastDragPosition: PointerPosition | null = null;

  readonly seats = input.required<Seat[]>();
  readonly maxSeats = input(10);
  readonly selectedSeatIds = input<Set<string>>(new Set());
  readonly seatToggled = output<Seat>();

  readonly zoomLevel = signal(1);
  readonly panX = signal(0);
  readonly panY = signal(0);
  readonly isDragging = signal(false);
  readonly isolatedSectionId = signal<string | null>(null);
  readonly animatingSeatIds = signal<Set<string>>(new Set());

  readonly sections = computed(() => {
    const sections = new Map<string, string>();
    for (const seat of this.seats()) {
      sections.set(seat.sectionId, seat.sectionName ?? 'Section');
    }
    return [...sections].map(([id, name]) => ({ id, name }));
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

  readonly mapWidth = computed(() => {
    const maxColumn = this.visibleSeats().reduce((maximum, seat) => Math.max(maximum, seat.gridX), 0);
    return Math.max(520, (maxColumn + 1) * 38 + 96);
  });

  readonly mapHeight = computed(() => {
    const maxRow = this.visibleSeats().reduce((maximum, seat) => Math.max(maximum, seat.gridY), 0);
    return Math.max(330, (maxRow + 1) * 38 + 156);
  });

  readonly viewBox = computed(() => `0 0 ${this.mapWidth()} ${this.mapHeight()}`);
  readonly transformMatrix = computed(
    () => `translate(${this.panX()} ${this.panY()}) scale(${this.zoomLevel()})`,
  );

  handleSeatClick(seat: Seat): void {
    const selected = this.selectedSeatIds().has(seat.id);
    if (!selected && (!seat.isActive || ['SOLD', 'RESERVED', 'DISABLED'].includes(seat.status))) {
      return;
    }
    if (!selected && seat.status === 'HELD') {
      this.snackBar.open('This seat is currently held by another customer.', 'Close', {
        duration: 3000,
        panelClass: 'snack-warning',
      });
      return;
    }
    if (!selected && this.selectedSeatIds().size >= this.maxSeats()) {
      this.snackBar.open('Maximum 10 seats allowed per reservation.', 'Close', {
        duration: 3500,
        panelClass: 'snack-warning',
      });
      return;
    }

    this.animatingSeatIds.update((current) => new Set(current).add(seat.id));
    this.seatToggled.emit(seat);
  }

  handleSeatKeydown(event: KeyboardEvent, seat: Seat): void {
    if (event.key !== 'Enter' && event.key !== ' ') {
      return;
    }
    event.preventDefault();
    this.handleSeatClick(seat);
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
    return seat.gridX * 38 + 48;
  }

  seatY(seat: Seat): number {
    return seat.gridY * 38 + 112;
  }

  seatLabel(seat: Seat): string {
    return `${seat.sectionName ?? 'Section'}, row ${seat.rowLabel}, seat ${seat.seatNumber}, ${this.formatPrice(seat)}, ${seat.status.toLowerCase()}`;
  }

  seatTooltip(seat: Seat): string {
    return this.seatLabel(seat).replaceAll(', ', ' · ');
  }

  private formatPrice(seat: Seat): string {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: seat.currency ?? 'USD',
    }).format(seat.price);
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
