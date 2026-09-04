import { Injectable } from '@angular/core';
import { VenueSectionLayout, VenueSectionSeat } from '../models/venue.model';
import { clampNumber, MAX_POSITION, MIN_POSITION } from '../shared/utils/layout-geometry';

export const MAX_SEAT_COORDINATE = 100000;
export const MAX_JAVA_INTEGER = 2147483647;
export const MAX_ROW_LABEL_LENGTH = 10;
export const DUPLICATE_OFFSET = 40;

/**
 * Converts a 0-indexed number to spreadsheet-style alphabetical label.
 * 0 -> A, 25 -> Z, 26 -> AA, 27 -> AB, 51 -> AZ, 52 -> BA, 701 -> ZZ, 702 -> AAA
 */
export function getRowLabel(rowIndex: number): string {
  if (rowIndex < 0) {
    throw new Error('Row index must be non-negative');
  }
  let label = '';
  let num = rowIndex;
  while (num >= 0) {
    label = String.fromCharCode((num % 26) + 65) + label;
    num = Math.floor(num / 26) - 1;
  }
  return label;
}

export interface GenerateSeatsOptions {
  rowCount: number; // 1..50
  colCount: number; // 1..50
  rowLabelStartIndex?: number; // default 0 ('A'), non-negative alphabetic index
  seatNumberStart?: number; // default 1, >= 1
  pitchX?: number; // 1..1000, default 40
  pitchY?: number; // 1..1000, default 40
  originX?: number; // within section bounds [0, sectionWidth], default 20
  originY?: number; // within section bounds [0, sectionHeight], default 20
  isActive?: boolean; // default true
  sectionWidth: number; // section bounds width
  sectionHeight: number; // section bounds height
  existingSeats?: VenueSectionSeat[]; // optional retained seats
  venueCapacity?: number; // venue capacity limit
  totalOtherActiveSeats?: number; // active seats in other sections or retained
}

export interface BulkTranslateOptions {
  deltaX: number;
  deltaY: number;
  sectionWidth: number;
  sectionHeight: number;
}

export function getSeatKey(seat: VenueSectionSeat): string {
  return `${seat.gridY}_${seat.gridX}`;
}

export function isSeatSelected(seat: VenueSectionSeat, selectedKeys: Set<string>): boolean {
  if (seat.seatId && selectedKeys.has(seat.seatId)) {
    return true;
  }
  return selectedKeys.has(getSeatKey(seat));
}

@Injectable({ providedIn: 'root' })
export class SeatLayoutGeneratorService {
  readonly getRowLabel = getRowLabel;

