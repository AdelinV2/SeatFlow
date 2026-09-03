import { computed, inject, Injectable, Signal, signal } from '@angular/core';
import { Observable, throwError } from 'rxjs';
import { tap } from 'rxjs/operators';
import {
  DeepReadonly,
  LayoutElementType,
  SaveVenueLayoutRequest,
  VenueLayout,
  VenueLayoutElement,
  VenueSectionLayout,
  VenueSectionSeat,
} from '../models/venue.model';
import { AdminVenueApiService } from './admin-venue-api.service';

const LAYOUT_ELEMENT_TYPES: ReadonlySet<string> = new Set([
  'STAGE',
  'AISLE',
  'LABEL',
  'BARRIER',
  'DECORATION',
]);

function deepClone<T>(value: T): T {
  if (value === null || value === undefined) {
    return value;
  }
  if (typeof structuredClone === 'function') {
    return structuredClone(value);
  }
  return JSON.parse(JSON.stringify(value));
}

function deepFreeze<T>(value: T): DeepReadonly<T> {
  if (value === null || typeof value !== 'object') {
    return value as DeepReadonly<T>;
  }
  if (Object.isFrozen(value)) {
    return value as DeepReadonly<T>;
  }
  if (Array.isArray(value)) {
    for (const item of value) {
      deepFreeze(item);
    }
  } else {
    for (const key of Object.keys(value)) {
      deepFreeze((value as Record<string, unknown>)[key]);
    }
  }
  return Object.freeze(value) as DeepReadonly<T>;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function invalid(path: string, detail: string): Error {
  return new Error(
    `Invalid venue layout snapshot: ${path} ${detail}; refusing to overwrite baseline or build a save request`,
  );
}

function assertNonBlankString(value: unknown, path: string): asserts value is string {
  if (typeof value !== 'string' || value.trim().length === 0) {
    if (value === undefined) {
      throw invalid(path, 'is required (expected non-empty string, got undefined)');
    }
    throw invalid(
      path,
      `must be a non-empty string (got ${JSON.stringify(value) ?? typeof value})`,
    );
  }
}

function assertFiniteNumber(value: unknown, path: string): asserts value is number {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    if (value === undefined) {
      throw invalid(path, 'is required (expected finite number, got undefined)');
    }
    throw invalid(path, `must be a finite number (got ${JSON.stringify(value) ?? typeof value})`);
  }
}

function assertNonNegativeInt(value: unknown, path: string): asserts value is number {
  if (typeof value !== 'number' || !Number.isInteger(value) || value < 0) {
    if (value === undefined) {
      throw invalid(path, 'is required (expected non-negative integer, got undefined)');
    }
    throw invalid(
      path,
      `must be a non-negative integer (got ${JSON.stringify(value) ?? typeof value})`,
    );
  }
}

function assertPositiveInt(value: unknown, path: string): asserts value is number {
  if (typeof value !== 'number' || !Number.isInteger(value) || value < 1) {
    if (value === undefined) {
      throw invalid(path, 'is required (expected positive integer, got undefined)');
    }
    throw invalid(
      path,
      `must be a positive integer (got ${JSON.stringify(value) ?? typeof value})`,
    );
  }
}

function assertBoolean(value: unknown, path: string): asserts value is boolean {
  if (typeof value !== 'boolean') {
    if (value === undefined) {
      throw invalid(path, 'is required (expected boolean, got undefined)');
    }
    throw invalid(path, `must be a boolean (got ${JSON.stringify(value) ?? typeof value})`);
  }
}

function assertId(value: unknown, path: string): asserts value is string | null {
  if (value === null) {
    return;
  }
  if (typeof value === 'string' && value.length > 0) {
    return;
  }
  if (value === undefined) {
    throw invalid(
      path,
      'is required (expected string | null, got undefined); null means create, undefined is malformed',
    );
  }
  throw invalid(path, `must be string | null (got ${JSON.stringify(value) ?? typeof value})`);
}

function assertLabel(value: unknown, path: string): asserts value is string | null {
  if (value === null) {
    return;
  }
  if (typeof value === 'string') {
    return;
  }
  if (value === undefined) {
    throw invalid(path, 'is required (expected string | null, got undefined)');
  }
  throw invalid(path, `must be string | null (got ${JSON.stringify(value) ?? typeof value})`);
}

function assertArray(value: unknown, path: string): asserts value is unknown[] {
  if (!Array.isArray(value)) {
    if (value === undefined) {
      throw invalid(path, 'is required (expected array, got undefined)');
    }
    throw invalid(path, `must be an array (got ${typeof value})`);
  }
}

