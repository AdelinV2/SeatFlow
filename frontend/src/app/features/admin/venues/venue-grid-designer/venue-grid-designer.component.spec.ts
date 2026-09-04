import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, ActivatedRoute } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';
import { getRowLabel, VenueGridDesignerComponent } from './venue-grid-designer.component';
import { AdminVenueApiService } from '../../../../services/admin-venue-api.service';
import { VenueLayout, VenueSectionLayout, VenueSectionSeat } from '../../../../models/venue.model';
import { VenueLayoutEditorStateService } from '../../../../services/venue-layout-editor-state.service';
import { SeatLayoutGeneratorService } from '../../../../services/seat-layout-generator.service';

describe('VenueGridDesignerComponent', () => {
  let component: VenueGridDesignerComponent;
  let fixture: ComponentFixture<VenueGridDesignerComponent>;
  let venueApiSpy: jasmine.SpyObj<AdminVenueApiService>;
  let editorState: VenueLayoutEditorStateService;
  let generator: SeatLayoutGeneratorService;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;

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

      venueApiSpy.getEditableLayout.and.returnValue(of(JSON.parse(JSON.stringify(mockLayout))));
      venueApiSpy.getVenueLayout.and.returnValue(of(JSON.parse(JSON.stringify(mockLayout))));
      venueApiSpy.saveLayout.and.returnValue(of(JSON.parse(JSON.stringify(mockLayout))));

      await TestBed.configureTestingModule({
        imports: [VenueGridDesignerComponent],
        providers: [
          provideHttpClient(),
          provideHttpClientTesting(),
          provideRouter([]),
          { provide: AdminVenueApiService, useValue: venueApiSpy },
          { provide: MatSnackBar, useValue: snackBarSpy },
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
        const preUpdateSeats = JSON.stringify(component.currentSection()?.seats);

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
        const postUpdateSeats = JSON.stringify(component.currentSection()?.seats);
        expect(postUpdateSeats).toBe(preUpdateSeats);
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

    describe('Save and Discard workflow', () => {
      it('should save layout draft and display success notification', () => {
        component.onSeatSelectionChanged(new Set(['s-00']));
        component.onBulkActivate(false);
        expect(component.isDirty()).toBeTrue();

        component.saveLayout();

        expect(venueApiSpy.saveLayout).toHaveBeenCalled();
        expect(snackBarSpy.open).toHaveBeenCalledWith(
          'Venue layout saved successfully!',
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
  });
});
