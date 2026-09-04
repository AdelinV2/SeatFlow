import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  LayoutElementType,
  LayoutGeometry,
  VenueLayoutElement,
  VenueSectionLayout,
} from '../../../../models/venue.model';
import {
  clampDimension,
  clampNumber,
  MAX_DIMENSION,
  MAX_POSITION,
  MAX_ROTATION,
  MAX_Z_INDEX,
  MIN_DIMENSION,
  MIN_POSITION,
  MIN_ROTATION,
  MIN_Z_INDEX,
  normalizeRotation,
} from '../../../utils/layout-geometry';

export const VALID_LAYOUT_ELEMENT_TYPES: readonly LayoutElementType[] = [
  'STAGE',
  'AISLE',
  'LABEL',
  'BARRIER',
  'DECORATION',
] as const;

export function isValidLayoutElementType(type: unknown): type is LayoutElementType {
  return typeof type === 'string' && VALID_LAYOUT_ELEMENT_TYPES.includes(type as LayoutElementType);
}

export const DEFAULT_ELEMENT_CONFIGS: Record<
  LayoutElementType,
  { label: string | null; geometry: LayoutGeometry }
> = {
  STAGE: {
    label: 'Stage',
    geometry: { x: 100, y: 40, width: 400, height: 80, rotationDeg: 0 },
  },
  AISLE: {
    label: null,
    geometry: { x: 100, y: 160, width: 300, height: 40, rotationDeg: 0 },
  },
  LABEL: {
    label: 'Label',
    geometry: { x: 100, y: 240, width: 200, height: 44, rotationDeg: 0 },
  },
  BARRIER: {
    label: null,
    geometry: { x: 100, y: 320, width: 300, height: 20, rotationDeg: 0 },
  },
  DECORATION: {
    label: null,
    geometry: { x: 100, y: 380, width: 100, height: 100, rotationDeg: 0 },
  },
};

@Component({
  selector: 'app-layout-element-palette',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './layout-element-palette.component.html',
  styleUrl: './layout-element-palette.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LayoutElementPaletteComponent {
  readonly existingElements = input<readonly VenueLayoutElement[]>([]);
  readonly existingSections = input<readonly VenueSectionLayout[]>([]);
  readonly defaultZIndex = input<number | null>(null);

  readonly elementCreated = output<VenueLayoutElement>();

  readonly validationError = signal<string | null>(null);

  readonly nextZIndex = computed<number>(() => {
    const override = this.defaultZIndex();
    if (override !== null && override !== undefined && Number.isFinite(override)) {
      return clampNumber(override, MIN_Z_INDEX, MAX_Z_INDEX);
    }
    let maxZ = -1;
    for (const el of this.existingElements() ?? []) {
      if (Number.isFinite(el?.zIndex) && el.zIndex > maxZ) {
        maxZ = el.zIndex;
      }
    }
    for (const s of this.existingSections() ?? []) {
      if (Number.isFinite(s?.zIndex) && s.zIndex > maxZ) {
        maxZ = s.zIndex;
      }
    }
    return clampNumber(maxZ + 1, MIN_Z_INDEX, MAX_Z_INDEX);
  });

  createDefaultElement(type: LayoutElementType): VenueLayoutElement {
    const config = DEFAULT_ELEMENT_CONFIGS[type];
    return {
      elementId: null,
      type,
      label: config.label,
      geometry: {
        x: clampNumber(config.geometry.x, MIN_POSITION, MAX_POSITION),
        y: clampNumber(config.geometry.y, MIN_POSITION, MAX_POSITION),
        width: clampDimension(config.geometry.width, MIN_DIMENSION, MAX_DIMENSION),
        height: clampDimension(config.geometry.height, MIN_DIMENSION, MAX_DIMENSION),
        rotationDeg: clampNumber(
          normalizeRotation(config.geometry.rotationDeg),
          MIN_ROTATION,
          MAX_ROTATION,
        ),
      },
      zIndex: this.nextZIndex(),
    };
  }

  onAddElement(type: LayoutElementType | string): void {
    if (!isValidLayoutElementType(type)) {
      this.validationError.set(`Unsupported layout element type: ${type}`);
      return;
    }
    this.validationError.set(null);
    const element = this.createDefaultElement(type);
    this.elementCreated.emit(element);
  }
}
