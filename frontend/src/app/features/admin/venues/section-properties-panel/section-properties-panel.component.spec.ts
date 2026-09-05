import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SectionPropertiesPanelComponent } from './section-properties-panel.component';
import { VenueSectionLayout, VenueSectionSeat } from '../../../../models/venue.model';
import { By } from '@angular/platform-browser';

describe('SectionPropertiesPanelComponent', () => {
  let component: SectionPropertiesPanelComponent;
  let fixture: ComponentFixture<SectionPropertiesPanelComponent>;

  const mockSeats: VenueSectionSeat[] = [
    {
      seatId: 'seat-1',
      rowLabel: 'A',
      seatNumber: 1,
      gridX: 0,
      gridY: 0,
      positionX: 20,
      positionY: 20,
      isActive: true,
    },
    {
      seatId: 'seat-2',
      rowLabel: 'A',
      seatNumber: 2,
      gridX: 1,
      gridY: 0,
      positionX: 60,
      positionY: 20,
      isActive: true,
    },
  ];

  const mockSection: VenueSectionLayout = {
    sectionId: 'sec-101',
    name: 'Main Orchestra',
    rowCount: 2,
    colCount: 2,
    isActive: true,
    positionX: 50,
    positionY: 80,
    width: 400,
    height: 300,
    rotationDeg: 0,
    zIndex: 1,
    shapeMetadata: null,
    seats: mockSeats,
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SectionPropertiesPanelComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(SectionPropertiesPanelComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('section', mockSection);
    fixture.componentRef.setInput('venueCapacity', 500);
    fixture.componentRef.setInput('totalConfiguredActiveSeats', 2);
    fixture.componentRef.setInput('selectedSeatKeys', new Set<string>());
    fixture.detectChanges();
  });

  it('should create and display section details', () => {
    expect(component).toBeTruthy();
    expect(fixture.nativeElement.textContent).toContain('Main Orchestra');
    expect(component.isSectionActive()).toBeTrue();
    expect(component.canRemove()).toBeFalse(); // saved section has non-null ID
  });

  it('should emit duplicateSection when duplicate button is clicked', () => {
    spyOn(component.duplicateSection, 'emit');
    const dupBtn = fixture.debugElement.query(
      By.css('button[aria-label="Duplicate this section"]'),
    );
    dupBtn.triggerEventHandler('click', null);
    expect(component.duplicateSection.emit).toHaveBeenCalled();
  });

  it('should emit deactivateSection when section is active and deactivate is clicked', () => {
    spyOn(component.deactivateSection, 'emit');
    const deactBtn = fixture.debugElement.query(
      By.css('button[aria-label="Deactivate this section and its seats"]'),
    );
    deactBtn.triggerEventHandler('click', null);
    expect(component.deactivateSection.emit).toHaveBeenCalled();
  });

  it('should emit reactivateSection when section is inactive and reactivate is clicked', () => {
    fixture.componentRef.setInput('section', { ...mockSection, isActive: false });
    fixture.detectChanges();

    spyOn(component.reactivateSection, 'emit');
    const reactBtn = fixture.debugElement.query(
      By.css('button[aria-label="Reactivate this section"]'),
    );
    reactBtn.triggerEventHandler('click', null);
    expect(component.reactivateSection.emit).toHaveBeenCalled();
  });

  it('should disable remove button for saved sections and enable for draft sections', () => {
    const removeBtn = fixture.debugElement.query(
      By.css('button[aria-label="Remove draft section"]'),
    );
    expect(removeBtn.nativeElement.disabled).toBeTrue();

    // Draft section with null ID
    fixture.componentRef.setInput('section', { ...mockSection, sectionId: null });
    fixture.detectChanges();
    expect(removeBtn.nativeElement.disabled).toBeFalse();

    spyOn(component.removeSection, 'emit');
    removeBtn.triggerEventHandler('click', null);
    expect(component.removeSection.emit).toHaveBeenCalled();
  });

  it('should emit sectionUpdated when geometry inputs change', () => {
    spyOn(component.sectionUpdated, 'emit');

    const posXInput = fixture.debugElement.query(By.css('#secPropPosX')).nativeElement;
    posXInput.value = '150';
    posXInput.dispatchEvent(new Event('change'));

    expect(component.sectionUpdated.emit).toHaveBeenCalledWith({ positionX: 150 });
  });

  it('should emit generateSeats when generator is submitted', () => {
    spyOn(component.generateSeats, 'emit');
    component.showGeneratorAccordion.set(true);
    fixture.detectChanges();

    component.generatorRows.set(5);
    component.generatorCols.set(10);
    component.triggerGenerateSeats();

    expect(component.generateSeats.emit).toHaveBeenCalledWith(
      jasmine.objectContaining({
        rowCount: 5,
        colCount: 10,
        sectionWidth: 400,
        sectionHeight: 300,
      }),
    );
  });

  it('should emit seatSelectionChanged when seats are selected or cleared', () => {
    spyOn(component.seatSelectionChanged, 'emit');

    component.selectAllSeats();
    expect(component.seatSelectionChanged.emit).toHaveBeenCalledWith(new Set(['seat-1', 'seat-2']));

    component.clearSelection();
    expect(component.seatSelectionChanged.emit).toHaveBeenCalledWith(new Set());
  });

  it('should emit bulk actions (activate, translate, setRowLabel, renumber)', () => {
    spyOn(component.bulkActivate, 'emit');
    spyOn(component.bulkTranslate, 'emit');
    spyOn(component.bulkSetRowLabel, 'emit');
    spyOn(component.bulkRenumber, 'emit');

    fixture.componentRef.setInput('selectedSeatKeys', new Set(['seat-1']));
    fixture.detectChanges();

    component.triggerBulkActivate(false);
    expect(component.bulkActivate.emit).toHaveBeenCalledWith(false);

    component.translateDeltaX.set(10);
    component.translateDeltaY.set(20);
    component.triggerBulkTranslate();
    expect(component.bulkTranslate.emit).toHaveBeenCalledWith({ deltaX: 10, deltaY: 20 });

    component.bulkRowLabelText.set('LOGE');
    component.triggerBulkSetRowLabel();
    expect(component.bulkSetRowLabel.emit).toHaveBeenCalledWith('LOGE');

    component.bulkStartNumberVal.set(5);
    component.triggerBulkRenumber();
    expect(component.bulkRenumber.emit).toHaveBeenCalledWith(5);
  });

  it('should render validation message inside alert with aria-live="polite"', () => {
    fixture.componentRef.setInput('validationMessage', 'Active seat count exceeds venue capacity');
    fixture.detectChanges();

    const alert = fixture.debugElement.query(By.css('[role="alert"]'));
    expect(alert).toBeTruthy();
    expect(alert.attributes['aria-live']).toBe('polite');
    expect(alert.nativeElement.textContent).toContain('Active seat count exceeds venue capacity');
  });

  describe('Color themes and seat coloring', () => {
    it('should emit sectionColorChanged when a preset color swatch is chosen', () => {
      spyOn(component.sectionColorChanged, 'emit');
      component.selectSectionColor('#8B5CF6');
      expect(component.sectionColorChanged.emit).toHaveBeenCalledWith('#8B5CF6');
    });

    it('should emit seatColorAssigned when assigning color to selected seats', () => {
      fixture.componentRef.setInput('selectedSeatKeys', new Set(['seat-1']));
      fixture.detectChanges();

      spyOn(component.seatColorAssigned, 'emit');
      component.assignColorToSelectedSeats('#EF4444');
      expect(component.seatColorAssigned.emit).toHaveBeenCalledWith({
        seatKeys: ['seat-1'],
        color: '#EF4444',
      });
    });
  });

  describe('Row and Column Aisle and Quick Operations', () => {
    it('should emit rowToggled with row and desired active state', () => {
      spyOn(component.rowToggled, 'emit');
      component.triggerRowToggle('A', false);
      expect(component.rowToggled.emit).toHaveBeenCalledWith({ rowLabel: 'A', active: false });
    });

    it('should emit colToggled with column index and desired active state', () => {
      spyOn(component.colToggled, 'emit');
      component.triggerColToggle(1, false);
      expect(component.colToggled.emit).toHaveBeenCalledWith({ colIndex: 1, active: false });
    });

    it('should emit centerAisleCreated, dualAislesCreated, and allSeatsActivated', () => {
      spyOn(component.centerAisleCreated, 'emit');
      spyOn(component.dualAislesCreated, 'emit');
      spyOn(component.allSeatsActivated, 'emit');

      component.triggerCenterAisle();
      expect(component.centerAisleCreated.emit).toHaveBeenCalled();

      component.triggerDualAisles();
      expect(component.dualAislesCreated.emit).toHaveBeenCalled();

      component.triggerActivateAllSeats();
      expect(component.allSeatsActivated.emit).toHaveBeenCalled();
    });

    it('should emit rowAppended and colAppended', () => {
      spyOn(component.rowAppended, 'emit');
      spyOn(component.colAppended, 'emit');

      component.triggerAppendRow();
      expect(component.rowAppended.emit).toHaveBeenCalled();

      component.triggerAppendCol();
      expect(component.colAppended.emit).toHaveBeenCalled();
    });
  });
});
