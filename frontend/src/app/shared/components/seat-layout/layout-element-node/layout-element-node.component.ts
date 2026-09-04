import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { VenueLayoutElement } from '../../../../models/venue.model';
import { CornerHandle } from '../../../utils/layout-geometry';
import { isValidLayoutElementType } from '../layout-element-palette/layout-element-palette.component';

@Component({
  selector: 'g[app-layout-element-node], app-layout-element-node',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './layout-element-node.component.html',
  styleUrl: './layout-element-node.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LayoutElementNodeComponent {
  readonly element = input.required<VenueLayoutElement>();
  readonly selected = input<boolean>(false);
  readonly editable = input<boolean>(true);
  readonly snapStep = input<number>(0);
  readonly validationError = input<string | null>(null);

  readonly elementClick = output<{ event: MouseEvent; element: VenueLayoutElement }>();
  readonly elementPointerDown = output<{ event: PointerEvent; element: VenueLayoutElement }>();
  readonly handlePointerDown = output<{
    event: PointerEvent;
    element: VenueLayoutElement;
    handle: CornerHandle | 'rotate';
  }>();
  readonly elementKeyDown = output<{ event: KeyboardEvent; element: VenueLayoutElement }>();

  readonly handleSize = 10;
  readonly halfHandle = 5;

  readonly isValidType = computed(() => {
    return isValidLayoutElementType(this.element()?.type);
  });

  readonly transformString = computed(() => {
    const el = this.element();
    const g = el?.geometry;
    if (!g) {
      return '';
    }
    const px = Number.isFinite(g.x) ? g.x : 0;
    const py = Number.isFinite(g.y) ? g.y : 0;
    const rot = Number.isFinite(g.rotationDeg) ? g.rotationDeg : 0;
    const w = Number.isFinite(g.width) ? g.width : 0;
    const h = Number.isFinite(g.height) ? g.height : 0;
    return `translate(${px} ${py}) rotate(${rot} ${w / 2} ${h / 2})`;
  });

  onPointerDown(event: PointerEvent): void {
    const target = event.target as Element | null;
    if (target?.closest?.('.transform-handle')) {
      return;
    }
    if (!this.editable()) {
      return;
    }
    this.elementPointerDown.emit({ event, element: this.element() });
  }

  onClick(event: MouseEvent): void {
    const target = event.target as Element | null;
    if (target?.closest?.('.transform-handle')) {
      return;
    }
    this.elementClick.emit({ event, element: this.element() });
  }

  onHandlePointerDown(event: PointerEvent, handle: CornerHandle | 'rotate'): void {
    event.stopPropagation();
    event.preventDefault();
    if (!this.editable()) {
      return;
    }
    this.handlePointerDown.emit({ event, element: this.element(), handle });
  }

  onKeyDown(event: KeyboardEvent): void {
    this.elementKeyDown.emit({ event, element: this.element() });
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      event.stopPropagation();
      this.elementClick.emit({ event: event as unknown as MouseEvent, element: this.element() });
    }
  }
}
