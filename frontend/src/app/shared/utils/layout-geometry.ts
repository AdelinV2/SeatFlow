import { VenueLayoutElement, VenueSectionLayout } from '../../models/venue.model';

export const MIN_POSITION = 0;
export const MAX_POSITION = 100000;
export const MIN_DIMENSION = 0.001;
export const MAX_DIMENSION = 100000;
export const MIN_ROTATION = -180;
export const MAX_ROTATION = 180;
export const MIN_Z_INDEX = -1000;
export const MAX_Z_INDEX = 1000;
export const MIN_ZOOM = 0.25;
export const MAX_ZOOM = 4.0;

export interface Point {
  x: number;
  y: number;
}

export interface ContainerOrigin {
  left: number;
  top: number;
}

export interface SectionTransform {
  positionX: number;
  positionY: number;
  width: number;
  height: number;
  rotationDeg: number;
  zIndex?: number;
}

export interface LayoutBounds {
  minX: number;
  minY: number;
  maxX: number;
  maxY: number;
  width: number;
  height: number;
}

export const DEFAULT_LAYOUT_BOUNDS: LayoutBounds = {
  minX: 0,
  minY: 0,
  maxX: 1000,
  maxY: 800,
  width: 1000,
  height: 800,
};

export type CornerHandle = 'nw' | 'ne' | 'se' | 'sw';

/**
 * Visual seat radius rendered by the shared section node (seat-circle r).
 * Used only to balance seat content inside the section box; seat data
 * (positionX/positionY/gridX/gridY) is never rewritten.
 */
export const SEAT_VISUAL_RADIUS = 13;

/** Standard 8-color theme palette for venue sections and pricing tiers. */
export const SECTION_PALETTE: readonly string[] = [
  '#6366f1', // Royal Indigo
  '#f97316', // Sunset Coral / Orange
  '#059669', // Jewel Emerald
  '#8b5cf6', // Deep Violet
  '#0ea5e9', // Ocean Cyan
  '#f43f5e', // Ruby Rose
  '#f59e0b', // Amber Gold
  '#d946ef', // Fuchsia Pink
];

/**
 * Resolves the visual color of a section from its shapeMetadata.color,
 * falling back deterministically to SECTION_PALETTE based on index.
 */
export function resolveSectionColor(
  sectionOrMeta: { shapeMetadata?: unknown } | Record<string, unknown> | null | undefined,
  fallbackIndex = 0,
): string {
  if (!sectionOrMeta) {
    return SECTION_PALETTE[Math.abs(fallbackIndex) % SECTION_PALETTE.length];
  }
  const meta = (
    typeof sectionOrMeta === 'object' && sectionOrMeta !== null && 'shapeMetadata' in sectionOrMeta
      ? (sectionOrMeta as { shapeMetadata?: unknown }).shapeMetadata
      : sectionOrMeta
  ) as Record<string, unknown> | null;
  if (meta && typeof meta === 'object' && typeof meta['color'] === 'string' && meta['color'].trim().length > 0) {
    return meta['color'].trim();
  }
  return SECTION_PALETTE[Math.abs(fallbackIndex) % SECTION_PALETTE.length];
}

/** Standard horizontal padding between section boundary and seat edges. */
export const SECTION_PADDING_X = 28;

/** Standard bottom padding between seat edges and section boundary. */
export const SECTION_PADDING_BOTTOM = 28;

/** Standard top padding reserved above seat edges, cleanly accommodating the section title (centered at y=14). */
export const SECTION_PADDING_TOP = 28;

/** Legacy alias for backwards compatibility with tests/code referencing SECTION_TITLE_BAND. */
export const SECTION_TITLE_BAND = SECTION_PADDING_TOP;

export interface SectionContentOffset {
  dx: number;
  dy: number;
}

export interface EffectiveSectionDimensions {
  width: number;
  height: number;
  contentMinX: number;
  contentMinY: number;
  contentW: number;
  contentH: number;
}

/**
 * Computes the effective dimensions of a section, ensuring the boundary box
 * symmetrically encloses all seats with balanced padding on all sides.
 */