function assertValidGeometry(value: unknown, path: string): void {
  if (!isPlainObject(value)) {
    if (value === undefined) {
      throw invalid(path, 'is required (expected geometry object, got undefined)');
    }
    throw invalid(
      path,
      `must be a geometry object (got ${value === null ? 'null' : typeof value})`,
    );
  }
  assertFiniteNumber(value['x'], `${path}.x`);
  assertFiniteNumber(value['y'], `${path}.y`);
  assertFiniteNumber(value['width'], `${path}.width`);
  assertFiniteNumber(value['height'], `${path}.height`);
  assertFiniteNumber(value['rotationDeg'], `${path}.rotationDeg`);
}

function assertValidElement(value: unknown, path: string): void {
  if (!isPlainObject(value)) {
    throw invalid(path, `must be an object (got ${value === null ? 'null' : typeof value})`);
  }
  assertId(value['elementId'], `${path}.elementId`);
  if (typeof value['type'] !== 'string' || !LAYOUT_ELEMENT_TYPES.has(value['type'])) {
    if (value['type'] === undefined) {
      throw invalid(
        `${path}.type`,
        'is required (expected STAGE | AISLE | LABEL | BARRIER | DECORATION, got undefined)',
      );
    }
    throw invalid(
      `${path}.type`,
      `must be STAGE | AISLE | LABEL | BARRIER | DECORATION (got ${JSON.stringify(value['type']) ?? typeof value['type']})`,
    );
  }
  assertLabel(value['label'], `${path}.label`);
  assertValidGeometry(value['geometry'], `${path}.geometry`);
  assertFiniteNumber(value['zIndex'], `${path}.zIndex`);
  if (!Number.isInteger(value['zIndex'])) {
    throw invalid(`${path}.zIndex`, `must be an integer (got ${JSON.stringify(value['zIndex'])})`);
  }
}

function assertValidSeat(value: unknown, path: string): void {
  if (!isPlainObject(value)) {
    throw invalid(path, `must be an object (got ${value === null ? 'null' : typeof value})`);
  }
  assertId(value['seatId'], `${path}.seatId`);
  assertNonBlankString(value['rowLabel'], `${path}.rowLabel`);
  assertPositiveInt(value['seatNumber'], `${path}.seatNumber`);
  assertNonNegativeInt(value['gridX'], `${path}.gridX`);
  assertNonNegativeInt(value['gridY'], `${path}.gridY`);
  assertFiniteNumber(value['positionX'], `${path}.positionX`);
  assertFiniteNumber(value['positionY'], `${path}.positionY`);
  assertBoolean(value['isActive'], `${path}.isActive`);
}

function assertValidSection(value: unknown, path: string): void {
  if (!isPlainObject(value)) {
    throw invalid(path, `must be an object (got ${value === null ? 'null' : typeof value})`);
  }
  assertId(value['sectionId'], `${path}.sectionId`);
  assertNonBlankString(value['name'], `${path}.name`);
  assertPositiveInt(value['rowCount'], `${path}.rowCount`);
  assertPositiveInt(value['colCount'], `${path}.colCount`);
  assertBoolean(value['isActive'], `${path}.isActive`);
  assertFiniteNumber(value['positionX'], `${path}.positionX`);
  assertFiniteNumber(value['positionY'], `${path}.positionY`);
  assertFiniteNumber(value['width'], `${path}.width`);
  assertFiniteNumber(value['height'], `${path}.height`);
  assertFiniteNumber(value['rotationDeg'], `${path}.rotationDeg`);
  assertFiniteNumber(value['zIndex'], `${path}.zIndex`);
  if (!Number.isInteger(value['zIndex'])) {
    throw invalid(`${path}.zIndex`, `must be an integer (got ${JSON.stringify(value['zIndex'])})`);
  }
  const shapeMetadata = value['shapeMetadata'];
  if (shapeMetadata === undefined) {
    throw invalid(`${path}.shapeMetadata`, 'is required (expected object | null, got undefined)');
  }
  if (shapeMetadata !== null && !isPlainObject(shapeMetadata)) {
    throw invalid(
      `${path}.shapeMetadata`,
      `must be object | null (got ${Array.isArray(shapeMetadata) ? 'array' : typeof shapeMetadata})`,
    );
  }
  assertArray(value['seats'], `${path}.seats`);
  (value['seats'] as unknown[]).forEach((seat, index) =>
    assertValidSeat(seat, `${path}.seats[${index}]`),
  );
}

