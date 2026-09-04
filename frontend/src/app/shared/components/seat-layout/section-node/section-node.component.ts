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
    event: MouseEvent;
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

  onSectionPointerDown(event: PointerEvent): void {
    if ((event.target as Element)?.closest?.('.transform-handle')) {
      return;
    }
    this.sectionPointerDown.emit({ event, section: this.section() });
  }

  onSectionClick(event: MouseEvent): void {
    if ((event.target as Element)?.closest?.('.transform-handle')) {
      return;
    }
    if (!this.editable() && !this.section().isActive) {
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
    this.seatClick.emit({ event, seat, section: this.section() });
  }
}