export function computeEffectiveSectionDimensions(section: {
  width?: number;
  height?: number;
  seats?: readonly { positionX?: number; positionY?: number }[];
}): EffectiveSectionDimensions {
  const seats = section?.seats ?? [];
  const rawWidth = Number.isFinite(section?.width) ? (section.width as number) : 0;
  const rawHeight = Number.isFinite(section?.height) ? (section.height as number) : 0;

  if (seats.length === 0) {
    return {
      width: Math.max(MIN_DIMENSION, rawWidth),
      height: Math.max(MIN_DIMENSION, rawHeight),
      contentMinX: 0,
      contentMinY: 0,
      contentW: 0,
      contentH: 0,
    };
  }

  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  for (const seat of seats) {
    if (!Number.isFinite(seat?.positionX) || !Number.isFinite(seat?.positionY)) {
      continue;
    }
    minX = Math.min(minX, seat.positionX!);
    minY = Math.min(minY, seat.positionY!);
    maxX = Math.max(maxX, seat.positionX!);
    maxY = Math.max(maxY, seat.positionY!);
  }

  if (!Number.isFinite(minX) || !Number.isFinite(minY)) {
    return {
      width: Math.max(MIN_DIMENSION, rawWidth),
      height: Math.max(MIN_DIMENSION, rawHeight),
      contentMinX: 0,
      contentMinY: 0,
      contentW: 0,
      contentH: 0,
    };
  }

  const pad = SEAT_VISUAL_RADIUS;
  const contentMinX = minX - pad;
  const contentMinY = minY - pad;
  const contentW = maxX - minX + pad * 2;
  const contentH = maxY - minY + pad * 2;

  const minRequiredW = contentW + SECTION_PADDING_X * 2;
  const minRequiredH = contentH + SECTION_PADDING_TOP + SECTION_PADDING_BOTTOM;

  const width = Math.max(rawWidth, minRequiredW);
  const height = Math.max(rawHeight, minRequiredH);

  return {
    width: Number(width.toFixed(3)),
    height: Number(height.toFixed(3)),
    contentMinX,
    contentMinY,
    contentW,
    contentH,
  };
}

/**
 * Computes the visual-only offset that balances seat content inside its
 * section box with symmetrical padding on all 4 sides.
 *
 * Horizontally centers seats so left and right margins are balanced.
 * Vertically balances seats between the title band at top and bottom margin.
 *
 * Data invariant: seat/grid coordinates, stable keys, rotation, and keyboard
 * navigation are untouched — consumers apply this as a transform on the
 * seats layer only. Empty seat lists yield a zero offset.
 */
export function sectionContentOffset(section: {
  width?: number;
  height?: number;
  seats?: readonly { positionX?: number; positionY?: number }[];
}): SectionContentOffset {
  const dims = computeEffectiveSectionDimensions(section);
  if (dims.contentW === 0 || dims.contentH === 0) {
    return { dx: 0, dy: 0 };
  }

  const dx = (dims.width - dims.contentW) / 2 - dims.contentMinX;
  const verticalSlack = Math.max(
    0,
    dims.height - SECTION_PADDING_TOP - SECTION_PADDING_BOTTOM - dims.contentH,
  );
  const dy = SECTION_PADDING_TOP + verticalSlack / 2 - dims.contentMinY;

  return {
    dx: Number(dx.toFixed(3)),
    dy: Number(dy.toFixed(3)),
  };
}

export interface ResizeResult {
  positionX: number;
  positionY: number;
  width: number;
  height: number;
}

export interface SortedCanvasSectionItem {
  kind: 'section';
  data: VenueSectionLayout;
  zIndex: number;
  stableKey: string;
}

export interface SortedCanvasElementItem {
  kind: 'element';
  data: VenueLayoutElement;
  zIndex: number;
  stableKey: string;
}

export type SortedCanvasItem = SortedCanvasSectionItem | SortedCanvasElementItem;