function assertValidVenueLayout(value: unknown): asserts value is VenueLayout {
  if (!isPlainObject(value)) {
    throw invalid('layout', `must be an object (got ${value === null ? 'null' : typeof value})`);
  }
  assertNonBlankString(value['venueId'], 'venueId');
  assertNonBlankString(value['name'], 'name');
  assertFiniteNumber(value['capacity'], 'capacity');
  assertNonNegativeInt(value['totalConfiguredSeats'], 'totalConfiguredSeats');
  assertNonNegativeInt(value['layoutVersion'], 'layoutVersion');
  assertArray(value['sections'], 'sections');
  assertArray(value['elements'], 'elements');
  (value['sections'] as unknown[]).forEach((section, index) =>
    assertValidSection(section, `sections[${index}]`),
  );
  (value['elements'] as unknown[]).forEach((element, index) =>
    assertValidElement(element, `elements[${index}]`),
  );
}

function normalizeValue(value: unknown): unknown {
  if (value === null) {
    return null;
  }
  if (value === undefined) {
    return undefined;
  }
  if (typeof value === 'number' || typeof value === 'boolean' || typeof value === 'string') {
    return value;
  }
  if (Array.isArray(value)) {
    return value.map(normalizeValue);
  }
  if (typeof value === 'object') {
    const obj = value as Record<string, unknown>;
    const sortedKeys = Object.keys(obj).sort();
    const result: Record<string, unknown> = {};
    for (const key of sortedKeys) {
      result[key] = normalizeValue(obj[key]);
    }
    return result;
  }
  return value;
}

function toCanonicalLayoutJson(layout: VenueLayout | DeepReadonly<VenueLayout> | null): string {
  if (!layout) {
    return '';
  }
  const canonical = {
    elements: layout.elements.map((el) => ({
      elementId: el.elementId,
      geometry: {
        height: el.geometry.height,
        rotationDeg: el.geometry.rotationDeg,
        width: el.geometry.width,
        x: el.geometry.x,
        y: el.geometry.y,
      },
      label: el.label,
      type: el.type as LayoutElementType,
      zIndex: el.zIndex,
    })),
    sections: layout.sections.map((sec) => ({
      colCount: sec.colCount,
      height: sec.height,
      isActive: sec.isActive,
      name: sec.name,
      positionX: sec.positionX,
      positionY: sec.positionY,
      rotationDeg: sec.rotationDeg,
      rowCount: sec.rowCount,
      sectionId: sec.sectionId,
      shapeMetadata: sec.shapeMetadata
        ? normalizeValue(sec.shapeMetadata as Record<string, unknown>)
        : null,
      width: sec.width,
      zIndex: sec.zIndex,
      seats: sec.seats.map((st) => ({
        gridX: st.gridX,
        gridY: st.gridY,
        isActive: st.isActive,
        positionX: st.positionX,
        positionY: st.positionY,
        rowLabel: st.rowLabel,
        seatId: st.seatId,
        seatNumber: st.seatNumber,
      })),
    })),
  };
  return JSON.stringify(normalizeValue(canonical));
}

export type EditableVenueLayout = VenueLayout;
export type VenueLayoutSnapshot = DeepReadonly<VenueLayout>;

@Injectable({ providedIn: 'root' })
export class VenueLayoutEditorStateService {
  private readonly adminVenueApi = inject(AdminVenueApiService);

  private readonly _layout = signal<VenueLayoutSnapshot | null>(null);
  private readonly _baseline = signal<VenueLayoutSnapshot | null>(null);
  private readonly _isSaving = signal<boolean>(false);
  private readonly _loadError = signal<string | null>(null);

  readonly layout: Signal<VenueLayoutSnapshot | null> = this._layout.asReadonly();
  readonly baseline: Signal<VenueLayoutSnapshot | null> = this._baseline.asReadonly();
  readonly isSaving: Signal<boolean> = this._isSaving.asReadonly();
  readonly loadError: Signal<string | null> = this._loadError.asReadonly();

  readonly isDirty: Signal<boolean> = computed(() => {
    const current = this._layout();
    const base = this._baseline();
    if (!current && !base) {
      return false;
    }
    if (!current || !base) {
      return true;
    }
    return toCanonicalLayoutJson(current) !== toCanonicalLayoutJson(base);
  });

  load(venueId: string): Observable<VenueLayout> {
    this._loadError.set(null);
    return this.adminVenueApi.getEditableLayout(venueId).pipe(
      tap({
        next: (snapshot) => {
          try {
            this.applyServerSnapshot(snapshot);
          } catch (err) {
            const message = err instanceof Error ? err.message : 'Invalid venue layout response';
            this._loadError.set(message);
            throw err;
          }
        },
        error: (err: unknown) => {
          this._baseline.set(null);
          this._layout.set(null);
          const message =
            (err as { error?: { message?: string } })?.error?.message ||
            (err instanceof Error ? err.message : 'Failed to load venue layout');
          this._loadError.set(message);
        },
      }),
    );
  }