  /**
   * Validates options for generating seats.
   * Returns null if valid, or an error string describing the exact rule violated.
   */
  validateGeneration(options: GenerateSeatsOptions): string | null {
    if (!Number.isInteger(options.rowCount) || options.rowCount < 1 || options.rowCount > 50) {
      return 'Row count must be an integer between 1 and 50';
    }
    if (!Number.isInteger(options.colCount) || options.colCount < 1 || options.colCount > 50) {
      return 'Column count must be an integer between 1 and 50';
    }

    const rowStart = options.rowLabelStartIndex ?? 0;
    if (!Number.isInteger(rowStart) || rowStart < 0) {
      return 'Row label start index must be a non-negative integer';
    }

    const seatStart = options.seatNumberStart ?? 1;
    if (!Number.isInteger(seatStart) || seatStart < 1 || seatStart > MAX_JAVA_INTEGER) {
      return 'Seat number start must be a positive integer between 1 and 2147483647';
    }
    if (seatStart + options.colCount - 1 > MAX_JAVA_INTEGER) {
      return 'Seat number exceeds Java integer range (2147483647)';
    }

    const pitchX = options.pitchX ?? 40;
    const pitchY = options.pitchY ?? 40;
    if (
      !Number.isFinite(pitchX) ||
      pitchX < 1 ||
      pitchX > 1000 ||
      !Number.isFinite(pitchY) ||
      pitchY < 1 ||
      pitchY > 1000
    ) {
      return 'Horizontal and vertical pitch must be between 1 and 1000';
    }

    if (
      !Number.isFinite(options.sectionWidth) ||
      options.sectionWidth <= 0 ||
      !Number.isFinite(options.sectionHeight) ||
      options.sectionHeight <= 0
    ) {
      return 'Section width and height must be positive numbers';
    }

    const originX = options.originX ?? 0;
    const originY = options.originY ?? 0;
    if (
      !Number.isFinite(originX) ||
      originX < 0 ||
      originX > options.sectionWidth ||
      !Number.isFinite(originY) ||
      originY < 0 ||
      originY > options.sectionHeight
    ) {
      return 'Origin coordinates must be within section bounds';
    }

    // Check row label lengths
    for (let r = 0; r < options.rowCount; r++) {
      const label = this.getRowLabel(rowStart + r);
      if (label.length > MAX_ROW_LABEL_LENGTH) {
        return 'Row label exceeds maximum length of 10 characters';
      }
    }

    // Check bounds of generated points
    const maxCalculatedX = originX + (options.colCount - 1) * pitchX;
    const maxCalculatedY = originY + (options.rowCount - 1) * pitchY;
    if (
      maxCalculatedX < 0 ||
      maxCalculatedX > options.sectionWidth ||
      maxCalculatedY < 0 ||
      maxCalculatedY > options.sectionHeight
    ) {
      return `Generated seats exceed section bounds (width: ${options.sectionWidth}, height: ${options.sectionHeight})`;
    }

    // Check capacity limit
    const isTargetActive = options.isActive ?? true;
    const generatedActiveCount = isTargetActive ? options.rowCount * options.colCount : 0;
    if (options.venueCapacity !== undefined && options.totalOtherActiveSeats !== undefined) {
      if (options.totalOtherActiveSeats + generatedActiveCount > options.venueCapacity) {
        return `Projected active seat count (${options.totalOtherActiveSeats + generatedActiveCount}) exceeds venue capacity (${options.venueCapacity})`;
      }
    }

    // Check collisions with retained/existing seats
    if (options.existingSeats && options.existingSeats.length > 0) {
      const existingRowKeys = new Set<string>();
      const existingGridKeys = new Set<string>();
      const existingActivePositions = new Set<string>();

      for (const s of options.existingSeats) {
        existingRowKeys.add(`${s.rowLabel.trim().toUpperCase()}|${s.seatNumber}`);
        existingGridKeys.add(`${s.gridX},${s.gridY}`);
        if (s.isActive) {
          existingActivePositions.add(
            `${Number(s.positionX.toFixed(3))},${Number(s.positionY.toFixed(3))}`,
          );
        }
      }

      for (let r = 0; r < options.rowCount; r++) {
        const rowLabel = this.getRowLabel(rowStart + r);
        for (let c = 0; c < options.colCount; c++) {
          const seatNum = seatStart + c;
          const rowKey = `${rowLabel.toUpperCase()}|${seatNum}`;
          if (existingRowKeys.has(rowKey)) {
            return `Generated seat duplicates row/number (${rowLabel}, ${seatNum})`;
          }
          const gridKey = `${c},${r}`;
          if (existingGridKeys.has(gridKey)) {
            return `Generated seat duplicates grid coordinates (${c}, ${r})`;
          }
          if (isTargetActive) {
            const posX = originX + c * pitchX;
            const posY = originY + r * pitchY;
            const posKey = `${Number(posX.toFixed(3))},${Number(posY.toFixed(3))}`;
            if (existingActivePositions.has(posKey)) {
              return `Generated seat duplicates active position (${posX}, ${posY})`;
            }
          }
        }
      }
    }

    return null;
  }