/**
 * Converts a client screen coordinate (e.g. from PointerEvent) to world canvas coordinate
 * given container position, pan offsets, and zoom scale.
 * Formula: world = (client - containerOrigin - pan) / zoom
 */
export function clientPointToWorld(
  clientPoint: Point,
  containerOrigin: ContainerOrigin,
  pan: Point,
  zoom: number,
): Point {
  const effectiveZoom = !Number.isFinite(zoom) || zoom <= 0 ? 1 : zoom;
  const originLeft = Number.isFinite(containerOrigin?.left) ? containerOrigin.left : 0;
  const originTop = Number.isFinite(containerOrigin?.top) ? containerOrigin.top : 0;
  const panX = Number.isFinite(pan?.x) ? pan.x : 0;
  const panY = Number.isFinite(pan?.y) ? pan.y : 0;

  return {
    x: (clientPoint.x - originLeft - panX) / effectiveZoom,
    y: (clientPoint.y - originTop - panY) / effectiveZoom,
  };
}

/**
 * Converts a client delta (difference between two client coordinates) to world delta.
 * Formula: deltaWorld = deltaClient / zoom
 */
export function clientDeltaToWorld(clientDelta: Point, zoom: number): Point {
  const effectiveZoom = !Number.isFinite(zoom) || zoom <= 0 ? 1 : zoom;
  return {
    x: clientDelta.x / effectiveZoom,
    y: clientDelta.y / effectiveZoom,
  };
}

/**
 * Normalizes rotation angle continuously into the [-180, 180] degree contract range.
 */
export function normalizeRotation(deg: number): number {
  if (!Number.isFinite(deg)) {
    return 0;
  }
  let angle = deg % 360;
  if (angle > 180) {
    angle -= 360;
  } else if (angle < -180) {
    angle += 360;
  }
  return Object.is(angle, -0) ? 0 : Number(angle.toFixed(4));
}

/**
 * Clamps a generic number within [min, max] inclusive.
 */
export function clampNumber(val: number, min: number, max: number, fallback = min): number {
  if (!Number.isFinite(val)) {
    return fallback;
  }
  return Math.min(max, Math.max(min, val));
}

/**
 * Clamps width or height to (minDimension, MAX_DIMENSION] ensuring strictly > 0.
 */
export function clampDimension(val: number, min = MIN_DIMENSION, max = MAX_DIMENSION): number {
  if (!Number.isFinite(val) || val < min) {
    return min;
  }
  return Math.min(max, Math.max(min, val));
}

/**
 * Clamps viewport zoom between 0.25 and 4.0; falls back to 1.0 on invalid input.
 */
export function clampZoom(zoom: number): number {
  if (!Number.isFinite(zoom) || zoom <= 0) {
    return 1.0;
  }
  return Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, zoom));
}

/**
 * Clamps section transform attributes to strict TASK-P11-003 bounds:
 * positionX/Y: [0, 100000]
 * width/height: [0.001, 100000] (strictly > 0)
 * rotationDeg: [-180, 180]
 * zIndex: [-1000, 1000]
 */
export function clampSectionTransform(
  transform: SectionTransform,
  minDimension = MIN_DIMENSION,
): SectionTransform {
  const positionX = clampNumber(transform.positionX, MIN_POSITION, MAX_POSITION, 0);
  const positionY = clampNumber(transform.positionY, MIN_POSITION, MAX_POSITION, 0);
  const width = clampDimension(transform.width, minDimension, MAX_DIMENSION);
  const height = clampDimension(transform.height, minDimension, MAX_DIMENSION);
  const rotationDeg = clampNumber(
    normalizeRotation(transform.rotationDeg),
    MIN_ROTATION,
    MAX_ROTATION,
    0,
  );
  const zIndex =
    transform.zIndex !== undefined
      ? clampNumber(transform.zIndex, MIN_Z_INDEX, MAX_Z_INDEX, 0)
      : undefined;

  return {
    positionX,
    positionY,
    width,
    height,
    rotationDeg,
    ...(zIndex !== undefined ? { zIndex } : {}),
  };
}