  replaceDraft(
    draftOrUpdater:
      | VenueLayout
      | VenueLayoutSnapshot
      | ((current: EditableVenueLayout) => VenueLayout | VenueLayoutSnapshot),
  ): void {
    if (typeof draftOrUpdater === 'function') {
      const current = this._layout();
      if (!current) {
        throw new Error('Cannot update draft: no layout currently loaded');
      }
      const editable = deepClone<VenueLayout>(current as VenueLayout);
      const updated = (draftOrUpdater as (current: EditableVenueLayout) => VenueLayout)(editable);
      const cloned = deepClone<VenueLayout>(updated as VenueLayout);
      assertValidVenueLayout(cloned);
      this._layout.set(deepFreeze<VenueLayout>(cloned));
    } else {
      const cloned = deepClone<VenueLayout>(draftOrUpdater as VenueLayout);
      assertValidVenueLayout(cloned);
      this._layout.set(deepFreeze<VenueLayout>(cloned));
    }
  }

  applyServerSnapshot(snapshot: VenueLayout | VenueLayoutSnapshot): void {
    assertValidVenueLayout(snapshot);
    const baselineClone = deepClone<VenueLayout>(snapshot as VenueLayout);
    const layoutClone = deepClone<VenueLayout>(snapshot as VenueLayout);
    this._baseline.set(deepFreeze<VenueLayout>(baselineClone));
    this._layout.set(deepFreeze<VenueLayout>(layoutClone));
    this._isSaving.set(false);
    this._loadError.set(null);
  }

  resetToBaseline(): void {
    const base = this._baseline();
    this._layout.set(
      base ? deepFreeze<VenueLayout>(deepClone<VenueLayout>(base as VenueLayout)) : null,
    );
  }

  buildSaveRequest(): SaveVenueLayoutRequest {
    const current = this._layout();
    if (!current) {
      throw new Error('Cannot build save request: no layout loaded');
    }
    assertValidVenueLayout(current);
    const editable = current as VenueLayout;
    const base = this._baseline();
    const rawVersion = base ? (base as VenueLayout).layoutVersion : editable.layoutVersion;
    if (typeof rawVersion !== 'number' || !Number.isInteger(rawVersion) || rawVersion < 0) {
      throw new Error(
        'Cannot build save request: layoutVersion is required (must be a non-negative integer); refusing to default it',
      );
    }

    const sections: VenueSectionLayout[] = editable.sections.map((section) => ({
      sectionId: section.sectionId,
      name: section.name,
      rowCount: section.rowCount,
      colCount: section.colCount,
      isActive: section.isActive,
      positionX: section.positionX,
      positionY: section.positionY,
      width: section.width,
      height: section.height,
      rotationDeg: section.rotationDeg,
      zIndex: section.zIndex,
      shapeMetadata: section.shapeMetadata === null ? null : deepClone(section.shapeMetadata),
      seats: section.seats.map((seat): VenueSectionSeat => ({
        seatId: seat.seatId,
        rowLabel: seat.rowLabel,
        seatNumber: seat.seatNumber,
        gridX: seat.gridX,
        gridY: seat.gridY,
        positionX: seat.positionX,
        positionY: seat.positionY,
        isActive: seat.isActive,
      })),
    }));

    const elements: VenueLayoutElement[] = editable.elements.map((element) => ({
      elementId: element.elementId,
      type: element.type,
      label: element.label,
      geometry: {
        x: element.geometry.x,
        y: element.geometry.y,
        width: element.geometry.width,
        height: element.geometry.height,
        rotationDeg: element.geometry.rotationDeg,
      },
      zIndex: element.zIndex,
    }));

    return {
      layoutVersion: rawVersion,
      sections,
      elements,
    };
  }

  save(venueId?: string): Observable<VenueLayout> {
    const targetVenueId = venueId || (this._layout() as VenueLayout | null)?.venueId;
    if (!targetVenueId) {
      return throwError(() => new Error('Cannot save layout: venueId is required'));
    }
    const request = this.buildSaveRequest();
    this._isSaving.set(true);
    return this.adminVenueApi.saveLayout(targetVenueId, request).pipe(
      tap({
        next: (savedLayout) => {
          try {
            this.applyServerSnapshot(savedLayout);
          } finally {
            this._isSaving.set(false);
          }
        },
        error: () => {
          this._isSaving.set(false);
        },
      }),
    );
  }

  validate(venueId?: string): Observable<void> {
    const targetVenueId = venueId || (this._layout() as VenueLayout | null)?.venueId;
    if (!targetVenueId) {
      return throwError(() => new Error('Cannot validate layout: venueId is required'));
    }
    const request = this.buildSaveRequest();
    return this.adminVenueApi.validateLayout(targetVenueId, request);
  }

  setIsSaving(saving: boolean): void {
    this._isSaving.set(saving);
  }

  setLoadError(error: string | null): void {
    this._loadError.set(error);
  }
}