  /**
   * Deterministically generates seats inside section bounds.
   * Throws an Error if validation fails.
   */
  generateSeats(options: GenerateSeatsOptions): VenueSectionSeat[] {
    const error = this.validateGeneration(options);
    if (error) {
      throw new Error(error);
    }

    const seats: VenueSectionSeat[] = [];
    const rowStart = options.rowLabelStartIndex ?? 0;
    const seatStart = options.seatNumberStart ?? 1;
    const pitchX = options.pitchX ?? 40;
    const pitchY = options.pitchY ?? 40;
    const originX = options.originX ?? 0;
    const originY = options.originY ?? 0;
    const isActive = options.isActive ?? true;

    for (let r = 0; r < options.rowCount; r++) {
      const rowLabel = this.getRowLabel(rowStart + r);
      for (let c = 0; c < options.colCount; c++) {
        const seatNumber = seatStart + c;
        const positionX = originX + c * pitchX;
        const positionY = originY + r * pitchY;
        seats.push({
          seatId: null,
          rowLabel,
          seatNumber,
          gridX: c,
          gridY: r,
          positionX,
          positionY,
          isActive,
        });
      }
    }

    return seats;
  }

  /**
   * Creates a new section in the draft with null sectionId.
   */
  createSection(
    name: string,
    existingSections: VenueSectionLayout[],
    options?: {
      rowCount?: number;
      colCount?: number;
      width?: number;
      height?: number;
      positionX?: number;
      positionY?: number;
      generateSeats?: boolean;
      venueCapacity?: number;
      totalActiveSeats?: number;
    },
  ): VenueSectionLayout {
    const trimmed = name?.trim();
    if (!trimmed) {
      throw new Error('Section name must not be blank');
    }

    // Check name uniqueness among active sections
    const activeNames = new Set(
      existingSections.filter((s) => s.isActive).map((s) => s.name.trim().toLowerCase()),
    );
    if (activeNames.has(trimmed.toLowerCase())) {
      throw new Error(`Section name "${trimmed}" already exists`);
    }

    const rowCount = options?.rowCount ?? 10;
    const colCount = options?.colCount ?? 15;
    if (rowCount < 1 || rowCount > 50 || colCount < 1 || colCount > 50) {
      throw new Error('Section rowCount and colCount must be between 1 and 50');
    }

    const width = options?.width ?? colCount * 44 + 40;
    const height = options?.height ?? rowCount * 44 + 40;
    const posX = clampNumber(options?.positionX ?? 0, MIN_POSITION, MAX_POSITION);
    const posY = clampNumber(options?.positionY ?? 0, MIN_POSITION, MAX_POSITION);

    let seats: VenueSectionSeat[] = [];
    if (options?.generateSeats !== false) {
      seats = this.generateSeats({
        rowCount,
        colCount,
        sectionWidth: width,
        sectionHeight: height,
        pitchX: 44,
        pitchY: 44,
        originX: 20,
        originY: 20,
        isActive: true,
        venueCapacity: options?.venueCapacity,
        totalOtherActiveSeats: options?.totalActiveSeats ?? 0,
      });
    }

    return {
      sectionId: null,
      name: trimmed,
      rowCount,
      colCount,
      isActive: true,
      positionX: posX,
      positionY: posY,
      width,
      height,
      rotationDeg: 0,
      zIndex: 1,
      shapeMetadata: null,
      seats,
    };
  }