/**
 * Snaps a coordinate or dimension value to the given grid step.
 * Step <= 0 or invalid disables snapping and preserves value as-is.
 */
export function snap(value: number, step: number): number {
  if (!Number.isFinite(value)) {
    return 0;
  }
  if (!Number.isFinite(step) || step <= 0) {
    return value;
  }
  const snapped = Math.round(value / step) * step;
  return Number(snapped.toFixed(6));
}

/**
 * Computes the axis-aligned bounding box enclosing all sections and elements.
 * For rotated items, computes true rotated corner bounds.
 * Returns DEFAULT_LAYOUT_BOUNDS if layout is empty.
 */
export function layoutBounds(
  sections: readonly VenueSectionLayout[],
  elements: readonly VenueLayoutElement[] = [],
): LayoutBounds {
  const hasSections = sections && sections.length > 0;
  const hasElements = elements && elements.length > 0;

  if (!hasSections && !hasElements) {
    return { ...DEFAULT_LAYOUT_BOUNDS };
  }

  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;

  const includeItem = (x: number, y: number, w: number, h: number, rotationDeg: number): void => {
    if (!Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(w) || !Number.isFinite(h)) {
      return;
    }
    const safeW = Math.max(MIN_DIMENSION, w);
    const safeH = Math.max(MIN_DIMENSION, h);
    const safeRot = Number.isFinite(rotationDeg) ? rotationDeg : 0;

    if (safeRot === 0) {
      minX = Math.min(minX, x);
      minY = Math.min(minY, y);
      maxX = Math.max(maxX, x + safeW);
      maxY = Math.max(maxY, y + safeH);
      return;
    }

    const cx = x + safeW / 2;
    const cy = y + safeH / 2;
    const rad = (safeRot * Math.PI) / 180;
    const cos = Math.cos(rad);
    const sin = Math.sin(rad);

    const halfW = safeW / 2;
    const halfH = safeH / 2;

    const corners: [number, number][] = [
      [-halfW, -halfH],
      [halfW, -halfH],
      [halfW, halfH],
      [-halfW, halfH],
    ];

    for (const [dx, dy] of corners) {
      const rx = cx + dx * cos - dy * sin;
      const ry = cy + dx * sin + dy * cos;
      minX = Math.min(minX, rx);
      minY = Math.min(minY, ry);
      maxX = Math.max(maxX, rx);
      maxY = Math.max(maxY, ry);
    }
  };

  if (hasSections) {
    for (const sec of sections) {
      const dims = computeEffectiveSectionDimensions(sec);
      includeItem(sec.positionX, sec.positionY, dims.width, dims.height, sec.rotationDeg);
    }
  }

  if (hasElements) {
    for (const elem of elements) {
      includeItem(
        elem.geometry.x,
        elem.geometry.y,
        elem.geometry.width,
        elem.geometry.height,
        elem.geometry.rotationDeg,
      );
    }
  }

  if (!Number.isFinite(minX) || !Number.isFinite(minY)) {
    return { ...DEFAULT_LAYOUT_BOUNDS };
  }

  const width = Math.max(MIN_DIMENSION, maxX - minX);
  const height = Math.max(MIN_DIMENSION, maxY - minY);

  return {
    minX: Number(minX.toFixed(3)),
    minY: Number(minY.toFixed(3)),
    maxX: Number(maxX.toFixed(3)),
    maxY: Number(maxY.toFixed(3)),
    width: Number(width.toFixed(3)),
    height: Number(height.toFixed(3)),
  };
}

/**
 * Returns canvas items sorted ascending by zIndex with deterministic stable tie-breaking.
 */
