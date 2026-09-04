import { ComponentFixture, TestBed } from '@angular/core/testing';
import { LayoutCanvasComponent, SectionTransformChangeEvent } from './layout-canvas.component';
import { VenueLayoutElement, VenueSectionLayout } from '../../../../models/venue.model';

describe('LayoutCanvasComponent', () => {
  let component: LayoutCanvasComponent;
  let fixture: ComponentFixture<LayoutCanvasComponent>;

  const mockSections: VenueSectionLayout[] = [
    {
      sectionId: 'sec-orchestra',
      name: 'Orchestra',
      rowCount: 2,
      colCount: 2,
      isActive: true,
      positionX: 100,
      positionY: 100,
      width: 200,
      height: 150,
      rotationDeg: 0,
      zIndex: 0,
      shapeMetadata: null,
      seats: [
        {
          seatId: 'seat-1',
          rowLabel: 'A',
          seatNumber: 1,
          gridX: 0,
          gridY: 0,
          positionX: 30,
          positionY: 40,
          isActive: true,
        },
      ],
    },
    {
      sectionId: 'sec-balcony',
      name: 'Balcony',
      rowCount: 2,
      colCount: 2,
      isActive: true,
      positionX: 400,
      positionY: 100,
      width: 200,
      height: 150,
      rotationDeg: 0,
      zIndex: 5,
      shapeMetadata: null,
      seats: [],
    },
  ];

  const mockElements: VenueLayoutElement[] = [
    {
      elementId: 'elem-stage',
      type: 'STAGE',
      label: 'Main Stage',
      geometry: { x: 200, y: 20, width: 300, height: 60, rotationDeg: 0 },
      zIndex: -1,
    },
    {
      elementId: 'elem-aisle',
      type: 'AISLE',
      label: 'Center Aisle',
      geometry: { x: 320, y: 100, width: 60, height: 200, rotationDeg: 0 },
      zIndex: 1,
    },
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [LayoutCanvasComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(LayoutCanvasComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('sections', mockSections);
    fixture.componentRef.setInput('elements', mockElements);
    fixture.detectChanges();
  });

  describe('Initialization and Rendering', () => {
    it('initializes with default zoom 1.0, pan 0,0, and not dragging', () => {
      expect(component.zoomLevel()).toBe(1.0);
      expect(component.panX()).toBe(0);
      expect(component.panY()).toBe(0);
      expect(component.isDragging()).toBeFalse();
    });

    it('renders all sections and layout elements concurrently (ADR-015 unified venue canvas)', () => {
      const sectionNodes = fixture.nativeElement.querySelectorAll('.section-node');
      const layoutElements = fixture.nativeElement.querySelectorAll('.layout-element');

      expect(sectionNodes.length).toBe(2);
      expect(layoutElements.length).toBe(2);
    });

    it('renders items in deterministic (zIndex, stableKey) ascending order', () => {
      const sorted = component.sortedItems();
      expect(sorted.length).toBe(4);
      // Expected zIndex:
      // 1. STAGE (zIndex -1)
      // 2. Orchestra (zIndex 0)
      // 3. AISLE (zIndex 1)
      // 4. Balcony (zIndex 5)
      expect(sorted[0].data).toBe(mockElements[0]); // STAGE
      expect(sorted[1].data).toBe(mockSections[0]); // Orchestra
      expect(sorted[2].data).toBe(mockElements[1]); // AISLE
      expect(sorted[3].data).toBe(mockSections[1]); // Balcony
    });
  });

  describe('Zoom, Pan, and Viewport Manipulation', () => {
    it('zoomIn increases zoom level up to max 4.0', () => {
      component.zoomIn();
      expect(component.zoomLevel()).toBe(1.25);

      component.setZoom(3.9);
      component.zoomIn();
      expect(component.zoomLevel()).toBe(4.0);

      // Clamps at 4.0
      component.zoomIn();
      expect(component.zoomLevel()).toBe(4.0);
    });

    it('zoomOut decreases zoom level down to min 0.25', () => {
      component.zoomOut();
      expect(component.zoomLevel()).toBe(0.75);

      component.setZoom(0.3);
      component.zoomOut();
      expect(component.zoomLevel()).toBe(0.25);

      // Clamps at 0.25
      component.zoomOut();
      expect(component.zoomLevel()).toBe(0.25);
    });

    it('clamps zoom safely on NaN or zero input to 1.0', () => {
      component.setZoom(0);
      expect(component.zoomLevel()).toBe(1.0);

      component.setZoom(NaN);
      expect(component.zoomLevel()).toBe(1.0);
    });

    it('pans viewport when dragging canvas background', () => {
      const svg = fixture.nativeElement.querySelector('.layout-canvas-svg');

      svg.dispatchEvent(
        new PointerEvent('pointerdown', {
          pointerId: 1,
          clientX: 100,
          clientY: 100,
          button: 0,
          bubbles: true,
        }),
      );
      expect(component.isDragging()).toBeTrue();

      svg.dispatchEvent(
        new PointerEvent('pointermove', {
          pointerId: 1,
          clientX: 150,
          clientY: 180,
          bubbles: true,
        }),
      );
      expect(component.panX()).toBe(50);
      expect(component.panY()).toBe(80);

      svg.dispatchEvent(
        new PointerEvent('pointerup', {
          pointerId: 1,
          clientX: 150,
          clientY: 180,
          bubbles: true,
        }),
      );
      expect(component.isDragging()).toBeFalse();
    });

    it('clears selection on background primary click without pan', () => {
      fixture.componentRef.setInput('selectedSectionIds', new Set(['sec-orchestra']));
      fixture.detectChanges();

      let emittedSelection: Set<string> | null = null;
      component.selectionChanged.subscribe((set) => {
        emittedSelection = set;
      });

      const svg = fixture.nativeElement.querySelector('.layout-canvas-svg');
      svg.dispatchEvent(
        new PointerEvent('pointerdown', {
          pointerId: 1,
          clientX: 100,
          clientY: 100,
          button: 0,
          bubbles: true,
        }),
      );
      // Up at identical client position (< 4px distance)
      svg.dispatchEvent(
        new PointerEvent('pointerup', {
          pointerId: 1,
          clientX: 100,
          clientY: 100,
          bubbles: true,
        }),
      );

      expect(emittedSelection as Set<string> | null).not.toBeNull();
      expect((emittedSelection as unknown as Set<string>).size).toBe(0);
    });
  });

  describe('Selection Behavior', () => {
    it('selects single section on simple click', () => {
      let emitted: Set<string> | null = null;
      component.selectionChanged.subscribe((s) => {
        emitted = s;
      });

      component.onSectionClick({
        event: new MouseEvent('click'),
        section: mockSections[0],
      });

      expect(emitted as Set<string> | null).not.toBeNull();
      expect((emitted as unknown as Set<string>).has('sec-orchestra')).toBeTrue();
      expect((emitted as unknown as Set<string>).size).toBe(1);
    });

    it('toggles section selection when Ctrl or Meta key is pressed', () => {
      fixture.componentRef.setInput('selectedSectionIds', new Set(['sec-orchestra']));
      fixture.detectChanges();

      let emitted: Set<string> | null = null;
      component.selectionChanged.subscribe((s) => {
        emitted = s;
      });

      // Ctrl-click on Balcony should add it
      component.onSectionClick({
        event: new MouseEvent('click', { ctrlKey: true }),
        section: mockSections[1],
      });
      expect((emitted as unknown as Set<string>).has('sec-orchestra')).toBeTrue();
      expect((emitted as unknown as Set<string>).has('sec-balcony')).toBeTrue();
      expect((emitted as unknown as Set<string>).size).toBe(2);

      // Ctrl-click on Orchestra should remove it
      fixture.componentRef.setInput('selectedSectionIds', emitted!);
      fixture.detectChanges();

      component.onSectionClick({
        event: new MouseEvent('click', { ctrlKey: true }),
        section: mockSections[0],
      });
      expect((emitted as unknown as Set<string>).has('sec-orchestra')).toBeFalse();
      expect((emitted as unknown as Set<string>).has('sec-balcony')).toBeTrue();
    });
  });

  describe('Section Drag and Transform Changes', () => {
    it('emits sectionTransformChanged with world coordinates excluding pan/zoom', () => {
      // Set canvas zoom to 2.0 and pan to (50, 50)
      component.zoomLevel.set(2.0);
      component.panX.set(50);
      component.panY.set(50);

      let emittedEvent: SectionTransformChangeEvent | null = null;
      component.sectionTransformChanged.subscribe((change) => {
        emittedEvent = change;
      });

      // Start drag on Orchestra (initial: pos 100, 100)
      component.onSectionPointerDown({
        event: new PointerEvent('pointerdown', { pointerId: 1, clientX: 200, clientY: 200 }),
        section: mockSections[0],
      });

      // Move by +100px in client space
      // Since zoom is 2.0, world delta is 100 / 2.0 = 50px!
      const svg = fixture.nativeElement.querySelector('.layout-canvas-svg');
      svg.dispatchEvent(
        new PointerEvent('pointermove', {
          pointerId: 1,
          clientX: 300,
          clientY: 300,
          bubbles: true,
        }),
      );

      expect(emittedEvent as SectionTransformChangeEvent | null).not.toBeNull();
      const change = emittedEvent as unknown as SectionTransformChangeEvent;
      expect(change.sectionId).toBe('sec-orchestra');
      // 100 + 50 = 150 (independent of viewport pan 50 and zoom 2)
      expect(change.positionX).toBe(150);
      expect(change.positionY).toBe(150);
      expect(change.width).toBe(200);
      expect(change.height).toBe(150);
    });

    it('applies snapStep to section drag coordinates when snapStep > 0', () => {
      fixture.componentRef.setInput('snapStep', 20);
      fixture.detectChanges();

      let emittedEvent: SectionTransformChangeEvent | null = null;
      component.sectionTransformChanged.subscribe((change) => {
        emittedEvent = change;
      });

      component.onSectionPointerDown({
        event: new PointerEvent('pointerdown', { pointerId: 1, clientX: 100, clientY: 100 }),
        section: mockSections[0],
      });

      // Move by 27px -> 100 + 27 = 127 -> snap 20 = 120
      const svg = fixture.nativeElement.querySelector('.layout-canvas-svg');
      svg.dispatchEvent(
        new PointerEvent('pointermove', {
          pointerId: 1,
          clientX: 127,
          clientY: 127,
          bubbles: true,
        }),
      );

      const change = emittedEvent as unknown as SectionTransformChangeEvent;
      expect(change.positionX).toBe(120);
      expect(change.positionY).toBe(120);
    });

    it('emits resized width and height when dragging a corner handle', () => {
      let emittedEvent: SectionTransformChangeEvent | null = null;
      component.sectionTransformChanged.subscribe((change) => {
        emittedEvent = change;
      });

      // Start resize on South-East handle of Orchestra (w=200, h=150)
      component.onHandlePointerDown({
        event: new PointerEvent('pointerdown', { pointerId: 1, clientX: 300, clientY: 250 }),
        section: mockSections[0],
        handle: 'se',
      });

      // Drag SE handle by (+50, +40)
      const svg = fixture.nativeElement.querySelector('.layout-canvas-svg');
      svg.dispatchEvent(
        new PointerEvent('pointermove', {
          pointerId: 1,
          clientX: 350,
          clientY: 290,
          bubbles: true,
        }),
      );

      const change = emittedEvent as unknown as SectionTransformChangeEvent;
      expect(change.width).toBe(250);
      expect(change.height).toBe(190);
    });

    it('emits normalized degrees when dragging rotation handle', () => {
      let emittedEvent: SectionTransformChangeEvent | null = null;
      component.sectionTransformChanged.subscribe((change) => {
        emittedEvent = change;
      });

      // Center of Orchestra in world coords is (200, 175)
      const svg = fixture.nativeElement.querySelector('.layout-canvas-svg');
      const rect = svg.getBoundingClientRect();

      component.onHandlePointerDown({
        event: new PointerEvent('pointerdown', {
          pointerId: 1,
          clientX: rect.left + 200,
          clientY: rect.top + 100,
        }),
        section: mockSections[0],
        handle: 'rotate',
      });

      // Move cursor to the right of section center: world (350, 175) -> 90 deg
      svg.dispatchEvent(
        new PointerEvent('pointermove', {
          pointerId: 1,
          clientX: rect.left + 350,
          clientY: rect.top + 175,
          bubbles: true,
        }),
      );

      const change = emittedEvent as unknown as SectionTransformChangeEvent;
      expect(change.rotationDeg).toBe(90);
    });
  });

  describe('Pointer Cancellation and Lost Capture (Pointer leak risk)', () => {
    it('clears interaction state and emits no extra transform on pointercancel', () => {
      let emitCount = 0;
      component.sectionTransformChanged.subscribe(() => {
        emitCount++;
      });

      component.onSectionPointerDown({
        event: new PointerEvent('pointerdown', { pointerId: 1, clientX: 100, clientY: 100 }),
        section: mockSections[0],
      });
      expect(component.isDragging()).toBeTrue();

      const svg = fixture.nativeElement.querySelector('.layout-canvas-svg');
      svg.dispatchEvent(new PointerEvent('pointercancel', { pointerId: 1, bubbles: true }));

      expect(component.isDragging()).toBeFalse();
      const countAtCancel = emitCount;

      // Further pointer moves must not emit transforms
      svg.dispatchEvent(
        new PointerEvent('pointermove', {
          pointerId: 1,
          clientX: 200,
          clientY: 200,
          bubbles: true,
        }),
      );
      expect(emitCount).toBe(countAtCancel);
    });

    it('clears interaction state on lostpointercapture', () => {
      component.onSectionPointerDown({
        event: new PointerEvent('pointerdown', { pointerId: 1, clientX: 100, clientY: 100 }),
        section: mockSections[0],
      });
      expect(component.isDragging()).toBeTrue();

      const svg = fixture.nativeElement.querySelector('.layout-canvas-svg');
      svg.dispatchEvent(new PointerEvent('lostpointercapture', { pointerId: 1, bubbles: true }));

      expect(component.isDragging()).toBeFalse();
    });
  });

  describe('Read-Only Mode (editable=false)', () => {
    it('emits no mutation event in read-only mode', () => {
      fixture.componentRef.setInput('editable', false);
      fixture.detectChanges();

      let emitted = false;
      component.sectionTransformChanged.subscribe(() => {
        emitted = true;
      });

      component.onSectionPointerDown({
        event: new PointerEvent('pointerdown', { pointerId: 1, clientX: 100, clientY: 100 }),
        section: mockSections[0],
      });

      const svg = fixture.nativeElement.querySelector('.layout-canvas-svg');
      svg.dispatchEvent(
        new PointerEvent('pointermove', {
          pointerId: 1,
          clientX: 200,
          clientY: 200,
          bubbles: true,
        }),
      );

      expect(emitted).toBeFalse();
      expect(component.isDragging()).toBeFalse();
    });
  });

  describe('Fit to Layout', () => {
    it('resets to default view on empty layout', () => {
      fixture.componentRef.setInput('sections', []);
      fixture.componentRef.setInput('elements', []);
      fixture.detectChanges();

      component.zoomLevel.set(2.5);
      component.panX.set(100);
      component.panY.set(150);

      component.fitToLayout();
      expect(component.zoomLevel()).toBe(1.0);
      expect(component.panX()).toBe(0);
      expect(component.panY()).toBe(0);
    });

    it('calculates bounded zoom and centers non-empty layout', () => {
      component.fitToLayout();
      expect(component.zoomLevel()).toBeGreaterThanOrEqual(0.25);
      expect(component.zoomLevel()).toBeLessThanOrEqual(4.0);
    });
  });
});
