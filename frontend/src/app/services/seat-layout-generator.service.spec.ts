import { TestBed } from '@angular/core/testing';
import {
  getRowLabel,
  SeatLayoutGeneratorService,
  GenerateSeatsOptions,
  getSeatKey,
  isSeatSelected,
} from './seat-layout-generator.service';
import { VenueSectionLayout, VenueSectionSeat } from '../models/venue.model';
import { MAX_POSITION } from '../shared/utils/layout-geometry';

describe('SeatLayoutGeneratorService', () => {
  let service: SeatLayoutGeneratorService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(SeatLayoutGeneratorService);
  });

  describe('getRowLabel algorithm', () => {
    it('should correctly convert 0-indexed numbers to alphabetic row labels', () => {
      expect(getRowLabel(0)).toBe('A');
      expect(getRowLabel(1)).toBe('B');
      expect(getRowLabel(25)).toBe('Z');
      expect(getRowLabel(26)).toBe('AA');
      expect(getRowLabel(27)).toBe('AB');
      expect(getRowLabel(51)).toBe('AZ');
      expect(getRowLabel(52)).toBe('BA');
      expect(getRowLabel(701)).toBe('ZZ');
      expect(getRowLabel(702)).toBe('AAA');
    });

    it('should throw an error for negative row index', () => {
      expect(() => getRowLabel(-1)).toThrowError('Row index must be non-negative');
    });
  });

  describe('generateSeats', () => {
    const validBaseOptions: GenerateSeatsOptions = {
      rowCount: 5,
      colCount: 10,
      rowLabelStartIndex: 0,
      seatNumberStart: 1,
      pitchX: 40,
      pitchY: 40,
      originX: 20,
      originY: 20,
      isActive: true,
      sectionWidth: 500,
      sectionHeight: 300,
      venueCapacity: 100,
      totalOtherActiveSeats: 0,
    };

    it('should generate seats with seatId: null and correct coordinates', () => {
      const seats = service.generateSeats(validBaseOptions);
      expect(seats.length).toBe(50);
      expect(seats[0].seatId).toBeNull();
      expect(seats[0].rowLabel).toBe('A');
      expect(seats[0].seatNumber).toBe(1);
      expect(seats[0].gridX).toBe(0);
      expect(seats[0].gridY).toBe(0);
      expect(seats[0].positionX).toBe(20);
      expect(seats[0].positionY).toBe(20);
      expect(seats[0].isActive).toBeTrue();

      // Last seat: row 4 (E), col 9
      const last = seats[49];
      expect(last.seatId).toBeNull();
      expect(last.rowLabel).toBe('E');
      expect(last.seatNumber).toBe(10);
      expect(last.gridX).toBe(9);
      expect(last.gridY).toBe(4);
      expect(last.positionX).toBe(20 + 9 * 40); // 380
      expect(last.positionY).toBe(20 + 4 * 40); // 180
    });

    it('should succeed with maximum 50x50 dimensions when within capacity and bounds', () => {
      const maxOptions: GenerateSeatsOptions = {
        rowCount: 50,
        colCount: 50,
        rowLabelStartIndex: 0,
        seatNumberStart: 1,
        pitchX: 20,
        pitchY: 20,
        originX: 10,
        originY: 10,
        isActive: true,
        sectionWidth: 1050,
        sectionHeight: 1050,
        venueCapacity: 3000,
        totalOtherActiveSeats: 0,
      };

      const seats = service.generateSeats(maxOptions);
      expect(seats.length).toBe(2500);
      expect(seats[2499].rowLabel).toBe(getRowLabel(49)); // AX
      expect(seats[2499].seatNumber).toBe(50);
    });

    it('should reject when row count or col count is outside 1..50', () => {
      expect(() => service.generateSeats({ ...validBaseOptions, rowCount: 0 })).toThrowError(
        /Row count must be an integer between 1 and 50/,
      );

      expect(() => service.generateSeats({ ...validBaseOptions, rowCount: 51 })).toThrowError(
        /Row count must be an integer between 1 and 50/,
      );

      expect(() => service.generateSeats({ ...validBaseOptions, colCount: 0 })).toThrowError(
        /Column count must be an integer between 1 and 50/,
      );

      expect(() => service.generateSeats({ ...validBaseOptions, colCount: 51 })).toThrowError(
        /Column count must be an integer between 1 and 50/,
      );
    });

    it('should reject when generated points exceed section bounds', () => {
      expect(() =>
        service.generateSeats({
          ...validBaseOptions,
          sectionWidth: 200, // 20 + 9 * 40 = 380 > 200
          sectionHeight: 300,
        }),
      ).toThrowError(/Generated seats exceed section bounds/);

      expect(() =>
        service.generateSeats({
          ...validBaseOptions,
          sectionWidth: 500,
          sectionHeight: 100, // 20 + 4 * 40 = 180 > 100
        }),
      ).toThrowError(/Generated seats exceed section bounds/);
    });

    it('should reject when origin coordinates are outside section bounds', () => {
      expect(() =>
        service.generateSeats({
          ...validBaseOptions,
          originX: -5,
        }),
      ).toThrowError(/Origin coordinates must be within section bounds/);

      expect(() =>
        service.generateSeats({
          ...validBaseOptions,
          originY: 350, // sectionHeight is 300
        }),
      ).toThrowError(/Origin coordinates must be within section bounds/);
    });

    it('should reject when seat number exceeds Java integer range', () => {
      expect(() =>
        service.generateSeats({
          ...validBaseOptions,
          seatNumberStart: 2147483645,
          colCount: 10, // 2147483645 + 9 > 2147483647
        }),
      ).toThrowError(/Seat number exceeds Java integer range/);
    });

    it('should reject when capacity exact fit is exceeded', () => {
      // 5x10 = 50 seats. Capacity 50 -> succeeds
      const fitOptions = {
        ...validBaseOptions,
        venueCapacity: 50,
        totalOtherActiveSeats: 0,
      };
      expect(() => service.generateSeats(fitOptions)).not.toThrow();

      // One seat over capacity -> fails without partial generation
      const overOptions = {
        ...validBaseOptions,
        venueCapacity: 49,
        totalOtherActiveSeats: 0,
      };
      expect(() => service.generateSeats(overOptions)).toThrowError(
        /Projected active seat count \(50\) exceeds venue capacity \(49\)/,
      );
    });

    it('should reject collisions with retained seats (duplicate row/seat, grid, or position)', () => {
      const retainedSeat: VenueSectionSeat = {
        seatId: 's-loaded-1',
        rowLabel: 'A',
        seatNumber: 1,
        gridX: 0,
        gridY: 0,
        positionX: 20,
        positionY: 20,
        isActive: true,
      };

      expect(() =>
        service.generateSeats({
          ...validBaseOptions,
          existingSeats: [retainedSeat],
        }),
      ).toThrowError(/Generated seat duplicates row\/number \(A, 1\)/);
    });
  });

  describe('Section Operations', () => {
    const mockExistingSection: VenueSectionLayout = {
      sectionId: 'sec-existing-1',
      name: 'Orchestra',
      rowCount: 2,
      colCount: 2,
      isActive: true,
      positionX: 100,
      positionY: 100,
      width: 400,
      height: 200,
      rotationDeg: 0,
      zIndex: 1,
      shapeMetadata: null,
      seats: [
        {
          seatId: 'seat-101',
          rowLabel: 'A',
          seatNumber: 1,
          gridX: 0,
          gridY: 0,
          positionX: 20,
          positionY: 20,
          isActive: true,
        },
      ],
    };

    it('should create a section with null sectionId, unique name, and active status', () => {
      const newSec = service.createSection('Balcony', [mockExistingSection], {
        rowCount: 2,
        colCount: 3,
        width: 300,
        height: 200,
        generateSeats: true,
      });

      expect(newSec.sectionId).toBeNull();
      expect(newSec.name).toBe('Balcony');
      expect(newSec.isActive).toBeTrue();
      expect(newSec.seats.length).toBe(6);
      expect(newSec.seats[0].seatId).toBeNull();
    });

    it('should reject creating a section with duplicate active name', () => {
      expect(() => service.createSection('orchestra', [mockExistingSection])).toThrowError(
        /Section name "orchestra" already exists/,
      );
    });

    it('should duplicate section with null section/seat IDs, +40 offset, and disambiguated name', () => {
      const dup = service.duplicateSection(mockExistingSection, [mockExistingSection]);

      expect(dup.sectionId).toBeNull();
      expect(dup.name).toBe('Orchestra Copy');
      expect(dup.positionX).toBe(140);
      expect(dup.positionY).toBe(140);
      expect(dup.seats.length).toBe(1);
      expect(dup.seats[0].seatId).toBeNull();
      expect(dup.seats[0].rowLabel).toBe('A');
      expect(dup.seats[0].seatNumber).toBe(1);

      // Source section must be byte-for-byte unmodified
      expect(mockExistingSection.positionX).toBe(100);
      expect(mockExistingSection.positionY).toBe(100);
      expect(mockExistingSection.seats[0].seatId).toBe('seat-101');
    });

    it('should clamp duplicate offset near canvas maximum without altering source', () => {
      const nearMaxSection: VenueSectionLayout = {
        ...mockExistingSection,
        positionX: MAX_POSITION - 10,
        positionY: MAX_POSITION - 20,
      };

      const dup = service.duplicateSection(nearMaxSection, [nearMaxSection]);
      expect(dup.positionX).toBe(MAX_POSITION);
      expect(dup.positionY).toBe(MAX_POSITION);

      // Source remains unchanged
      expect(nearMaxSection.positionX).toBe(MAX_POSITION - 10);
      expect(nearMaxSection.positionY).toBe(MAX_POSITION - 20);
    });

    it('should disambiguate name when "Copy" already exists', () => {
      const copy1: VenueSectionLayout = {
        ...mockExistingSection,
        sectionId: 'sec-copy-1',
        name: 'Orchestra Copy',
      };

      const dup = service.duplicateSection(mockExistingSection, [mockExistingSection, copy1]);
      expect(dup.name).toBe('Orchestra Copy 2');
    });

    it('should deactivate section while retaining sectionId and all seatIds', () => {
      const deactivated = service.deactivateSection(mockExistingSection);

      expect(deactivated.sectionId).toBe('sec-existing-1');
      expect(deactivated.isActive).toBeFalse();
      expect(deactivated.seats[0].seatId).toBe('seat-101');
      expect(deactivated.seats[0].isActive).toBeFalse();
    });

    it('should reactivate section while retaining sectionId and all seatIds', () => {
      const inactiveSec: VenueSectionLayout = {
        ...mockExistingSection,
        isActive: false,
      };

      const reactivated = service.reactivateSection(inactiveSec, [inactiveSec]);
      expect(reactivated.sectionId).toBe('sec-existing-1');
      expect(reactivated.isActive).toBeTrue();
      expect(reactivated.seats[0].seatId).toBe('seat-101');
    });

    it('should reject removing a saved section (non-null sectionId)', () => {
      expect(service.canRemoveSection(mockExistingSection)).toBeFalse();
      expect(() => service.removeSection(mockExistingSection, [mockExistingSection])).toThrowError(
        /Saved sections cannot be removed; use deactivate instead/,
      );
    });

    it('should allow removing a never-saved section (null sectionId)', () => {
      const draftSec: VenueSectionLayout = {
        ...mockExistingSection,
        sectionId: null,
      };
      expect(service.canRemoveSection(draftSec)).toBeTrue();
      const remaining = service.removeSection(draftSec, [mockExistingSection, draftSec]);
      expect(remaining.length).toBe(1);
      expect(remaining[0].sectionId).toBe('sec-existing-1');
    });
  });

  describe('Bulk Seat Operations', () => {
    let testSection: VenueSectionLayout;

    beforeEach(() => {
      testSection = {
        sectionId: 'sec-1',
        name: 'Main',
        rowCount: 2,
        colCount: 2,
        isActive: true,
        positionX: 0,
        positionY: 0,
        width: 200,
        height: 200,
        rotationDeg: 0,
        zIndex: 1,
        shapeMetadata: null,
        seats: [
          {
            seatId: 's-00',
            rowLabel: 'A',
            seatNumber: 1,
            gridX: 0,
            gridY: 0,
            positionX: 20,
            positionY: 20,
            isActive: true,
          },
          {
            seatId: 's-01',
            rowLabel: 'A',
            seatNumber: 2,
            gridX: 1,
            gridY: 0,
            positionX: 60,
            positionY: 20,
            isActive: true,
          },
          {
            seatId: 's-10',
            rowLabel: 'B',
            seatNumber: 1,
            gridX: 0,
            gridY: 1,
            positionX: 20,
            positionY: 60,
            isActive: false,
          },
          {
            seatId: 's-11',
            rowLabel: 'B',
            seatNumber: 2,
            gridX: 1,
            gridY: 1,
            positionX: 60,
            positionY: 60,
            isActive: false,
          },
        ],
      };
    });

    it('should bulk activate/deactivate seats while preserving loaded seatIds', () => {
      const selected = new Set(['s-00', 's-01']);
      const deactivated = service.bulkSetActive(testSection, selected, false);

      expect(deactivated.seats[0].seatId).toBe('s-00');
      expect(deactivated.seats[0].isActive).toBeFalse();
      expect(deactivated.seats[1].seatId).toBe('s-01');
      expect(deactivated.seats[1].isActive).toBeFalse();

      // Activate with capacity check
      const selectedInactive = new Set(['s-10']);
      const activated = service.bulkSetActive(deactivated, selectedInactive, true, 10, 2);
      expect(activated.seats[2].seatId).toBe('s-10');
      expect(activated.seats[2].isActive).toBeTrue();
    });

    it('should reject bulk activate in an inactive section', () => {
      const inactiveSec = { ...testSection, isActive: false };
      const selected = new Set(['s-10']);

      expect(() => service.bulkSetActive(inactiveSec, selected, true)).toThrowError(
        /Cannot activate seats in an inactive section/,
      );
    });

    it('should bulk translate selected seats and preserve loaded seatIds', () => {
      const selected = new Set(['s-00', 's-01']);
      const translated = service.bulkTranslate(testSection, selected, {
        deltaX: 15,
        deltaY: 10,
        sectionWidth: 200,
        sectionHeight: 200,
      });

      expect(translated.seats[0].seatId).toBe('s-00');
      expect(translated.seats[0].positionX).toBe(35);
      expect(translated.seats[0].positionY).toBe(30);

      // Unselected seats stay unchanged
      expect(translated.seats[2].seatId).toBe('s-10');
      expect(translated.seats[2].positionX).toBe(20);
      expect(translated.seats[2].positionY).toBe(60);
    });

    it('should reject bulk translation that moves any selected seat out of bounds', () => {
      const selected = new Set(['s-00', 's-01']);

      // Moving by +150 on X moves s-01 (60 + 150 = 210 > 200) out of bounds
      expect(() =>
        service.bulkTranslate(testSection, selected, {
          deltaX: 150,
          deltaY: 0,
          sectionWidth: 200,
          sectionHeight: 200,
        }),
      ).toThrowError(/Translation would move seat Row A Seat 2 out of bounds/);
    });

    it('should bulk set row label while preserving loaded seatIds', () => {
      const selected = new Set(['0_0', '0_1']);
      const relabeled = service.bulkSetRowLabel(testSection, selected, 'VIP');

      expect(relabeled.seats[0].seatId).toBe('s-00');
      expect(relabeled.seats[0].rowLabel).toBe('VIP');
      expect(relabeled.seats[1].seatId).toBe('s-01');
      expect(relabeled.seats[1].rowLabel).toBe('VIP');
    });

    it('should reject bulk set row label if duplicate row/seat is caused', () => {
      // Row A Seat 1 and Row B Seat 1 exist. If we relabel Row B Seat 1 to 'A', it duplicates (A, 1)
      const selected = new Set(['s-10']);
      expect(() => service.bulkSetRowLabel(testSection, selected, 'A')).toThrowError(
        /causes duplicate row\/seat/,
      );
    });

    it('should bulk renumber in deterministic (gridY, gridX, seatId) order', () => {
      const selected = new Set(['s-01', 's-00']); // pass in reverse order
      const renumbered = service.bulkRenumber(testSection, selected, 10);

      // s-00 is at gridX 0, gridY 0 -> gets 10
      expect(renumbered.seats[0].seatId).toBe('s-00');
      expect(renumbered.seats[0].seatNumber).toBe(10);

      // s-01 is at gridX 1, gridY 0 -> gets 11
      expect(renumbered.seats[1].seatId).toBe('s-01');
      expect(renumbered.seats[1].seatNumber).toBe(11);
    });

    it('should reject bulk renumber if collision occurs with unselected seat', () => {
      // s-10 has seatNumber 1 in Row B. If we renumber s-11 (in Row B) to 1, duplicate occurs
      const selected = new Set(['s-11']);
      expect(() => service.bulkRenumber(testSection, selected, 1)).toThrowError(
        /causes duplicate row\/seat/,
      );
    });
  });

  describe('Seat Key helpers', () => {
    it('should extract seat key from grid coordinates', () => {
      const seat: VenueSectionSeat = {
        seatId: 'xyz',
        rowLabel: 'C',
        seatNumber: 4,
        gridX: 3,
        gridY: 2,
        positionX: 50,
        positionY: 50,
        isActive: true,
      };
      expect(getSeatKey(seat)).toBe('2_3');
      expect(isSeatSelected(seat, new Set(['xyz']))).toBeTrue();
      expect(isSeatSelected(seat, new Set(['2_3']))).toBeTrue();
      expect(isSeatSelected(seat, new Set(['other']))).toBeFalse();
    });
  });
});
