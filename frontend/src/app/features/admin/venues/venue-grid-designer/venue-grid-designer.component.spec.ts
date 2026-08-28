import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, ActivatedRoute } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';
import {
  getRowLabel,
  VenueGridDesignerComponent,
} from './venue-grid-designer.component';
import { AdminVenueApiService } from '../../../../services/admin-venue-api.service';
import { VenueLayout, VenueSectionLayout, VenueSectionSeat } from '../../../../models/venue.model';

describe('VenueGridDesignerComponent', () => {
  let component: VenueGridDesignerComponent;
  let fixture: ComponentFixture<VenueGridDesignerComponent>;
  let venueApiSpy: jasmine.SpyObj<AdminVenueApiService>;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;

  const mockSeats: VenueSectionSeat[] = [
    { seatId: 's-00', rowLabel: 'A', seatNumber: 1, gridX: 0, gridY: 0, isActive: true },
    { seatId: 's-01', rowLabel: 'A', seatNumber: 2, gridX: 1, gridY: 0, isActive: true },
    { seatId: 's-10', rowLabel: 'B', seatNumber: 1, gridX: 0, gridY: 1, isActive: true },
    { seatId: 's-11', rowLabel: 'B', seatNumber: 2, gridX: 1, gridY: 1, isActive: false },
  ];

  const mockSection: VenueSectionLayout = {
    sectionId: 'sec-1',
    name: 'Orchestra',
    rowCount: 2,
    colCount: 2,
    seats: mockSeats,
  };

  const mockLayout: VenueLayout = {
    venueId: 'v-100',
    name: 'National Opera',
    capacity: 1000,
    sections: [mockSection],
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
        'getVenueLayout',
        'createSection',
        'deleteSection',
        'toggleSeat',
      ]);
      snackBarSpy = jasmine.createSpyObj('MatSnackBar', ['open']);

      venueApiSpy.getVenueLayout.and.returnValue(of(mockLayout));

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

      fixture = TestBed.createComponent(VenueGridDesignerComponent);
      component = fixture.componentInstance;
      fixture.detectChanges();
    });

    it('should initialize and load venue layout', () => {
      expect(component).toBeTruthy();
      expect(venueApiSpy.getVenueLayout).toHaveBeenCalledWith('v-100');
      expect(component.venue()?.name).toBe('National Opera');
      expect(component.sections().length).toBe(1);
      expect(component.currentSection()?.name).toBe('Orchestra');
    });

    it('should compute grid matrix and active/inactive counts accurately', () => {
      expect(component.currentSectionTotalCount()).toBe(4);
      expect(component.currentSectionActiveCount()).toBe(3);
      expect(component.currentSectionInactiveCount()).toBe(1);
      expect(component.totalConfiguredActiveSeats()).toBe(3);

      const matrix = component.gridMatrix();
      expect(matrix.length).toBe(2);
      expect(matrix[0].rowLabel).toBe('A');
      expect(matrix[1].rowLabel).toBe('B');
      expect(matrix[0].seats.length).toBe(2);
    });

    it('should toggle seat active state optimistically and call API', () => {
      const targetSeat = mockSeats[0]; // s-00, active: true
      const updatedSeat: VenueSectionSeat = { ...targetSeat, isActive: false };
      venueApiSpy.toggleSeat.and.returnValue(of(updatedSeat));

      component.toggleSeat(targetSeat);

      expect(venueApiSpy.toggleSeat).toHaveBeenCalledWith('v-100', 'sec-1', 's-00', false);

      const section = component.currentSection();
      const toggled = section?.seats.find((s) => s.seatId === 's-00');
      expect(toggled?.isActive).toBeFalse();
    });

    it('should rollback seat state if API call fails', () => {
      const targetSeat = mockSeats[0]; // active: true
      venueApiSpy.toggleSeat.and.returnValue(
        throwError(() => ({ error: { message: 'Server error' } }))
      );

      component.toggleSeat(targetSeat);

      expect(venueApiSpy.toggleSeat).toHaveBeenCalled();
      expect(snackBarSpy.open).toHaveBeenCalled();

      // State should have reverted to true
      const section = component.currentSection();
      const reverted = section?.seats.find((s) => s.seatId === 's-00');
      expect(reverted?.isActive).toBeTrue();
    });

    it('should bulk toggle row seats', () => {
      venueApiSpy.toggleSeat.and.returnValue(of({} as any));

      // Row 0 has 2 seats (both active). Bulk toggle to inactive:
      component.bulkToggleRow(0, false);

      expect(venueApiSpy.toggleSeat).toHaveBeenCalledTimes(2);
    });

    it('should bulk toggle column seats', () => {
      venueApiSpy.toggleSeat.and.returnValue(of({} as any));

      // Col 0 has seat (0,0) [active] and (1,0) [active]. Bulk toggle to inactive:
      component.bulkToggleColumn(0, false);

      expect(venueApiSpy.toggleSeat).toHaveBeenCalledTimes(2);
    });

    it('should open and close add section modal', () => {
      component.openAddSectionModal();
      expect(component.showAddSectionModal()).toBeTrue();

      component.closeAddSectionModal();
      expect(component.showAddSectionModal()).toBeFalse();
    });

    it('should create new section successfully', () => {
      const newSec: VenueSectionLayout = {
        sectionId: 'sec-new',
        name: 'Balcony',
        rowCount: 3,
        colCount: 4,
        seats: [],
      };

      venueApiSpy.createSection.and.returnValue(of(newSec));

      component.openAddSectionModal();
      component.sectionForm.patchValue({
        name: 'Balcony',
        rowCount: 3,
        colCount: 4,
      });

      component.createSection();

      expect(venueApiSpy.createSection).toHaveBeenCalledWith('v-100', {
        name: 'Balcony',
        rowCount: 3,
        colCount: 4,
      });
      expect(component.showAddSectionModal()).toBeFalse();
    });

    it('should adjust zoom levels within constraints', () => {
      component.setZoom(120);
      expect(component.zoomLevel()).toBe(120);

      component.setZoom(300); // capped at 175
      expect(component.zoomLevel()).toBe(175);

      component.resetZoom();
      expect(component.zoomLevel()).toBe(100);
    });

    it('should open and close delete section confirm modal', () => {
      component.openDeleteSectionConfirm();
      expect(component.showDeleteSectionConfirm()).toBeTrue();

      component.closeDeleteSectionConfirm();
      expect(component.showDeleteSectionConfirm()).toBeFalse();
    });

    it('should delete section successfully and reload layout', () => {
      venueApiSpy.deleteSection.and.returnValue(of(undefined));
      component.openDeleteSectionConfirm();

      component.confirmDeleteSection();

      expect(venueApiSpy.deleteSection).toHaveBeenCalledWith('v-100', 'sec-1');
      expect(component.showDeleteSectionConfirm()).toBeFalse();
      expect(snackBarSpy.open).toHaveBeenCalled();
      expect(venueApiSpy.getVenueLayout).toHaveBeenCalledWith('v-100');
    });
  });
});