export function sortedLayoutItems(
  sections: readonly VenueSectionLayout[],
  elements: readonly VenueLayoutElement[],
): SortedCanvasItem[];
export function sortedLayoutItems<T extends { zIndex: number }>(items: readonly T[]): T[];
export function sortedLayoutItems(first: readonly any[], second?: readonly any[]): any[] {
  if (second !== undefined) {
    const combined: SortedCanvasItem[] = [];
    const sections = (first ?? []) as readonly VenueSectionLayout[];
    const elements = (second ?? []) as readonly VenueLayoutElement[];

    sections.forEach((s, idx) => {
      combined.push({
        kind: 'section',
        data: s,
        zIndex: Number.isFinite(s?.zIndex) ? s.zIndex : 0,
        // Zero-padded fallback index keeps lexicographic comparison in numeric
        // order for null-ID drafts (idx-2 < idx-10). See REV-003.
        stableKey: `sec-${s?.sectionId ?? 'idx-' + String(idx).padStart(10, '0')}`,
      });
    });

    elements.forEach((e, idx) => {
      combined.push({
        kind: 'element',
        data: e,
        zIndex: Number.isFinite(e?.zIndex) ? e.zIndex : 0,
        stableKey: `elem-${e?.elementId ?? 'idx-' + String(idx).padStart(10, '0')}`,
      });
    });

    return combined.slice().sort((a, b) => {
      if (a.zIndex !== b.zIndex) {
        return a.zIndex - b.zIndex;
      }
      return a.stableKey.localeCompare(b.stableKey);
    });
  }

  const items = first ?? [];
  return items
    .map((item, idx) => ({
      item,
      idx,
      zIndex: Number.isFinite(item?.zIndex) ? item.zIndex : 0,
      id: item?.id ?? item?.sectionId ?? item?.elementId ?? null,
    }))
    .sort((a, b) => {
      if (a.zIndex !== b.zIndex) {
        return a.zIndex - b.zIndex;
      }
      if (a.id != null && b.id != null && a.id !== b.id) {
        return String(a.id).localeCompare(String(b.id));
      }
      return a.idx - b.idx;
    })
    .map((wrapper) => wrapper.item);
}

/**
 * Converts a section-local coordinate to world coordinate using the section's transform:
 * translate(positionX positionY) rotate(rotationDeg width/2 height/2).
 */
export function sectionLocalToWorld(localPoint: Point, section: SectionTransform): Point {
  const rot = Number.isFinite(section.rotationDeg) ? section.rotationDeg : 0;
  const w = Number.isFinite(section.width) ? section.width : 0;
  const h = Number.isFinite(section.height) ? section.height : 0;
  const px = Number.isFinite(section.positionX) ? section.positionX : 0;
  const py = Number.isFinite(section.positionY) ? section.positionY : 0;

  if (rot === 0) {
    return {
      x: Number((px + localPoint.x).toFixed(3)),
      y: Number((py + localPoint.y).toFixed(3)),
    };
  }

  const cx = w / 2;
  const cy = h / 2;
  const rad = (rot * Math.PI) / 180;
  const cos = Math.cos(rad);
  const sin = Math.sin(rad);

  const dx = localPoint.x - cx;
  const dy = localPoint.y - cy;
  const rx = dx * cos - dy * sin;
  const ry = dx * sin + dy * cos;

  return {
    x: Number((px + cx + rx).toFixed(3)),
    y: Number((py + cy + ry).toFixed(3)),
  };
}

/**
 * Calculates new position and dimensions when dragging a corner handle ('nw', 'ne', 'se', 'sw')
 * taking into account section rotation, grid snap, and min/max bounds.
 * Preserves the exact world coordinate of the nominally fixed opposite corner for all handles and rotations.
 */