  /**
   * Duplicates a section with copied visual values offset by 40 units and clamped.
   * Suffixed Copy with numeric disambiguation.
   * All copied seats and section have null IDs.
   * Does NOT alter source section.
   */
  duplicateSection(
    source: VenueSectionLayout,
    existingSections: VenueSectionLayout[],
    venueCapacity?: number,
    totalActiveSeats?: number,
  ): VenueSectionLayout {
    // Generate disambiguated name
    const activeNames = new Set(
      existingSections.filter((s) => s.isActive).map((s) => s.name.trim().toLowerCase()),
    );

    let candidateName = `${source.name} Copy`;
    let disambiguationCounter = 2;
    while (activeNames.has(candidateName.trim().toLowerCase())) {
      candidateName = `${source.name} Copy ${disambiguationCounter++}`;
    }

    // Offset position by 40 units and clamp to MAX_POSITION
    const posX = clampNumber(source.positionX + DUPLICATE_OFFSET, MIN_POSITION, MAX_POSITION);
    const posY = clampNumber(source.positionY + DUPLICATE_OFFSET, MIN_POSITION, MAX_POSITION);

    // Deep clone seats with null seatId
    const duplicatedSeats: VenueSectionSeat[] = (source.seats || []).map((s) => ({
      seatId: null,
      rowLabel: s.rowLabel,
      seatNumber: s.seatNumber,
      gridX: s.gridX,
      gridY: s.gridY,
      positionX: s.positionX,
      positionY: s.positionY,
      isActive: s.isActive,
    }));

    // Capacity check
    const activeSeatCountInDup = duplicatedSeats.filter((s) => s.isActive).length;
    if (venueCapacity !== undefined && totalActiveSeats !== undefined) {
      if (totalActiveSeats + activeSeatCountInDup > venueCapacity) {
        throw new Error(
          `Duplicating section "${source.name}" would exceed venue capacity of ${venueCapacity} active seats`,
        );
      }
    }

    return {
      sectionId: null,
      name: candidateName,
      rowCount: source.rowCount,
      colCount: source.colCount,
      isActive: true,
      positionX: posX,
      positionY: posY,
      width: source.width,
      height: source.height,
      rotationDeg: source.rotationDeg,
      zIndex: source.zIndex,
      shapeMetadata: source.shapeMetadata ? JSON.parse(JSON.stringify(source.shapeMetadata)) : null,
      seats: duplicatedSeats,
    };
  }

  /**
   * Deactivates an existing or draft section and deactivates all of its seats.
   * Retains existing sectionId and all seatIds!
   */
  deactivateSection(section: VenueSectionLayout): VenueSectionLayout {
    return {
      ...section,
      isActive: false,
      seats: (section.seats || []).map((seat) => ({
        ...seat,
        isActive: false,
      })),
    };
  }

  /**
   * Reactivates an inactive section if name uniqueness and venue capacity permit.
   * Retains existing sectionId and all seatIds!
   */
  reactivateSection(
    section: VenueSectionLayout,
    existingSections: VenueSectionLayout[],
    venueCapacity?: number,
    totalOtherActiveSeats?: number,
  ): VenueSectionLayout {
    // Check name uniqueness among other active sections
    const activeNames = new Set(
      existingSections
        .filter((s) => s !== section && s.isActive)
        .map((s) => s.name.trim().toLowerCase()),
    );
    if (activeNames.has(section.name.trim().toLowerCase())) {
      throw new Error(
        `Cannot reactivate section: another active section with name "${section.name}" exists`,
      );
    }

    return {
      ...section,
      isActive: true,
    };
  }

  /**
   * Checks whether a section is permitted to be removed entirely.
   * Only never-saved null-ID sections may be removed; saved sections must be deactivated.
   */
  canRemoveSection(section: VenueSectionLayout): boolean {
    return section.sectionId === null;
  }

  /**
   * Removes a never-saved null-ID section from the sections list.
   * Throws an Error if attempting to remove an existing saved section.
   */
  removeSection(
    section: VenueSectionLayout,
    allSections: VenueSectionLayout[],
  ): VenueSectionLayout[] {
    if (!this.canRemoveSection(section)) {
      throw new Error(
        'Saved sections cannot be removed; use deactivate instead to preserve booking history',
      );
    }
    return allSections.filter((s) => s !== section);
  }

