import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, ActivatedRoute } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';
import { getRowLabel, VenueGridDesignerComponent } from './venue-grid-designer.component';
import { AdminVenueApiService } from '../../../../services/admin-venue-api.service';
import {
  VenueLayout,
  VenueLayoutElement,
  VenueSectionLayout,
  VenueSectionSeat,
} from '../../../../models/venue.model';
import { VenueLayoutEditorStateService } from '../../../../services/venue-layout-editor-state.service';
import { LayoutHistoryService } from '../../../../services/layout-history.service';
import {
  getSectionDraftKey,
  SeatLayoutGeneratorService,
} from '../../../../services/seat-layout-generator.service';

describe('VenueGridDesignerComponent', () => {
  let component: VenueGridDesignerComponent;
  let fixture: ComponentFixture<VenueGridDesignerComponent>;
  let venueApiSpy: jasmine.SpyObj<AdminVenueApiService>;
  let editorState: VenueLayoutEditorStateService;
  let generator: SeatLayoutGeneratorService;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;
  let dialogSpy: jasmine.SpyObj<MatDialog>;

  const mockSeats: VenueSectionSeat[] = [
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
      isActive: true,
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
  ];

  const mockSection: VenueSectionLayout = {
    sectionId: 'sec-1',
    name: 'Orchestra',
    rowCount: 2,
    colCount: 2,
    isActive: true,
    positionX: 10,
    positionY: 20,
    width: 400,
    height: 300,
    rotationDeg: 0,
    zIndex: 1,
    shapeMetadata: null,
    seats: mockSeats,
  };

  const mockLayout: VenueLayout = {
    venueId: 'v-100',
    name: 'National Opera',
    capacity: 1000,
    totalConfiguredSeats: 4,
    layoutVersion: 1,
    sections: [mockSection],
    elements: [],
  };

  describe('getRowLabel algorithm unit test', () => {
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
  });

  describe('Component lifecycle & interactions', () => {
    beforeEach(async () => {
      venueApiSpy = jasmine.createSpyObj('AdminVenueApiService', [
        'getEditableLayout',
        'getVenueLayout',
        'saveLayout',
        'validateLayout',
        'createSection',
        'deleteSection',
        'toggleSeat',
      ]);
      snackBarSpy = jasmine.createSpyObj('MatSnackBar', ['open']);
      dialogSpy = jasmine.createSpyObj('MatDialog', ['open']);
      dialogSpy.open.and.returnValue({ afterClosed: () => of(undefined) } as never);

      venueApiSpy.getEditableLayout.and.returnValue(of(JSON.parse(JSON.stringify(mockLayout))));
      venueApiSpy.getVenueLayout.and.returnValue(of(JSON.parse(JSON.stringify(mockLayout))));
      venueApiSpy.saveLayout.and.returnValue(of(JSON.parse(JSON.stringify(mockLayout))));
      venueApiSpy.validateLayout.and.returnValue(of(undefined));

      await TestBed.configureTestingModule({
        imports: [VenueGridDesignerComponent],
        providers: [
          provideHttpClient(),
          provideHttpClientTesting(),
          provideRouter([]),
          { provide: AdminVenueApiService, useValue: venueApiSpy },
          { provide: MatSnackBar, useValue: snackBarSpy },
          { provide: MatDialog, useValue: dialogSpy },
          {
            provide: ActivatedRoute,
            useValue: {
              snapshot: {
                paramMap: {
                  get: (key: string) => (key === 'id' ? 'v-100' : null),
                },
              },
            },
          },
        ],
      }).compileComponents();

      editorState = TestBed.inject(VenueLayoutEditorStateService);
      generator = TestBed.inject(SeatLayoutGeneratorService);

      fixture = TestBed.createComponent(VenueGridDesignerComponent);
      component = fixture.componentInstance;
      fixture.detectChanges();
    });

    it('should initialize and load venue layout via editor state', () => {
      expect(component).toBeTruthy();
      expect(venueApiSpy.getEditableLayout).toHaveBeenCalledWith('v-100');
      expect(component.venue()?.name).toBe('National Opera');
      expect(component.sections().length).toBe(1);
      expect(component.currentSection()?.name).toBe('Orchestra');
      expect(component.totalConfiguredActiveSeats()).toBe(3);
    });

    describe('Risk: UUID regeneration and stable seat IDs', () => {
      it('should preserve loaded seatIds when bulk updating seat active status', () => {
        component.onSeatSelectionChanged(new Set(['s-00', 's-01']));
        component.onBulkActivate(false);

        const sec = component.currentSection();
        expect(sec?.seats[0].seatId).toBe('s-00');
        expect(sec?.seats[0].isActive).toBeFalse();
        expect(sec?.seats[1].seatId).toBe('s-01');
        expect(sec?.seats[1].isActive).toBeFalse();

        // Check unselected seats
        expect(sec?.seats[2].seatId).toBe('s-10');
        expect(sec?.seats[3].seatId).toBe('s-11');
      });

      it('should preserve loaded seatIds when bulk translating seats', () => {
        component.onSeatSelectionChanged(new Set(['s-00']));
        component.onBulkTranslate({ deltaX: 10, deltaY: 5 });

        const sec = component.currentSection();
        expect(sec?.seats[0].seatId).toBe('s-00');
        expect(sec?.seats[0].positionX).toBe(30);
        expect(sec?.seats[0].positionY).toBe(25);
      });

      it('should preserve loaded seatIds when bulk setting row labels', () => {
        component.onSeatSelectionChanged(new Set(['s-00', 's-01']));
        component.onBulkSetRowLabel('VIP');

        const sec = component.currentSection();
        expect(sec?.seats[0].seatId).toBe('s-00');
        expect(sec?.seats[0].rowLabel).toBe('VIP');
        expect(sec?.seats[1].seatId).toBe('s-01');
        expect(sec?.seats[1].rowLabel).toBe('VIP');
      });

      it('should preserve loaded seatIds when bulk renumbering seats', () => {
        component.onSeatSelectionChanged(new Set(['s-00', 's-01']));
        component.onBulkRenumber(10);

        const sec = component.currentSection();
        expect(sec?.seats[0].seatId).toBe('s-00');
        expect(sec?.seats[0].seatNumber).toBe(10);
        expect(sec?.seats[1].seatId).toBe('s-01');
        expect(sec?.seats[1].seatNumber).toBe(11);
      });

      it('should preserve loaded sectionId and seatIds when deactivating a section', () => {
        component.deactivateSection();

        const sec = component.currentSection();
        expect(sec?.sectionId).toBe('sec-1');
        expect(sec?.isActive).toBeFalse();
        expect(sec?.seats[0].seatId).toBe('s-00');
        expect(sec?.seats[0].isActive).toBeFalse();
        expect(sec?.seats[1].seatId).toBe('s-01');
        expect(sec?.seats[1].isActive).toBeFalse();
      });

      it('should create null IDs only when duplicating section or generating seats', () => {
        component.duplicateSection();

        const sections = component.sections();
        expect(sections.length).toBe(2);

        const dup = sections[1];
        expect(dup.sectionId).toBeNull();
        expect(dup.name).toBe('Orchestra Copy');
        for (const seat of dup.seats) {
          expect(seat.seatId).toBeNull();
        }

        // Original section retains its loaded IDs
        const original = sections[0];
        expect(original.sectionId).toBe('sec-1');
        expect(original.seats[0].seatId).toBe('s-00');
      });
    });

    describe('Risk: Network Fan-Out', () => {
      it('should apply all bulk seat operations strictly in local draft without HTTP calls', () => {
        // Clear spy invocations from initial load
        venueApiSpy.saveLayout.calls.reset();
        venueApiSpy.toggleSeat.calls.reset();

        // Run rapid bulk actions
        component.onSeatSelectionChanged(new Set(['s-00']));
        component.onBulkActivate(false);
        component.onBulkTranslate({ deltaX: 5, deltaY: 5 });
        component.onBulkRenumber(5);
        component.duplicateSection();
        component.deactivateSection();

        // Must NOT make any API calls
        expect(venueApiSpy.saveLayout).not.toHaveBeenCalled();
        expect(venueApiSpy.toggleSeat).not.toHaveBeenCalled();

        // Only explicit saveLayout triggers HTTP request
        component.saveLayout();
        expect(venueApiSpy.saveLayout).toHaveBeenCalledTimes(1);
      });
    });

    describe('Risk: Invalid generation and bounds leave draft byte-for-byte unchanged', () => {
      it('should leave draft unchanged when bulk translate moves seat out of bounds', () => {
        const preUpdateSeats = JSON.stringify(component.currentSection()?.seats);

        component.onSeatSelectionChanged(new Set(['s-00']));
        // section width is 400. Moving by +500 moves it out of bounds
        component.onBulkTranslate({ deltaX: 500, deltaY: 0 });

        expect(component.validationError()).toContain('out of bounds');
        const postUpdateSeats = JSON.stringify(component.currentSection()?.seats);
        expect(postUpdateSeats).toBe(preUpdateSeats);
      });

      it('should leave draft unchanged when duplicate row/seat is caused by row label edit', () => {
        const preUpdateSeats = JSON.stringify(component.currentSection()?.seats);

        // Renaming B1 to A1 would duplicate A1
        component.onSeatSelectionChanged(new Set(['s-10']));
        component.onBulkSetRowLabel('A');

        expect(component.validationError()).toContain('duplicate row/seat');
        const postUpdateSeats = JSON.stringify(component.currentSection()?.seats);
        expect(postUpdateSeats).toBe(preUpdateSeats);
      });

      it('should leave draft unchanged when seat generation exceeds capacity', () => {
        // Capacity validation runs against a never-saved draft section so the
        // loaded-ID guard (REV-001) does not mask the capacity rule.
        component.sectionForm.patchValue({
          name: 'Capacity Probe',
          rowCount: 2,
          colCount: 2,
          generateSeats: false,
        });
        component.createSection();
        const draftSec = component.sections().find((s) => s.name === 'Capacity Probe')!;
        component.selectSection(draftSec);
        const preUpdate = JSON.stringify(component.currentSection());

        // Request generating 2000 seats when venue capacity is 1000
        component.onGenerateSeats({
          rowCount: 40,
          colCount: 50,
          pitchX: 20,
          pitchY: 20,
          originX: 10,
          originY: 10,
          isActive: true,
          sectionWidth: 1500,
          sectionHeight: 1500,
          venueCapacity: component.venue()?.capacity,
          totalOtherActiveSeats: 0,
        });

        expect(component.validationError()).toContain('exceeds venue capacity');
        expect(JSON.stringify(component.currentSection())).toBe(preUpdate);
      });
    });

    describe('Risk: Destructive removal of existing section', () => {
      it('should reject removing a saved section and keep it in draft', () => {
        expect(component.sections().length).toBe(1);
        component.removeSection();

        expect(component.validationError()).toContain(
          'Saved sections cannot be removed; use deactivate instead',
        );
        expect(component.sections().length).toBe(1);
        expect(component.sections()[0].sectionId).toBe('sec-1');
      });

      it('should permit removing a never-saved null-ID section', () => {
        // Add a new draft section
        component.sectionForm.patchValue({
          name: 'Temporary Balcony',
          rowCount: 2,
          colCount: 2,
          generateSeats: false,
        });
        component.createSection();

        expect(component.sections().length).toBe(2);
        const draftSec = component.sections().find((s) => s.name === 'Temporary Balcony');
        expect(draftSec?.sectionId).toBeNull();

        component.selectSection(draftSec ?? null);
        component.removeSection();

        expect(component.sections().length).toBe(1);
        expect(component.sections()[0].name).toBe('Orchestra');
      });
    });

    describe('REV-001: generation never destroys persisted seat identities', () => {
      it('should reject generation on a loaded section and retain every ID', () => {
        const before = JSON.stringify(component.sections());
        component.onGenerateSeats({
          rowCount: 2,
          colCount: 2,
          pitchX: 40,
          pitchY: 40,
          originX: 20,
          originY: 20,
          isActive: true,
          sectionWidth: 400,
          sectionHeight: 300,
          venueCapacity: 1000,
          totalOtherActiveSeats: 0,
        });

        expect(component.validationError()).toContain('stable seat identities');
        expect(JSON.stringify(component.sections())).toBe(before);
        const ids = component.currentSection()?.seats.map((s) => s.seatId);
        expect(ids).toEqual(['s-00', 's-01', 's-10', 's-11']);
      });

      it('should allow generation on a null-ID draft section with null seat IDs', () => {
        component.sectionForm.patchValue({
          name: 'Draft Gen',
          rowCount: 2,
          colCount: 2,
          generateSeats: false,
        });
        component.createSection();
        const draft = component.sections().find((s) => s.name === 'Draft Gen')!;
        component.selectSection(draft);

        component.onGenerateSeats({
          rowCount: 2,
          colCount: 2,
          pitchX: 40,
          pitchY: 40,
          originX: 20,
          originY: 20,
          isActive: true,
          sectionWidth: draft.width,
          sectionHeight: draft.height,
        });

        expect(component.validationError()).toBeNull();
        const generated = component.currentSection()?.seats ?? [];
        expect(generated.length).toBe(4);
        expect(generated.every((s) => s.seatId === null)).toBeTrue();
        // Loaded section untouched
        expect(component.sections()[0].seats[0].seatId).toBe('s-00');
      });
    });

    describe('REV-002: multiple null-ID draft sections stay independently targetable', () => {
      function createDraft(name: string): VenueSectionLayout {
        component.sectionForm.patchValue({
          name,
          rowCount: 2,
          colCount: 2,
          generateSeats: true,
        });
        component.createSection();
        return component.sections().find((s) => s.name === name)!;
      }

      it('should isolate property edits, generate, deactivate/reactivate to the selected draft', () => {
        const draftA = createDraft('Draft A');
        const draftB = createDraft('Draft B');
        expect(draftA.sectionId).toBeNull();
        expect(draftB.sectionId).toBeNull();
        const beforeA = JSON.stringify(component.sections().find((s) => s.name === 'Draft A'));

        component.selectSection(component.sections().find((s) => s.name === 'Draft B')!);
        component.updateSectionProperties({ width: 999 });
        expect(JSON.stringify(component.sections().find((s) => s.name === 'Draft A'))).toBe(
          beforeA,
        );
        expect(component.sections().find((s) => s.name === 'Draft B')?.width).toBe(999);

        component.deactivateSection();
        expect(component.sections().find((s) => s.name === 'Draft A')?.isActive).toBeTrue();
        expect(component.sections().find((s) => s.name === 'Draft B')?.isActive).toBeFalse();

        component.reactivateSection();
        expect(component.sections().find((s) => s.name === 'Draft B')?.isActive).toBeTrue();
        expect(JSON.stringify(component.sections().find((s) => s.name === 'Draft A'))).toBe(
          beforeA,
        );
      });

      it('should isolate each bulk operation to the selected null-ID draft', () => {
        createDraft('Bulk A');
        createDraft('Bulk B');
        const bulkB = component.sections().find((s) => s.name === 'Bulk B')!;
        component.selectSection(bulkB);

        const seatKeys = new Set(
          (component.currentSection()?.seats.slice(0, 2) ?? []).map((s) => `${s.gridY}_${s.gridX}`),
        );
        const beforeA = JSON.stringify(component.sections().find((s) => s.name === 'Bulk A'));

        component.onSeatSelectionChanged(seatKeys);
        component.onBulkActivate(false);
        expect(JSON.stringify(component.sections().find((s) => s.name === 'Bulk A'))).toBe(beforeA);

        component.onBulkTranslate({ deltaX: 5, deltaY: 0 });
        expect(JSON.stringify(component.sections().find((s) => s.name === 'Bulk A'))).toBe(beforeA);

        component.onBulkSetRowLabel('VIPB');
        expect(JSON.stringify(component.sections().find((s) => s.name === 'Bulk A'))).toBe(beforeA);
        expect(
          component
            .currentSection()
            ?.seats.filter((s) => seatKeys.has(`${s.gridY}_${s.gridX}`))
            .every((s) => s.rowLabel === 'VIPB'),
        ).toBeTrue();

        component.onBulkRenumber(50);
        expect(JSON.stringify(component.sections().find((s) => s.name === 'Bulk A'))).toBe(beforeA);
      });

      it('should isolate canvas transform events to the selected null-ID draft', () => {
        createDraft('Transform A');
        const draftB = createDraft('Transform B');
        component.selectSection(component.sections().find((s) => s.name === 'Transform B')!);
        const beforeA = JSON.stringify(component.sections().find((s) => s.name === 'Transform A'));

        const targetB = component.sections().find((s) => s.name === 'Transform B')!;
        const draftKeyB = getSectionDraftKey(targetB);
        component.onSectionTransformChanged({
          sectionId: targetB.sectionId,
          draftKey: draftKeyB,
          positionX: targetB.positionX + 10,
          positionY: targetB.positionY,
          width: targetB.width,
          height: targetB.height,
          rotationDeg: 0,
        });

        expect(JSON.stringify(component.sections().find((s) => s.name === 'Transform A'))).toBe(
          beforeA,
        );
        expect(component.sections().find((s) => s.name === 'Transform B')?.positionX).toBe(
          draftB.positionX + 10,
        );
      });
    });

    describe('REV-004: modifier canvas selection', () => {
      it('should accumulate seats across two additive canvas clicks', () => {
        const sec = component.currentSection()!;
        const [seatA, seatB] = sec.seats;
        component.onCanvasSeatSelected({ seat: seatA, section: sec, additive: false });
        expect([...component.selectedSeatKeys()].length).toBe(1);

        component.onCanvasSeatSelected({ seat: seatB, section: sec, additive: true });
        expect(component.selectedSeatKeys().has(seatA.seatId!)).toBeTrue();
        expect(component.selectedSeatKeys().has(seatB.seatId!)).toBeTrue();
        expect(component.selectedSeatKeys().size).toBe(2);
      });

      it('should replace selection on a plain canvas click', () => {
        const sec = component.currentSection()!;
        const [seatA, seatB] = sec.seats;
        component.onCanvasSeatSelected({ seat: seatA, section: sec, additive: false });
        component.onCanvasSeatSelected({ seat: seatB, section: sec, additive: true });
        expect(component.selectedSeatKeys().size).toBe(2);

        component.onCanvasSeatSelected({ seat: seatB, section: sec, additive: false });
        expect(component.selectedSeatKeys().size).toBe(1);
        expect(component.selectedSeatKeys().has(seatB.seatId!)).toBeTrue();
      });
    });

    describe('REV-005: invalid geometry leaves the draft unchanged', () => {
      it('should reject an out-of-bounds resize without calling replaceDraft', () => {
        const before = JSON.stringify(component.sections());
        const sec = component.currentSection()!;
        component.onSectionTransformChanged({
          sectionId: sec.sectionId,
          draftKey: sec.draftKey ?? sec.sectionId,
          positionX: sec.positionX,
          positionY: sec.positionY,
          width: 1,
          height: sec.height,
          rotationDeg: 0,
        });

        expect(component.validationError()).toContain('out of section bounds');
        expect(JSON.stringify(component.sections())).toBe(before);
      });

      it('should reject an out-of-bounds properties-panel width without mutation', () => {
        const before = JSON.stringify(component.sections());
        component.updateSectionProperties({ width: 1 });

        expect(component.validationError()).toContain('out of section bounds');
        expect(JSON.stringify(component.sections())).toBe(before);
      });
    });

    describe('Layout elements draft feedback (canvas elementsChange)', () => {
      const stageElement: VenueLayoutElement = {
        elementId: null,
        type: 'STAGE',
        label: 'Stage',
        geometry: { x: 100, y: 40, width: 400, height: 80, rotationDeg: 0 },
        zIndex: 10,
      };

      it('should persist canvas element changes into the editor draft and mark dirty', () => {
        expect(component.elements().length).toBe(0);

        component.onCanvasElementsChanged([stageElement]);

        expect(component.elements().length).toBe(1);
        expect(component.elements()[0].label).toBe('Stage');
        expect(component.isDirty()).toBeTrue();
      });

      it('should retain draft elements across subsequent seat-toggle draft updates', () => {
        component.onCanvasElementsChanged([stageElement]);

        const sec = component.sections()[0];
        component.onCanvasSeatToggle({ seat: sec.seats[0], section: sec });

        expect(component.elements().length).toBe(1);
        expect(component.elements()[0].type).toBe('STAGE');
      });
    });

    describe('Save and Discard workflow', () => {
      it('should save layout draft via one validation POST and one atomic PUT', () => {
        component.onSeatSelectionChanged(new Set(['s-00']));
        component.onBulkActivate(false);
        expect(component.isDirty()).toBeTrue();

        component.saveLayout();

        expect(venueApiSpy.validateLayout).toHaveBeenCalledTimes(1);
        expect(venueApiSpy.saveLayout).toHaveBeenCalledTimes(1);
        expect(venueApiSpy.saveLayout.calls.mostRecent().args[1]).toEqual(
          venueApiSpy.validateLayout.calls.mostRecent().args[1],
        );
        expect(snackBarSpy.open).toHaveBeenCalledWith(
          jasmine.stringMatching(/Venue layout saved successfully/),
          'Close',
          jasmine.objectContaining({ panelClass: 'snack-success' }),
        );
      });

      it('should discard changes and reset draft to baseline', () => {
        component.onSeatSelectionChanged(new Set(['s-00']));
        component.onBulkActivate(false);
        expect(component.isDirty()).toBeTrue();

        component.discardChanges();
        expect(component.isDirty()).toBeFalse();
        expect(component.currentSection()?.seats[0].isActive).toBeTrue();
      });
    });

    describe('Interactive Seat Canvas Features & Aesthetic Overhaul', () => {
      it('should toggle seat active state via onCanvasSeatToggle', () => {
        const sec = component.currentSection()!;
        const seat = sec.seats[0]; // s-00, initially active: true
        expect(seat.isActive).toBeTrue();

        component.onCanvasSeatToggle({ seat, section: sec });

        const updatedSeat = component.currentSection()?.seats.find((s) => s.seatId === 's-00');
        expect(updatedSeat?.isActive).toBeFalse();
        expect(component.isDirty()).toBeTrue();

        // Toggle back to active
        component.onCanvasSeatToggle({ seat: updatedSeat!, section: sec });
        const toggledBackSeat = component.currentSection()?.seats.find((s) => s.seatId === 's-00');
        expect(toggledBackSeat?.isActive).toBeTrue();
      });

      it('should paint seat color via onCanvasSeatPaint without affecting backend schema', () => {
        const sec = component.currentSection()!;
        const seat = sec.seats[0]; // s-00, row A, seat 1

        component.onCanvasSeatPaint({ seat, section: sec, color: '#F59E0B' });

        const updatedSec = component.currentSection();
        expect(updatedSec?.shapeMetadata).toBeDefined();
        const seatColors = (updatedSec?.shapeMetadata as any)?.seatColors;
        expect(seatColors).toBeDefined();
        // REV-001: exactly one stable entry per paint (seatId), no triplicated keys.
        expect(seatColors['s-00']).toBe('#F59E0B');
        expect(Object.keys(seatColors)).toEqual(['s-00']);
        expect(component.isDirty()).toBeTrue();
      });

      it('should prune legacy label color keys on bulk rename while keeping color via stable key', () => {
        const sec = component.currentSection()!;
        const seat = sec.seats[0]; // s-00, row A, seat 1

        // Seed a legacy `RowLabel_Number` key plus the stable paint entry.
        component.onSeatColorAssigned({ seatKeys: ['A_1'], color: '#F59E0B' });
        component.onCanvasSeatPaint({ seat, section: sec, color: '#F59E0B' });
        expect(Object.keys((component.currentSection()?.shapeMetadata as any)?.seatColors)).toEqual(
          jasmine.arrayWithExactContents(['A_1', 's-00']),
        );

        component.onSeatSelectionChanged(new Set(['s-00']));
        component.onBulkSetRowLabel('VIP');

        const seatColors = (component.currentSection()?.shapeMetadata as any)?.seatColors;
        expect(seatColors['A_1']).toBeUndefined();
        expect(seatColors['s-00']).toBe('#F59E0B');
        expect(component.currentSection()?.seats[0].rowLabel).toBe('VIP');
      });

      it('should prune legacy label color keys on bulk renumber', () => {
        const sec = component.currentSection()!;
        const seat = sec.seats[0]; // s-00, row A, seat 1

        component.onSeatColorAssigned({ seatKeys: ['A_1'], color: '#F59E0B' });
        component.onCanvasSeatPaint({ seat, section: sec, color: '#F59E0B' });

        // Renumber only s-00 (row A) to 10: no collision with A/2.
        component.onSeatSelectionChanged(new Set(['s-00']));
        component.onBulkRenumber(10);

        const seatColors = (component.currentSection()?.shapeMetadata as any)?.seatColors;
        expect(seatColors['A_1']).toBeUndefined();
        expect(seatColors['s-00']).toBe('#F59E0B');
        expect(component.currentSection()?.seats[0].seatNumber).toBe(10);
      });

      it('should update section color via onSectionColorChanged in shapeMetadata', () => {
        component.onSectionColorChanged('#8B5CF6');

        const updatedSec = component.currentSection();
        expect((updatedSec?.shapeMetadata as any)?.color).toBe('#8B5CF6');
        expect(component.getSectionColor(updatedSec!)).toBe('#8B5CF6');
        expect(component.isDirty()).toBeTrue();
      });

      it('should deactivate center column when onCenterAisleCreated is called', () => {
        // Col count is 2: center column is Math.floor(2 / 2) = 1
        component.onCenterAisleCreated();

        const sec = component.currentSection();
        const col1Seats = sec?.seats.filter((s) => s.gridX === 1);
        expect(col1Seats?.length).toBeGreaterThan(0);
        expect(col1Seats?.every((s) => !s.isActive)).toBeTrue();
      });

      it('should reset all seats to active when onAllSeatsActivated is called', () => {
        // First deactivate some seats
        component.onCenterAisleCreated();
        expect(component.currentSection()?.seats.some((s) => !s.isActive)).toBeTrue();

        // Reset
        component.onAllSeatsActivated();
        expect(component.currentSection()?.seats.every((s) => s.isActive)).toBeTrue();
      });

      it('should append a new row and update rowCount and seats', () => {
        const initialRowCount = component.currentSection()?.rowCount ?? 0;
        const initialSeatCount = component.currentSection()?.seats.length ?? 0;

        component.onRowAppended();

        const updatedSec = component.currentSection();
        expect(updatedSec?.rowCount).toBe(initialRowCount + 1);
        expect(updatedSec?.seats.length).toBe(initialSeatCount + (updatedSec?.colCount ?? 0));
      });

      it('should append a new column and update colCount and seats', () => {
        const initialColCount = component.currentSection()?.colCount ?? 0;
        const initialSeatCount = component.currentSection()?.seats.length ?? 0;

        component.onColAppended();

        const updatedSec = component.currentSection();
        expect(updatedSec?.colCount).toBe(initialColCount + 1);
        expect(updatedSec?.seats.length).toBe(initialSeatCount + (updatedSec?.rowCount ?? 0));
      });

      it('should reject appending a row on duplicate row/number without mutating the draft', () => {
        // Relabel B/2 to C/2 so the appended row C collides on C/2.
        component.onSeatSelectionChanged(new Set(['s-11']));
        component.onBulkSetRowLabel('C');
        expect(component.validationError()).toBeNull();

        const before = JSON.stringify(component.currentSection());
        component.onRowAppended();

        expect(component.validationError()).toMatch(/duplicates row\/number/);
        expect(JSON.stringify(component.currentSection())).toBe(before);
      });

      it('should reject appending a column on duplicate row/number without mutating the draft', () => {
        // Renumber B/1 to B/3 so the appended column (seat #3) collides on B/3.
        component.onSeatSelectionChanged(new Set(['s-10']));
        component.onBulkRenumber(3);
        expect(component.validationError()).toBeNull();

        const before = JSON.stringify(component.currentSection());
        component.onColAppended();

        expect(component.validationError()).toMatch(/duplicates row\/number/);
        expect(JSON.stringify(component.currentSection())).toBe(before);
      });

      it('should reject appending a row that would push height past MAX_POSITION', () => {
        editorState.replaceDraft((draft) => {
          draft.sections = draft.sections.map((s) => ({
            ...s,
            height: 100000,
            seats: (s.seats || []).map((st) =>
              st.gridY === s.rowCount - 1 ? { ...st, positionY: 99990 } : st,
            ),
          }));
          return draft;
        });

        const before = JSON.stringify(component.currentSection());
        component.onRowAppended();

        expect(component.validationError()).toMatch(/exceeds maximum/);
        expect(JSON.stringify(component.currentSection())).toBe(before);
      });

      it('should reject appending a column that would push width past MAX_POSITION', () => {
        editorState.replaceDraft((draft) => {
          draft.sections = draft.sections.map((s) => ({
            ...s,
            width: 100000,
            seats: (s.seats || []).map((st) =>
              st.gridX === s.colCount - 1 ? { ...st, positionX: 99990 } : st,
            ),
          }));
          return draft;
        });

        const before = JSON.stringify(component.currentSection());
        component.onColAppended();

        expect(component.validationError()).toMatch(/exceeds maximum/);
        expect(JSON.stringify(component.currentSection())).toBe(before);
      });

      it('should derive section counts from seats rather than grid capacity', () => {
        expect(component.currentSectionTotalCount()).toBe(4);
        expect(component.currentSectionInactiveCount()).toBe(1);

        editorState.replaceDraft((draft) => {
          draft.sections = draft.sections.map((s) => ({ ...s, seats: [] }));
          return draft;
        });

        expect(component.currentSectionTotalCount()).toBe(0);
        expect(component.currentSectionInactiveCount()).toBe(0);
      });

      it('should label grid matrix rows with actual seat row labels after rename', () => {
        expect(component.gridMatrix()[0].rowLabel).toBe('A');

        component.onSeatSelectionChanged(new Set(['s-00', 's-01']));
        component.onBulkSetRowLabel('VIP');

        const matrix = component.gridMatrix();
        expect(matrix[0].rowLabel).toBe('VIP');
        expect(matrix[1].rowLabel).toBe('B');
      });

      it('should reject renaming a section to a duplicate name without mutating the draft', () => {
        editorState.replaceDraft((draft) => {
          const copy = {
            ...draft.sections[0],
            sectionId: null,
            draftKey: 'draft-balcony',
            name: 'Balcony',
          };
          draft.sections = [...draft.sections, copy];
          return draft;
        });

        const before = JSON.stringify(component.currentSection());
        component.updateSectionProperties({ name: 'balcony' });

        expect(component.validationError()).toMatch(/already exists/);
        expect(JSON.stringify(component.currentSection())).toBe(before);
      });

      it('should reject transform changes past position bounds without mutating the draft', () => {
        const before = JSON.stringify(component.currentSection());
        component.onSectionTransformChanged({
          sectionId: 'sec-1',
          draftKey: getSectionDraftKey(component.currentSection()!),
          positionX: 100001,
          positionY: 20,
          width: 400,
          height: 300,
          rotationDeg: 0,
        });

        expect(component.validationError()).toMatch(/between 0 and 100000/);
        expect(JSON.stringify(component.currentSection())).toBe(before);
      });
    });

    describe('TASK-P11-009: undo/redo, dirty guard, keyboard, accessibility', () => {
      let history: LayoutHistoryService;

      beforeEach(() => {
        history = TestBed.inject(LayoutHistoryService);
        history.clear();
        // Re-establish a clean baseline: reload bypasses history, then clear.
        editorState.applyServerSnapshot(JSON.parse(JSON.stringify(mockLayout)));
        history.clear();
        fixture.detectChanges();
      });

      function keyboardEvent(
        key: string,
        target: HTMLElement,
        extra: Partial<KeyboardEventInit> & {
          ctrlKey?: boolean;
          metaKey?: boolean;
          shiftKey?: boolean;
          altKey?: boolean;
        } = {},
      ): KeyboardEvent {
        const event = new KeyboardEvent('keydown', {
          key,
          bubbles: true,
          cancelable: true,
          ...extra,
        });
        Object.defineProperty(event, 'target', { value: target });
        return event;
      }

      it('should isolate history snapshots from later draft mutations (no shared references)', () => {
        const before = JSON.stringify(component.sections());
        component.onSeatSelectionChanged(new Set(['s-00']));
        component.onBulkActivate(false);
        expect(history.undoDepth()).toBe(1);

        editorState.replaceDraft((draft) => {
          draft.sections[0].name = 'Hacked';
          return draft;
        });
        expect(component.currentSection()?.name).toBe('Hacked');

        component.undo();
        expect(component.currentSection()?.name).toBe('Orchestra');
        expect(JSON.stringify(component.sections())).toBe(before);
      });

      it('should not mutate layout for Delete/arrows/undo keys inside inputs or contenteditable', () => {
        const before = JSON.stringify(component.sections());
        const input = document.createElement('input');
        const textarea = document.createElement('textarea');
        const select = document.createElement('select');
        const editable = document.createElement('div');
        editable.setAttribute('contenteditable', 'true');

        component.onKeyDown(keyboardEvent('Delete', input));
        component.onKeyDown(keyboardEvent('Backspace', textarea));
        component.onKeyDown(keyboardEvent('ArrowUp', select));
        component.onKeyDown(keyboardEvent('ArrowLeft', editable));
        const imeInput = document.createElement('input');
        const imeEvent = keyboardEvent('Delete', imeInput);
        Object.defineProperty(imeEvent, 'isComposing', { value: true });
        component.onKeyDown(imeEvent);

        expect(JSON.stringify(component.sections())).toBe(before);
        expect(history.undoDepth()).toBe(0);
      });

      it('should perform undo/redo with zero HTTP calls', () => {
        venueApiSpy.saveLayout.calls.reset();
        venueApiSpy.toggleSeat.calls.reset();
        venueApiSpy.getEditableLayout.calls.reset();

        component.onSeatSelectionChanged(new Set(['s-00']));
        component.onBulkActivate(false);
        component.undo();
        component.redo();

        expect(venueApiSpy.saveLayout).not.toHaveBeenCalled();
        expect(venueApiSpy.toggleSeat).not.toHaveBeenCalled();
        expect(venueApiSpy.getEditableLayout).not.toHaveBeenCalled();
      });

      it('should expose named DOM controls for every mutation class', () => {
        component.onSeatSelectionChanged(new Set(['s-00']));
        fixture.detectChanges();
        const root: HTMLElement = fixture.nativeElement;
        const query = (selector: string): HTMLElement | null => root.querySelector(selector);

        expect(query('[aria-label="Undo layout change"]')).not.toBeNull();
        expect(query('[aria-label="Redo layout change"]')).not.toBeNull();
        expect(query('[aria-label="Zoom in"]')).not.toBeNull();
        expect(query('[aria-label="Zoom out"]')).not.toBeNull();
        expect(query('[aria-label="Fit venue layout to screen"]')).not.toBeNull();
        expect(query('#designerSnapStep')).not.toBeNull();
        expect(query('[data-testid="designer-announcement"]')).not.toBeNull();
        // Numeric geometry alternatives (section properties panel).
        expect(query('#secPropPosX')).not.toBeNull();
        expect(query('#secPropPosY')).not.toBeNull();
        expect(query('#secPropWidth')).not.toBeNull();
        expect(query('#secPropHeight')).not.toBeNull();
        expect(query('#secPropRot')).not.toBeNull();
        expect(query('#secPropZ')).not.toBeNull();
        // Activation / duplicate / delete / selection alternatives.
        expect(query('[aria-label="Bulk activate selected seats"]')).not.toBeNull();
        expect(query('[aria-label="Duplicate this section"]')).not.toBeNull();
        expect(
          query('[aria-label="Deactivate this section and its seats"]') ||
            query('[aria-label="Remove draft section"]'),
        ).not.toBeNull();
        expect(query('[aria-label="Select all seats in this section"]')).not.toBeNull();
      });

      it('should no-op undo/redo at history boundaries', () => {
        expect(history.canUndo()).toBeFalse();
        expect(history.canRedo()).toBeFalse();
        const before = JSON.stringify(component.sections());

        component.undo();
        component.redo();
        expect(JSON.stringify(component.sections())).toBe(before);

        component.onSeatSelectionChanged(new Set(['s-00']));
        component.onBulkActivate(false);
        const mutated = JSON.stringify(component.sections());
        expect(mutated).not.toBe(before);

        component.undo();
        expect(JSON.stringify(component.sections())).toBe(before);
        component.undo();
        expect(JSON.stringify(component.sections())).toBe(before);

        component.redo();
        expect(JSON.stringify(component.sections())).toBe(mutated);
        component.redo();
        expect(JSON.stringify(component.sections())).toBe(mutated);
      });

      it('should coalesce 50 pointer-move transforms into one undo entry', () => {
        const sec = component.currentSection()!;
        const baseX = sec.positionX;
        for (let i = 1; i <= 50; i++) {
          component.onSectionTransformChanged({
            sectionId: sec.sectionId,
            draftKey: getSectionDraftKey(sec),
            positionX: baseX + i,
            positionY: sec.positionY,
            width: sec.width,
            height: sec.height,
            rotationDeg: 0,
          });
        }
        expect(history.undoDepth()).toBe(1);
        component.endPointerGesture();
        expect(history.undoDepth()).toBe(1);

        component.undo();
        expect(component.currentSection()?.positionX).toBe(baseX);
      });

      it('should clear redo when a new command follows undo', () => {
        component.onSeatSelectionChanged(new Set(['s-00']));
        component.onBulkActivate(false);
        component.undo();
        expect(history.canRedo()).toBeTrue();

        component.onSeatSelectionChanged(new Set(['s-01']));
        component.onBulkActivate(false);
        expect(history.canRedo()).toBeFalse();
        const afterEdit = JSON.stringify(component.sections());
        component.redo();
        expect(JSON.stringify(component.sections())).toBe(afterEdit);
      });

      it('should clear history on save success and retain it on save failure', () => {
        component.onSeatSelectionChanged(new Set(['s-00']));
        component.onBulkActivate(false);
        expect(history.canUndo()).toBeTrue();

        component.saveLayout();
        expect(venueApiSpy.validateLayout).toHaveBeenCalledTimes(1);
        expect(venueApiSpy.saveLayout).toHaveBeenCalledTimes(1);
        expect(history.canUndo()).toBeFalse();
        expect(history.canRedo()).toBeFalse();
        expect(component.isDirty()).toBeFalse();

        component.onSeatSelectionChanged(new Set(['s-01']));
        component.onBulkActivate(false);
        expect(history.canUndo()).toBeTrue();

        venueApiSpy.validateLayout.and.returnValue(of(undefined));
        venueApiSpy.saveLayout.and.returnValue(
          throwError(() => ({ error: { message: 'Conflict' }, message: 'Conflict' })),
        );
        component.saveLayout();
        expect(history.canUndo()).toBeTrue();
        expect(component.isDirty()).toBeTrue();
      });

      it('should preserve layoutVersion through undo/redo', () => {
        expect(component.venue()?.layoutVersion).toBe(1);
        component.onSeatSelectionChanged(new Set(['s-00']));
        component.onBulkActivate(false);
        expect(component.venue()?.layoutVersion).toBe(1);
        component.undo();
        expect(component.venue()?.layoutVersion).toBe(1);
        component.redo();
        expect(component.venue()?.layoutVersion).toBe(1);
      });

      it('should guard dirty state via PendingChangesAware with consequence text', () => {
        expect(component.hasPendingChanges()).toBeFalse();
        component.onSeatSelectionChanged(new Set(['s-00']));
        component.onBulkActivate(false);
        expect(component.hasPendingChanges()).toBeTrue();

        const confirmSpy = spyOn(window, 'confirm').and.returnValue(true);
        expect(component.confirmDiscardChanges()).toBeTrue();
        const message = String(confirmSpy.calls.mostRecent().args[0]);
        expect(message).toContain('unsaved layout edits will be discarded');
      });

      it('should clear selection on Escape without reverting unrelated draft', () => {
        component.onSeatSelectionChanged(new Set(['s-00']));
        component.onBulkActivate(false);
        const mutated = JSON.stringify(component.sections());
        expect(component.hasPendingChanges()).toBeTrue();

        component.onKeyDown(keyboardEvent('Escape', document.createElement('div')));
        expect(JSON.stringify(component.sections())).toBe(mutated);
        expect(component['selectedSeatKeys']().size).toBe(0);
      });

      it('should set returnValue on beforeunload only while dirty (REV-001)', () => {
        const cleanEvent = {
          preventDefault: jasmine.createSpy('preventDefault'),
          returnValue: 'initial',
        } as unknown as BeforeUnloadEvent;
        component.onBeforeUnload(cleanEvent);
        expect(cleanEvent.preventDefault).not.toHaveBeenCalled();
        expect(cleanEvent.returnValue).toBe('initial');

        component.onSeatSelectionChanged(new Set(['s-00']));
        component.onBulkActivate(false);
        expect(component.hasPendingChanges()).toBeTrue();

        const dirtyEvent = {
          preventDefault: jasmine.createSpy('preventDefault'),
          returnValue: 'initial',
        } as unknown as BeforeUnloadEvent;
        component.onBeforeUnload(dirtyEvent);
        expect(dirtyEvent.preventDefault).toHaveBeenCalled();
        expect(dirtyEvent.returnValue).toBe('');
      });

      it('should deactivate persisted seats and remove draft seats on mixed Delete (REV-002)', () => {
        editorState.replaceDraft((draft) => {
          draft.sections = draft.sections.map((s) => ({
            ...s,
            seats: [
              ...s.seats,
              {
                seatId: null,
                rowLabel: 'C',
                seatNumber: 1,
                gridX: 2,
                gridY: 2,
                positionX: 100,
                positionY: 100,
                isActive: true,
              },
            ],
          }));
          return draft;
        });

        component.onSeatSelectionChanged(new Set(['s-00', '2_2']));
        component.onKeyDown(keyboardEvent('Delete', document.createElement('div')));

        const seats = component.currentSection()?.seats ?? [];
        expect(seats.find((s) => s.seatId === 's-00')?.isActive).toBeFalse();
        expect(seats.some((s) => s.seatId === null)).toBeFalse();
        expect(String(component['announcement']())).toContain('Deactivated');
        expect(String(component['announcement']())).toContain('draft');
      });

      it('should toggle seats locally with zero HTTP calls via the legacy entry point (REV-003)', () => {
        venueApiSpy.toggleSeat.calls.reset();
        const seat = component.currentSection()?.seats.find((s) => s.seatId === 's-00')!;
        expect(seat.isActive).toBeTrue();

        component.toggleSeat(seat);

        expect(venueApiSpy.toggleSeat).not.toHaveBeenCalled();
        expect(
          component.currentSection()?.seats.find((s) => s.seatId === 's-00')?.isActive,
        ).toBeFalse();
        expect(component.hasPendingChanges()).toBeTrue();
      });
    });

    describe('TASK-P11-010: atomic save and stale-version conflict recovery', () => {
      let history: LayoutHistoryService;

      beforeEach(() => {
        history = TestBed.inject(LayoutHistoryService);
        history.clear();
        editorState.applyServerSnapshot(JSON.parse(JSON.stringify(mockLayout)));
        history.clear();
        venueApiSpy.validateLayout.calls.reset();
        venueApiSpy.saveLayout.calls.reset();
        venueApiSpy.getEditableLayout.calls.reset();
        dialogSpy.open.calls.reset();
        dialogSpy.open.and.returnValue({ afterClosed: () => of(undefined) } as never);
        venueApiSpy.validateLayout.and.returnValue(of(undefined));
        venueApiSpy.saveLayout.and.returnValue(of(JSON.parse(JSON.stringify(mockLayout))));
        fixture.detectChanges();
      });

      function makeDirty(): void {
        component.onSeatSelectionChanged(new Set(['s-00']));
        component.onBulkActivate(false);
        expect(component.isDirty()).toBeTrue();
      }

      function staleConflictError(): unknown {
        return {
          status: 409,
          error: {
            status: 409,
            error: 'Conflict',
            errorCode: 'SF_409_CONFLICT',
            message: 'Stale layout version',
            path: '/api/admin/venues/v-100/layout',
            timestamp: '2026-09-05T00:00:00Z',
            correlationId: 'corr-conflict-1',
          },
          message: 'Stale layout version',
        };
      }

      it('should suppress duplicate clicks while a save flight is in flight', async () => {
        const { Subject } = await import('rxjs');
        const validateGate = new Subject<void>();
        venueApiSpy.validateLayout.and.returnValue(validateGate.asObservable());
        makeDirty();

        component.saveLayout();
        component.saveLayout();

        expect(venueApiSpy.validateLayout).toHaveBeenCalledTimes(1);
        expect(venueApiSpy.saveLayout).not.toHaveBeenCalled();

        validateGate.next(undefined);
        validateGate.complete();
        expect(venueApiSpy.saveLayout).toHaveBeenCalledTimes(1);
      });

      it('should send one immutable snapshot where PUT body equals the validated POST body', async () => {
        const { Subject } = await import('rxjs');
        const validateGate = new Subject<void>();
        venueApiSpy.validateLayout.and.returnValue(validateGate.asObservable());
        makeDirty();
        const draftBefore = JSON.stringify(component.sections());

        component.saveLayout();
        // Attempted edit during the validation flight must not alter the sent snapshot.
        component.onBulkRenumber(99);
        expect(JSON.stringify(component.sections())).toBe(draftBefore);

        validateGate.next(undefined);
        validateGate.complete();

        expect(venueApiSpy.validateLayout).toHaveBeenCalledTimes(1);
        expect(venueApiSpy.saveLayout).toHaveBeenCalledTimes(1);
        const postBody = venueApiSpy.validateLayout.calls.mostRecent().args[1];
        const putBody = venueApiSpy.saveLayout.calls.mostRecent().args[1];
        expect(putBody).toEqual(postBody);
        expect(putBody.layoutVersion).toBe(1);
      });

      it('should block local-invalid saves with no HTTP and focus the summary', () => {
        editorState.replaceDraft((draft) => {
          const copy = {
            ...draft.sections[0],
            sectionId: null,
            draftKey: 'draft-duplicate-name',
            name: 'ORCHESTRA',
          };
          draft.sections = [...draft.sections, copy];
          return draft;
        });
        venueApiSpy.validateLayout.calls.reset();
        venueApiSpy.saveLayout.calls.reset();

        component.saveLayout();

        expect(venueApiSpy.validateLayout).not.toHaveBeenCalled();
        expect(venueApiSpy.saveLayout).not.toHaveBeenCalled();
        expect(component.validationError()).toMatch(/already exists/);
      });

      it('should adopt server IDs/version on 200, clear history/dirty, and prune selection', () => {
        component.onSeatSelectionChanged(new Set(['s-00', 'ghost-seat']));
        component.onBulkActivate(false);
        expect(history.canUndo()).toBeTrue();

        const serverLayout = JSON.parse(JSON.stringify(mockLayout)) as VenueLayout;
        serverLayout.layoutVersion = 2;
        serverLayout.sections[0].seats[0].seatId = 's-00';
        venueApiSpy.saveLayout.and.returnValue(of(serverLayout));

        component.saveLayout();

        expect(component.venue()?.layoutVersion).toBe(2);
        expect(component.isDirty()).toBeFalse();
        expect(history.canUndo()).toBeFalse();
        expect(component.selectedSeatKeys().has('ghost-seat')).toBeFalse();
        expect(snackBarSpy.open).toHaveBeenCalledWith(
          jasmine.stringMatching(/v2/),
          'Close',
          jasmine.objectContaining({ panelClass: 'snack-success' }),
        );
      });

      it('should display 400 validation details, issue no PUT, and retain history/baseline', () => {
        makeDirty();
        const baselineBefore = JSON.stringify(editorState.baseline());
        venueApiSpy.validateLayout.and.returnValue(
          throwError(() => ({
            status: 400,
            error: {
              status: 400,
              error: 'Bad Request',
              errorCode: 'SF_400_VALIDATION',
              message: 'Layout validation failed',
              path: '/api/admin/venues/v-100/layout/validation',
              timestamp: '2026-09-05T00:00:00Z',
              correlationId: 'corr-400-1',
              validationErrors: [
                { field: 'sections[0].name', message: 'Section name is required' },
                { field: 'mystery.field', message: 'Unknown rule violated' },
              ],
            },
          })),
        );

        component.saveLayout();

        expect(venueApiSpy.saveLayout).not.toHaveBeenCalled();
        expect(component.serverValidationErrors().length).toBe(2);
        expect(component.saveCorrelationId()).toBe('corr-400-1');
        expect(history.canUndo()).toBeTrue();
        expect(component.isDirty()).toBeTrue();
        expect(JSON.stringify(editorState.baseline())).toBe(baselineBefore);
        expect(component.venue()?.layoutVersion).toBe(1);

        fixture.detectChanges();
        const root: HTMLElement = fixture.nativeElement;
        expect(root.textContent).toContain('sections[0].name');
        expect(root.textContent).toContain('corr-400-1');
      });

      it('should open the conflict dialog on 409 SF_409_CONFLICT with no retry PUT', () => {
        makeDirty();
        const draftBefore = JSON.stringify(component.sections());
        venueApiSpy.saveLayout.and.returnValue(throwError(() => staleConflictError()));

        component.saveLayout();

        expect(venueApiSpy.validateLayout).toHaveBeenCalledTimes(1);
        expect(venueApiSpy.saveLayout).toHaveBeenCalledTimes(1);
        expect(dialogSpy.open).toHaveBeenCalledTimes(1);
        const dialogArgs = dialogSpy.open.calls.mostRecent().args as unknown as [
          unknown,
          { data: { localVersion: number; snapshotJson: string } },
        ];
        expect(dialogArgs[1].data.localVersion).toBe(1);
        expect(dialogArgs[1].data.snapshotJson).toContain('"layoutVersion"');
        expect(JSON.stringify(component.sections())).toBe(draftBefore);
        expect(history.canUndo()).toBeTrue();
        expect(component.venue()?.layoutVersion).toBe(1);
      });

      it('should use the generic failure path for 409 with any other error code', () => {
        makeDirty();
        venueApiSpy.saveLayout.and.returnValue(
          throwError(() => ({
            status: 409,
            error: {
              status: 409,
              error: 'Conflict',
              errorCode: 'SF_409_SEAT_TAKEN',
              message: 'Seat already taken',
              path: '/x',
              timestamp: '2026-09-05T00:00:00Z',
            },
          })),
        );

        component.saveLayout();

        expect(dialogSpy.open).not.toHaveBeenCalled();
        expect(component.validationError()).toContain('Seat already taken');
        expect(history.canUndo()).toBeTrue();
        expect(component.isDirty()).toBeTrue();
      });

      it('should replace draft/baseline/history on reload success', () => {
        makeDirty();
        expect(history.canUndo()).toBeTrue();
        const confirmSpy = spyOn(window, 'confirm').and.returnValue(true);
        const serverLayout = JSON.parse(JSON.stringify(mockLayout)) as VenueLayout;
        serverLayout.layoutVersion = 5;
        serverLayout.sections[0].name = 'Server Orchestra';
        venueApiSpy.getEditableLayout.and.returnValue(of(serverLayout));

        component.reloadServerLayout();

        expect(confirmSpy).toHaveBeenCalled();
        expect(component.venue()?.layoutVersion).toBe(5);
        expect(component.currentSection()?.name).toBe('Server Orchestra');
        expect(history.canUndo()).toBeFalse();
        expect(component.isDirty()).toBeFalse();
      });

      it('should retain the local draft when reload fails', () => {
        makeDirty();
        const draftBefore = JSON.stringify(component.sections());
        spyOn(window, 'confirm').and.returnValue(true);
        venueApiSpy.getEditableLayout.and.returnValue(
          throwError(() => ({ status: 500, error: { message: 'Reload boom' } })),
        );

        component.reloadServerLayout();

        expect(JSON.stringify(component.sections())).toBe(draftBefore);
        expect(history.canUndo()).toBeTrue();
        expect(component.validationError()).toContain('Reload boom');
      });

      it('should render hostile server messages as escaped text without HTML injection', () => {
        makeDirty();
        const hostile = '<img src=x onerror=alert(1)>';
        venueApiSpy.validateLayout.and.returnValue(
          throwError(() => ({
            status: 400,
            error: {
              status: 400,
              error: 'Bad Request',
              errorCode: 'SF_400_VALIDATION',
              message: hostile,
              path: '/x',
              timestamp: '2026-09-05T00:00:00Z',
              correlationId: 'corr-x',
              validationErrors: [{ field: 'sections[0].name', message: hostile }],
            },
          })),
        );

        component.saveLayout();
        fixture.detectChanges();

        const root: HTMLElement = fixture.nativeElement;
        expect(root.querySelector('img')).toBeNull();
        expect(root.textContent).toContain('<img src=x onerror=alert(1)>');
      });

      it('should never increment layoutVersion locally on failed saves', () => {
        makeDirty();
        expect(component.venue()?.layoutVersion).toBe(1);
        venueApiSpy.saveLayout.and.returnValue(throwError(() => staleConflictError()));

        component.saveLayout();

        expect(component.venue()?.layoutVersion).toBe(1);
        component.undo();
        expect(component.venue()?.layoutVersion).toBe(1);
      });

      it('should render duplicate-field 400 violations without @for track errors (REV-001)', () => {
        makeDirty();
        venueApiSpy.validateLayout.and.returnValue(
          throwError(() => ({
            status: 400,
            error: {
              status: 400,
              error: 'Bad Request',
              errorCode: 'SF_400_VALIDATION',
              message: 'Layout validation failed',
              path: '/api/admin/venues/v-100/layout/validation',
              timestamp: '2026-09-05T00:00:00Z',
              correlationId: 'corr-dup-field',
              validationErrors: [
                { field: 'sections[0].name', message: 'First violation' },
                { field: 'sections[0].name', message: 'Second violation' },
              ],
            },
          })),
        );

        component.saveLayout();
        fixture.detectChanges();

        expect(venueApiSpy.saveLayout).not.toHaveBeenCalled();
        expect(component.serverValidationErrors().length).toBe(2);
        const root: HTMLElement = fixture.nativeElement;
        expect(root.textContent).toContain('First violation');
        expect(root.textContent).toContain('Second violation');
      });

      it('should suppress a second save issued while the PUT flight is pending', async () => {
        const { Subject } = await import('rxjs');
        const putGate = new Subject<VenueLayout>();
        venueApiSpy.validateLayout.and.returnValue(of(undefined));
        venueApiSpy.saveLayout.and.returnValue(putGate.asObservable());
        makeDirty();

        component.saveLayout();
        expect(venueApiSpy.validateLayout).toHaveBeenCalledTimes(1);
        expect(venueApiSpy.saveLayout).toHaveBeenCalledTimes(1);

        component.saveLayout();
        expect(venueApiSpy.validateLayout).toHaveBeenCalledTimes(1);
        expect(venueApiSpy.saveLayout).toHaveBeenCalledTimes(1);

        putGate.next(JSON.parse(JSON.stringify(mockLayout)) as VenueLayout);
        putGate.complete();
        expect(venueApiSpy.saveLayout).toHaveBeenCalledTimes(1);
      });

      it('should take the generic path on validation POST 500 with no PUT and retained draft', () => {
        makeDirty();
        const draftBefore = JSON.stringify(component.sections());
        const baselineBefore = JSON.stringify(editorState.baseline());
        venueApiSpy.validateLayout.and.returnValue(
          throwError(() => ({
            status: 500,
            error: {
              status: 500,
              error: 'Internal Server Error',
              errorCode: 'SF_500_INTERNAL',
              message: 'Validation service unavailable',
              path: '/api/admin/venues/v-100/layout/validation',
              timestamp: '2026-09-05T00:00:00Z',
              correlationId: 'corr-500-1',
            },
          })),
        );

        component.saveLayout();

        expect(venueApiSpy.saveLayout).not.toHaveBeenCalled();
        expect(component.validationError()).toContain('Validation service unavailable');
        expect(component.saveCorrelationId()).toBe('corr-500-1');
        expect(JSON.stringify(component.sections())).toBe(draftBefore);
        expect(JSON.stringify(editorState.baseline())).toBe(baselineBefore);
        expect(history.canUndo()).toBeTrue();
        expect(component.isDirty()).toBeTrue();
      });

      it('should retain draft and issue no reload on dialog dismiss (Escape/undefined)', () => {
        makeDirty();
        const draftBefore = JSON.stringify(component.sections());
        dialogSpy.open.and.returnValue({ afterClosed: () => of(undefined) } as never);
        venueApiSpy.saveLayout.and.returnValue(throwError(() => staleConflictError()));
        venueApiSpy.getEditableLayout.calls.reset();

        component.saveLayout();

        expect(dialogSpy.open).toHaveBeenCalledTimes(1);
        expect(venueApiSpy.getEditableLayout).not.toHaveBeenCalled();
        expect(JSON.stringify(component.sections())).toBe(draftBefore);
        expect(history.canUndo()).toBeTrue();
        expect(component.venue()?.layoutVersion).toBe(1);
      });
    });
  });
});