export function calculateCornerResize(
  initial: SectionTransform,
  handle: CornerHandle,
  worldDelta: Point,
  snapStep = 0,
  minDimension = MIN_DIMENSION,
): ResizeResult {
  const rot = Number.isFinite(initial.rotationDeg) ? initial.rotationDeg : 0;
  const rad = (rot * Math.PI) / 180;
  const cos = Math.cos(rad);
  const sin = Math.sin(rad);

  // Convert world delta to section-local delta by rotating by -rot
  const localDx = worldDelta.x * cos + worldDelta.y * sin;
  const localDy = -worldDelta.x * sin + worldDelta.y * cos;

  let oldOppositeLocal: Point;
  let newW = initial.width;
  let newH = initial.height;

  switch (handle) {
    case 'se': {
      oldOppositeLocal = { x: 0, y: 0 };
      newW = clampDimension(snap(initial.width + localDx, snapStep), minDimension, MAX_DIMENSION);
      newH = clampDimension(snap(initial.height + localDy, snapStep), minDimension, MAX_DIMENSION);
      break;
    }
    case 'sw': {
      oldOppositeLocal = { x: initial.width, y: 0 };
      newW = clampDimension(snap(initial.width - localDx, snapStep), minDimension, MAX_DIMENSION);
      newH = clampDimension(snap(initial.height + localDy, snapStep), minDimension, MAX_DIMENSION);
      break;
    }
    case 'ne': {
      oldOppositeLocal = { x: 0, y: initial.height };
      newW = clampDimension(snap(initial.width + localDx, snapStep), minDimension, MAX_DIMENSION);
      newH = clampDimension(snap(initial.height - localDy, snapStep), minDimension, MAX_DIMENSION);
      break;
    }
    case 'nw': {
      oldOppositeLocal = { x: initial.width, y: initial.height };
      newW = clampDimension(snap(initial.width - localDx, snapStep), minDimension, MAX_DIMENSION);
      newH = clampDimension(snap(initial.height - localDy, snapStep), minDimension, MAX_DIMENSION);
      break;
    }
  }

  // Exact world coordinate of opposite corner in initial transform
  const oldCx = initial.width / 2;
  const oldCy = initial.height / 2;
  const oldDx = oldOppositeLocal.x - oldCx;
  const oldDy = oldOppositeLocal.y - oldCy;
  const oldRotX = oldDx * cos - oldDy * sin;
  const oldRotY = oldDx * sin + oldDy * cos;
  const fixedWorldX = initial.positionX + oldCx + oldRotX;
  const fixedWorldY = initial.positionY + oldCy + oldRotY;

  // Local coordinate of opposite corner in the resized section
  let newOppositeLocal: Point;
  switch (handle) {
    case 'se':
      newOppositeLocal = { x: 0, y: 0 };
      break;
    case 'sw':
      newOppositeLocal = { x: newW, y: 0 };
      break;
    case 'ne':
      newOppositeLocal = { x: 0, y: newH };
      break;
    case 'nw':
      newOppositeLocal = { x: newW, y: newH };
      break;
  }

  // Derive new section origin (positionX, positionY) anchoring the fixed opposite corner
  const newCx = newW / 2;
  const newCy = newH / 2;
  const newDx = newOppositeLocal.x - newCx;
  const newDy = newOppositeLocal.y - newCy;
  const newRotX = newDx * cos - newDy * sin;
  const newRotY = newDx * sin + newDy * cos;

  const rawNewPosX = fixedWorldX - (newCx + newRotX);
  const rawNewPosY = fixedWorldY - (newCy + newRotY);

  const positionX = clampNumber(Number(rawNewPosX.toFixed(3)), MIN_POSITION, MAX_POSITION);
  const positionY = clampNumber(Number(rawNewPosY.toFixed(3)), MIN_POSITION, MAX_POSITION);

  return {
    positionX,
    positionY,
    width: Number(newW.toFixed(3)),
    height: Number(newH.toFixed(3)),
  };
}

/**
 * Calculates normalized rotation angle when dragging the rotation handle.
 */
export function calculateRotation(
  section: SectionTransform,
  currentWorldPoint: Point,
  snapStep = 0,
): number {
  const centerX = section.positionX + section.width / 2;
  const centerY = section.positionY + section.height / 2;
  const dx = currentWorldPoint.x - centerX;
  const dy = currentWorldPoint.y - centerY;
  const angleRad = Math.atan2(dy, dx);
  // Pointer angle + 90 deg because rotation handle sits at top of section (along -Y)
  const deg = (angleRad * 180) / Math.PI + 90;
  let normalized = normalizeRotation(deg);
  if (snapStep > 0) {
    normalized = normalizeRotation(snap(normalized, snapStep));
  }
  return normalized;
}