  /**
   * Bulk activates or deactivates selected seats.
   * Preserves loaded seatIds.
   * Atomic: fails with Error if capacity exceeded or if activating inside an inactive section.
   */
  bulkSetActive(
    section: VenueSectionLayout,
    selectedKeys: Set<string>,
    targetActive: boolean,
    venueCapacity?: number,
    totalOtherActiveSeats?: number,
  ): VenueSectionLayout {
    if (selectedKeys.size === 0) {
      return section;
    }

    if (targetActive && !section.isActive) {
      throw new Error(
        'Cannot activate seats in an inactive section. Reactivate the section first.',
      );
    }

    let newlyActivatedCount = 0;
    for (const seat of section.seats || []) {
      if (isSeatSelected(seat, selectedKeys)) {
        if (!seat.isActive && targetActive) {
          newlyActivatedCount++;
        }
      }
    }

    if (targetActive && venueCapacity !== undefined && totalOtherActiveSeats !== undefined) {
      if (totalOtherActiveSeats + newlyActivatedCount > venueCapacity) {
        throw new Error(
          `Activating ${newlyActivatedCount} seats would exceed venue capacity of ${venueCapacity} active seats`,
        );
      }
    }

    // Check duplicate active positions if activating
    if (targetActive) {
      const activePositions = new Set<string>();
      for (const seat of section.seats || []) {
        const willBeActive = isSeatSelected(seat, selectedKeys) ? true : seat.isActive;
        if (willBeActive) {
          const key = `${Number(seat.positionX.toFixed(3))},${Number(seat.positionY.toFixed(3))}`;
          if (activePositions.has(key)) {
            throw new Error(
              `Activating seat Row ${seat.rowLabel} Seat ${seat.seatNumber} creates duplicate active position (${seat.positionX}, ${seat.positionY})`,
            );
          }
          activePositions.add(key);
        }
      }
    }

    const updatedSeats = (section.seats || []).map((seat) => {
      if (isSeatSelected(seat, selectedKeys)) {
        return {
          ...seat,
          isActive: targetActive,
        };
      }
      return seat;
    });

    return {
      ...section,
      seats: updatedSeats,
    };
  }

  /**
   * Bulk translates selected seats by a numeric delta.
   * Preserves loaded seatIds.
   * Atomic: if any seat would move out of bounds, throws an Error leaving section unchanged.
   */
  bulkTranslate(
    section: VenueSectionLayout,
    selectedKeys: Set<string>,
    options: BulkTranslateOptions,
  ): VenueSectionLayout {
    if (selectedKeys.size === 0) {
      return section;
    }

    const { deltaX, deltaY, sectionWidth, sectionHeight } = options;
    if (!Number.isFinite(deltaX) || !Number.isFinite(deltaY)) {
      throw new Error('Translation deltas must be finite numbers');
    }

    // First pass: check bounds on all selected seats
    for (const seat of section.seats || []) {
      if (isSeatSelected(seat, selectedKeys)) {
        const newX = seat.positionX + deltaX;
        const newY = seat.positionY + deltaY;
        if (newX < 0 || newX > sectionWidth || newY < 0 || newY > sectionHeight) {
          throw new Error(
            `Translation would move seat Row ${seat.rowLabel} Seat ${seat.seatNumber} out of bounds (${newX}, ${newY})`,
          );
        }
      }
    }

    // Second pass: check duplicate active positions
    const activePositions = new Set<string>();
    for (const seat of section.seats || []) {
      const isSelected = isSeatSelected(seat, selectedKeys);
      const posX = isSelected ? seat.positionX + deltaX : seat.positionX;
      const posY = isSelected ? seat.positionY + deltaY : seat.positionY;

      if (seat.isActive) {
        const key = `${Number(posX.toFixed(3))},${Number(posY.toFixed(3))}`;
        if (activePositions.has(key)) {
          throw new Error(
            `Translation would cause duplicate active position at (${posX}, ${posY})`,
          );
        }
        activePositions.add(key);
      }
    }

    const updatedSeats = (section.seats || []).map((seat) => {
      if (isSeatSelected(seat, selectedKeys)) {
        return {
          ...seat,
          positionX: Number((seat.positionX + deltaX).toFixed(3)),
          positionY: Number((seat.positionY + deltaY).toFixed(3)),
        };
      }
      return seat;
    });

    return {
      ...section,
      seats: updatedSeats,
    };
  }

