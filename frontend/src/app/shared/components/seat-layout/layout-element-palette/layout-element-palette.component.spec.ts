import { ComponentFixture, TestBed } from '@angular/core/testing';
import {
  DEFAULT_ELEMENT_CONFIGS,
  isValidLayoutElementType,
  LayoutElementPaletteComponent,
} from './layout-element-palette.component';
import { VenueLayoutElement, VenueSectionLayout } from '../../../../models/venue.model';

describe('LayoutElementPaletteComponent', () => {
  let component: LayoutElementPaletteComponent;
  let fixture: ComponentFixture<LayoutElementPaletteComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [LayoutElementPaletteComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(LayoutElementPaletteComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('initializes cleanly without validation errors', () => {
    expect(component).toBeTruthy();
    expect(component.validationError()).toBeNull();
  });

  describe('Contract Default Geometries (Task §5)', () => {
    it('creates STAGE with exact defaults: label Stage, x=100, y=40, w=400, h=80, rot=0', () => {
      let created: VenueLayoutElement | null = null;
      component.elementCreated.subscribe((el) => (created = el));

      component.onAddElement('STAGE');

      expect(created).not.toBeNull();
      expect(created!.elementId).toBeNull();
      expect(created!.type).toBe('STAGE');
      expect(created!.label).toBe('Stage');
      expect(created!.geometry).toEqual({
        x: 100,
        y: 40,
        width: 400,
        height: 80,
        rotationDeg: 0,
      });
      expect(created!.zIndex).toBe(0);
    });

    it('creates AISLE with exact defaults: label null, x=100, y=160, w=300, h=40, rot=0', () => {
      let created: VenueLayoutElement | null = null;
      component.elementCreated.subscribe((el) => (created = el));

      component.onAddElement('AISLE');

      expect(created).not.toBeNull();
      expect(created!.elementId).toBeNull();
      expect(created!.type).toBe('AISLE');
      expect(created!.label).toBeNull();
      expect(created!.geometry).toEqual({
        x: 100,
        y: 160,
        width: 300,
        height: 40,
        rotationDeg: 0,
      });
    });

    it('creates LABEL with exact defaults: label Label, x=100, y=240, w=200, h=44, rot=0', () => {
      let created: VenueLayoutElement | null = null;
      component.elementCreated.subscribe((el) => (created = el));

      component.onAddElement('LABEL');

      expect(created).not.toBeNull();
      expect(created!.elementId).toBeNull();
      expect(created!.type).toBe('LABEL');
      expect(created!.label).toBe('Label');
      expect(created!.geometry).toEqual({
        x: 100,
        y: 240,
        width: 200,
        height: 44,
        rotationDeg: 0,
      });
    });

    it('creates BARRIER with exact defaults: label null, x=100, y=320, w=300, h=20, rot=0', () => {
      let created: VenueLayoutElement | null = null;
      component.elementCreated.subscribe((el) => (created = el));

      component.onAddElement('BARRIER');

      expect(created).not.toBeNull();
      expect(created!.elementId).toBeNull();
      expect(created!.type).toBe('BARRIER');
      expect(created!.label).toBeNull();
      expect(created!.geometry).toEqual({
        x: 100,
        y: 320,
        width: 300,
        height: 20,
        rotationDeg: 0,
      });
    });

    it('creates DECORATION with exact defaults: label null, x=100, y=380, w=100, h=100, rot=0', () => {
      let created: VenueLayoutElement | null = null;
      component.elementCreated.subscribe((el) => (created = el));

      component.onAddElement('DECORATION');

      expect(created).not.toBeNull();
      expect(created!.elementId).toBeNull();
      expect(created!.type).toBe('DECORATION');
      expect(created!.label).toBeNull();
      expect(created!.geometry).toEqual({
        x: 100,
        y: 380,
        width: 100,
        height: 100,
        rotationDeg: 0,
      });
    });
  });

  describe('Z-Index Calculation', () => {
    it('uses next available z-index above existing elements and sections', () => {
      const existingElements: VenueLayoutElement[] = [
        {
          elementId: 'elem-1',
          type: 'STAGE',
          label: 'Stage',
          geometry: { x: 100, y: 100, width: 200, height: 50, rotationDeg: 0 },
          zIndex: 4,
        },
      ];
      const existingSections: VenueSectionLayout[] = [
        {
          sectionId: 'sec-1',
          name: 'Main',
          rowCount: 5,
          colCount: 5,
          isActive: true,
          positionX: 0,
          positionY: 0,
          width: 200,
          height: 200,
          rotationDeg: 0,
          zIndex: 8,
          shapeMetadata: null,
          seats: [],
        },
      ];

      fixture.componentRef.setInput('existingElements', existingElements);
      fixture.componentRef.setInput('existingSections', existingSections);
      fixture.detectChanges();

      // Max z-index is 8, so next should be 9
      expect(component.nextZIndex()).toBe(9);

      let created: VenueLayoutElement | null = null;
      component.elementCreated.subscribe((el) => (created = el));
      component.onAddElement('STAGE');

      expect(created!.zIndex).toBe(9);
    });

    it('clamps next z-index to MAX_Z_INDEX (1000)', () => {
      fixture.componentRef.setInput('defaultZIndex', 1500);
      fixture.detectChanges();

      expect(component.nextZIndex()).toBe(1000);
    });
  });

  describe('Closed-Union Guard and Negative Tests', () => {
    it('rejects unsupported element type without mutating or emitting', () => {
      let emitted = false;
      component.elementCreated.subscribe(() => (emitted = true));

      component.onAddElement('CUSTOM_SVG' as any);

      expect(emitted).toBeFalse();
      expect(component.validationError()).toContain('Unsupported layout element type');
      fixture.detectChanges();

      const alert = fixture.nativeElement.querySelector('.palette-error-message');
      expect(alert).not.toBeNull();
      expect(alert.textContent).toContain('CUSTOM_SVG');
    });

    it('isValidLayoutElementType returns true only for the 5 permitted types', () => {
      expect(isValidLayoutElementType('STAGE')).toBeTrue();
      expect(isValidLayoutElementType('AISLE')).toBeTrue();
      expect(isValidLayoutElementType('LABEL')).toBeTrue();
      expect(isValidLayoutElementType('BARRIER')).toBeTrue();
      expect(isValidLayoutElementType('DECORATION')).toBeTrue();

      expect(isValidLayoutElementType('SEAT')).toBeFalse();
      expect(isValidLayoutElementType('PATH')).toBeFalse();
      expect(isValidLayoutElementType('POLYGON')).toBeFalse();
      expect(isValidLayoutElementType(null)).toBeFalse();
      expect(isValidLayoutElementType(undefined)).toBeFalse();
    });
  });

  describe('Accessibility Requirements', () => {
    it('renders all 5 palette buttons with accessible names and min 44px targets', () => {
      const buttons: HTMLButtonElement[] = Array.from(
        fixture.nativeElement.querySelectorAll('.palette-btn'),
      );
      expect(buttons.length).toBe(5);

      for (const btn of buttons) {
        expect(btn.getAttribute('aria-label')).toBeTruthy();
        expect(btn.classList.contains('min-h-[44px]')).toBeTrue();
        expect(btn.classList.contains('min-w-[44px]')).toBeTrue();
        expect(btn.classList.contains('focus-visible:ring-2')).toBeTrue();
      }
    });

    it('activates button via click event', () => {
      let createdType: string | null = null;
      component.elementCreated.subscribe((el) => (createdType = el.type));

      const stageBtn = fixture.nativeElement.querySelector(
        'button[aria-label="Add Stage element"]',
      );
      stageBtn.click();

      expect<string | null>(createdType).toBe('STAGE');
    });
  });
});
