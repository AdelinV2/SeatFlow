import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { VenueSectionLayout, VenueSectionSeat } from '../../../../models/venue.model';
import { CornerHandle } from '../../../utils/layout-geometry';

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

  readonly handleSize = 10;
  readonly halfHandle = 5;

  readonly transformString = computed(() => {
    const s = this.section();
    const px = Number.isFinite(s.positionX) ? s.positionX : 0;
    const py = Number.isFinite(s.positionY) ? s.positionY : 0;
    const rot = Number.isFinite(s.rotationDeg) ? s.rotationDeg : 0;
    const w = Number.isFinite(s.width) ? s.width : 0;
    const h = Number.isFinite(s.height) ? s.height : 0;
    return `translate(${px} ${py}) rotate(${rot} ${w / 2} ${h / 2})`;
  });

  readonly isSelectable = computed(() => {
    return this.editable() || this.section().isActive;
  });

  isSeatInteractive(seat: VenueSectionSeat): boolean {
    return this.editable() || (this.section().isActive && seat.isActive);
  }

  onSectionPointerDown(event: PointerEvent): void {
    if ((event.target as Element)?.closest?.('.transform-handle')) {
      return;
    }
    if (!this.editable()) {
      // REV-004: keep read-only geometry hit-testable so this guard runs;
      // stop propagation to prevent canvas background pan/clear.
      event.stopPropagation();
      return;
    }
    this.sectionPointerDown.emit({ event, section: this.section() });
  }

  onSectionClick(event: MouseEvent): void {
    if ((event.target as Element)?.closest?.('.transform-handle')) {
      return;
    }
    if (!this.editable() && !this.section().isActive) {
      // REV-004: suppress inactive read-only selection; stop bubbling toward canvas handlers.
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

  onSeatClick(event: MouseEvent, seat: VenueSectionSeat): void {
    event.stopPropagation();
    if (!this.isSeatInteractive(seat)) {
      return;
    }
    this.seatClick.emit({ event, seat, section: this.section() });
  }

  onSeatKeyDown(event: KeyboardEvent, seat: VenueSectionSeat): void {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      event.stopPropagation();
      if (!this.isSeatInteractive(seat)) {
        return;
      }
      this.seatClick.emit({ event, seat, section: this.section() });
    }
  }
}