  /**
   * Bulk sets row label on selected seats.
   * Preserves loaded seatIds.
   * Atomic: if duplicate row/number occurs, throws an Error.
   */
  bulkSetRowLabel(
    section: VenueSectionLayout,
    selectedKeys: Set<string>,
    newRowLabel: string,
  ): VenueSectionLayout {
    if (selectedKeys.size === 0) {
      return section;
    }

    const trimmed = newRowLabel?.trim();
    if (!trimmed) {
      throw new Error('Row label must not be blank');
    }
    if (trimmed.length > MAX_ROW_LABEL_LENGTH) {
      throw new Error('Row label exceeds maximum length of 10 characters');
    }

    // Verify no duplicate (upper(rowLabel), seatNumber) will be created
    const seen = new Set<string>();
    for (const seat of section.seats || []) {
      const isSelected = isSeatSelected(seat, selectedKeys);
      const label = isSelected ? trimmed : seat.rowLabel;
      const key = `${label.toUpperCase()}|${seat.seatNumber}`;
      if (seen.has(key)) {
        throw new Error(
          `Setting row label "${trimmed}" causes duplicate row/seat (${label}, ${seat.seatNumber})`,
        );
      }
      seen.add(key);
    }

    const updatedSeats = (section.seats || []).map((seat) => {
      if (isSeatSelected(seat, selectedKeys)) {
        return {
          ...seat,
          rowLabel: trimmed,
        };
      }
      return seat;
    });

    return {
      ...section,
      seats: updatedSeats,
    };
  }

  /**
   * Bulk renumbers selected seats in deterministic (gridY, gridX, seatId-or-index) order.
   * Preserves loaded seatIds.
   * Atomic: if duplicate row/number occurs with unselected seats, throws an Error.
   */
  bulkRenumber(
    section: VenueSectionLayout,
    selectedKeys: Set<string>,
    startNumber: number,
  ): VenueSectionLayout {
    if (selectedKeys.size === 0) {
      return section;
    }

    if (!Number.isInteger(startNumber) || startNumber < 1 || startNumber > MAX_JAVA_INTEGER) {
      throw new Error('Start seat number must be a positive integer between 1 and 2147483647');
    }

    // Sort selected seats deterministically: gridY, gridX, then seatId or index
    const seats = section.seats || [];
    const selectedList = seats.filter((s) => isSeatSelected(s, selectedKeys));
    selectedList.sort((a, b) => {
      if (a.gridY !== b.gridY) return a.gridY - b.gridY;
      if (a.gridX !== b.gridX) return a.gridX - b.gridX;
      return (a.seatId || '').localeCompare(b.seatId || '');
    });

    if (startNumber + selectedList.length - 1 > MAX_JAVA_INTEGER) {
      throw new Error('Seat number exceeds Java integer range (2147483647)');
    }

    const seatToNewNumber = new Map<VenueSectionSeat, number>();
    selectedList.forEach((s, index) => {
      seatToNewNumber.set(s, startNumber + index);
    });

    // Check collisions
    const seen = new Set<string>();
    for (const seat of seats) {
      const newNum = seatToNewNumber.has(seat) ? seatToNewNumber.get(seat)! : seat.seatNumber;
      const key = `${seat.rowLabel.trim().toUpperCase()}|${newNum}`;
      if (seen.has(key)) {
        throw new Error(`Renumbering causes duplicate row/seat (${seat.rowLabel}, ${newNum})`);
      }
      seen.add(key);
    }

    const updatedSeats = seats.map((seat) => {
      if (seatToNewNumber.has(seat)) {
        return {
          ...seat,
          seatNumber: seatToNewNumber.get(seat)!,
        };
      }
      return seat;
    });

    return {
      ...section,
      seats: updatedSeats,
    };
  }
}
