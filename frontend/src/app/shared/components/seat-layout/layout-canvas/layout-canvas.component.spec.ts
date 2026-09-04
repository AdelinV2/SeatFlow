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

    it('correctly scales delta and emits resized dimensions under canvas zoom (2.0)', () => {
      component.zoomLevel.set(2.0);
      component.panX.set(100);
      component.panY.set(50);

      let emittedEvent: SectionTransformChangeEvent | null = null;
      component.sectionTransformChanged.subscribe((change) => {
        emittedEvent = change;
      });

      // Start resize on SE handle of Orchestra (w=200, h=150)
      component.onHandlePointerDown({
        event: new PointerEvent('pointerdown', { pointerId: 1, clientX: 300, clientY: 250 }),
        section: mockSections[0],
        handle: 'se',
      });

      // Move by (+100, +80) in client space
      // At zoom 2.0, worldDelta = (+100/2, +80/2) = (+50, +40)
      const svg = fixture.nativeElement.querySelector('.layout-canvas-svg');
      svg.dispatchEvent(
        new PointerEvent('pointermove', {
          pointerId: 1,
          clientX: 400,
          clientY: 330,
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

    it('does NOT emit seatSelected or mutate selection when clicking a seat in an inactive section (REV-003)', () => {
      const inactiveSection: VenueSectionLayout = {
        ...mockSections[0],
        sectionId: 'sec-inactive',
        isActive: false,
        seats: [
          {
            seatId: 'seat-in-inactive-sec',
            rowLabel: 'Z',
            seatNumber: 99,
            gridX: 0,
            gridY: 0,
            positionX: 20,
            positionY: 20,
            isActive: true,
          },
        ],
      };

      fixture.componentRef.setInput('editable', false);
      fixture.componentRef.setInput('sections', [inactiveSection]);
      fixture.detectChanges();

      let seatSelectedEmitted = false;
      component.seatSelected.subscribe(() => {
        seatSelectedEmitted = true;
      });

      let selectionChangedEmitted = false;
      component.selectionChanged.subscribe(() => {
        selectionChangedEmitted = true;
      });

      // Click seat in inactive section
      component.onSeatClick({
        event: new MouseEvent('click'),
        seat: inactiveSection.seats[0],
        section: inactiveSection,
      });

      expect(seatSelectedEmitted).toBeFalse();
      expect(selectionChangedEmitted).toBeFalse();
    });

    it('does NOT emit seatSelected or mutate selection on keyboard activation of a seat in an inactive section (REV-003)', () => {
      const inactiveSection: VenueSectionLayout = {
        ...mockSections[0],
        sectionId: 'sec-inactive',
        isActive: false,
        seats: [
          {
            seatId: 'seat-in-inactive-sec',
            rowLabel: 'Z',
            seatNumber: 99,
            gridX: 0,
            gridY: 0,
            positionX: 20,
            positionY: 20,
            isActive: true,
          },
        ],
      };

      fixture.componentRef.setInput('editable', false);
      fixture.componentRef.setInput('sections', [inactiveSection]);
      fixture.detectChanges();

      let seatSelectedEmitted = false;
      component.seatSelected.subscribe(() => {
        seatSelectedEmitted = true;
      });

      let selectionChangedEmitted = false;
      component.selectionChanged.subscribe(() => {
        selectionChangedEmitted = true;
      });

      component.onSeatClick({
        event: new KeyboardEvent('keydown', { key: 'Enter' }),
        seat: inactiveSection.seats[0],
        section: inactiveSection,
      });

      expect(seatSelectedEmitted).toBeFalse();
      expect(selectionChangedEmitted).toBeFalse();
    });

    it('does NOT emit seatSelected when clicking an inactive seat inside an active section (REV-003)', () => {
      const activeSectionWithInactiveSeat: VenueSectionLayout = {
        ...mockSections[0],
        sectionId: 'sec-active',
        isActive: true,
        seats: [
          {
            seatId: 'seat-inactive',
            rowLabel: 'B',
            seatNumber: 2,
            gridX: 0,
            gridY: 1,
            positionX: 50,
            positionY: 50,
            isActive: false,
          },
        ],
      };

      fixture.componentRef.setInput('editable', false);
      fixture.componentRef.setInput('sections', [activeSectionWithInactiveSeat]);
      fixture.detectChanges();

      let seatSelectedEmitted = false;
      component.seatSelected.subscribe(() => {
        seatSelectedEmitted = true;
      });

      component.onSeatClick({
        event: new MouseEvent('click'),
        seat: activeSectionWithInactiveSeat.seats[0],
        section: activeSectionWithInactiveSeat,
      });

      expect(seatSelectedEmitted).toBeFalse();
    });

    it('does NOT emit seatSelected or selectionChanged via pointer/click path on inactive read-only section (REV-004)', () => {
      const inactiveSection: VenueSectionLayout = {
        ...mockSections[0],
        sectionId: 'sec-inactive-path',
        isActive: false,
        seats: [
          {
            seatId: 'seat-in-inactive-path',
            rowLabel: 'Z',
            seatNumber: 99,
            gridX: 0,
            gridY: 0,
            positionX: 20,
            positionY: 20,
            isActive: true,
          },
        ],
      };

      fixture.componentRef.setInput('editable', false);
      fixture.componentRef.setInput('sections', [inactiveSection]);
      fixture.componentRef.setInput('selectedSectionIds', new Set(['sec-orchestra']));
      fixture.detectChanges();

      let seatSelectedCount = 0;
      component.seatSelected.subscribe(() => {
        seatSelectedCount++;
      });

      let selectionChangedCount = 0;
      component.selectionChanged.subscribe(() => {
        selectionChangedCount++;
      });

      const inactiveNode = fixture.nativeElement.querySelector(
        '.section-node.section-inactive',
      ) as Element;
      expect(inactiveNode).not.toBeNull();
      // REV-004: inactive geometry must stay hit-testable so guards run.
      expect(getComputedStyle(inactiveNode).pointerEvents).not.toBe('none');

      const target = inactiveNode.querySelector('.section-boundary') ?? inactiveNode;

      target.dispatchEvent(
        new PointerEvent('pointerdown', {
          pointerId: 7,
          clientX: 120,
          clientY: 120,
          button: 0,
          bubbles: true,
        }),
      );
      target.dispatchEvent(
        new PointerEvent('pointerup', {
          pointerId: 7,
          clientX: 120,
          clientY: 120,
          bubbles: true,
        }),
      );
      target.dispatchEvent(new MouseEvent('click', { bubbles: true }));

      expect(seatSelectedCount).toBe(0);
      expect(selectionChangedCount).toBe(0);
    });

    it('does NOT emit seatSelected or selectionChanged via pointer/click path on inactive seat in active read-only section (REV-004)', () => {
      const activeSectionWithInactiveSeat: VenueSectionLayout = {
        ...mockSections[0],
        sectionId: 'sec-active-path',
        isActive: true,
        seats: [
          {
            seatId: 'seat-inactive-path',
            rowLabel: 'B',
            seatNumber: 2,
            gridX: 0,
            gridY: 1,
            positionX: 50,
            positionY: 50,
            isActive: false,
          },
        ],
      };

      fixture.componentRef.setInput('editable', false);
      fixture.componentRef.setInput('sections', [activeSectionWithInactiveSeat]);
      fixture.componentRef.setInput('selectedSectionIds', new Set<string>());
      fixture.detectChanges();

      let seatSelectedCount = 0;
      component.seatSelected.subscribe(() => {
        seatSelectedCount++;
      });

      let selectionChangedCount = 0;
      component.selectionChanged.subscribe(() => {
        selectionChangedCount++;
      });

      const inactiveSeat = fixture.nativeElement.querySelector(
        '.seat-item.non-interactive',
      ) as Element;
      expect(inactiveSeat).not.toBeNull();
      // REV-004: inactive seats must stay hit-testable so the seat guard runs.
      expect(getComputedStyle(inactiveSeat).pointerEvents).not.toBe('none');

      const target = inactiveSeat.querySelector('.seat-circle') ?? inactiveSeat;

      target.dispatchEvent(
        new PointerEvent('pointerdown', {
          pointerId: 9,
          clientX: 150,
          clientY: 150,
          button: 0,
          bubbles: true,
        }),
      );
      target.dispatchEvent(
        new PointerEvent('pointerup', {
          pointerId: 9,
          clientX: 150,
          clientY: 150,
          bubbles: true,
        }),
      );
      target.dispatchEvent(new MouseEvent('click', { bubbles: true }));

      expect(seatSelectedCount).toBe(0);
      expect(selectionChangedCount).toBe(0);
    });

    it('emits seatSelected when clicking an active seat in an active read-only section', () => {
      fixture.componentRef.setInput('editable', false);
      fixture.detectChanges();

      let emittedSeat: any = null;
      component.seatSelected.subscribe((data) => {
        emittedSeat = data;
      });

      component.onSeatClick({
        event: new MouseEvent('click'),
        seat: mockSections[0].seats[0],
        section: mockSections[0],
      });

      expect(emittedSeat).not.toBeNull();
      expect(emittedSeat.seat.seatId).toBe('seat-1');
      expect(emittedSeat.section.sectionId).toBe('sec-orchestra');
    });

    it('propagates modifier state as additive on Ctrl/Cmd seat clicks (REV-004)', () => {
      fixture.componentRef.setInput('editable', true);
      fixture.detectChanges();

      const emitted: any[] = [];
      component.seatSelected.subscribe((data) => {
        emitted.push(data);
      });

      component.onSeatClick({
        event: new MouseEvent('click'),
        seat: mockSections[0].seats[0],
        section: mockSections[0],
      });
      component.onSeatClick({
        event: new MouseEvent('click', { ctrlKey: true }),
        seat: mockSections[0].seats[0],
        section: mockSections[0],
      });

      expect(emitted.length).toBe(2);
      expect(emitted[0].additive).toBeFalse();
      expect(emitted[1].additive).toBeTrue();
    });

    it('renders inactive sections and seats in editor DOM and permits editor seat selection (REV-003)', () => {
      const inactiveSection: VenueSectionLayout = {
        ...mockSections[0],
        sectionId: 'sec-inactive-editor',
        isActive: false,
        seats: [
          {
            seatId: 'seat-in-inactive-editor',
            rowLabel: 'A',
            seatNumber: 1,
            gridX: 0,
            gridY: 0,
            positionX: 30,
            positionY: 30,
            isActive: false,
          },
        ],
      };

      fixture.componentRef.setInput('editable', true);
      fixture.componentRef.setInput('sections', [inactiveSection]);
      fixture.detectChanges();

      // Section and seat nodes remain represented in DOM
      const sectionNode = fixture.nativeElement.querySelector('.section-node');
      expect(sectionNode).not.toBeNull();
      expect(sectionNode.classList.contains('section-inactive')).toBeTrue();

      const seatItem = fixture.nativeElement.querySelector('.seat-item');
      expect(seatItem).not.toBeNull();
      expect(seatItem.classList.contains('inactive')).toBeTrue();

      let emittedSeat: any = null;
      component.seatSelected.subscribe((data) => {
        emittedSeat = data;
      });

      // Emits in editor mode
      component.onSeatClick({
        event: new MouseEvent('click'),
        seat: inactiveSection.seats[0],
        section: inactiveSection,
      });

      expect(emittedSeat).not.toBeNull();
      expect(emittedSeat.seat.seatId).toBe('seat-in-inactive-editor');
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

  describe('Layout Elements Operations and Invariants (TASK-P11-008)', () => {
    it('isolates layout elements from seat selection, capacity, and active seat counts (Booking Contamination)', () => {
      const initialSections = component.sections();
      const initialSeatCount = initialSections.reduce(
        (sum, sec) => sum + sec.seats.filter((s) => s.isActive).length,
        0,
      );
      const initialSelectedSeats = new Set(component.selectedSeatKeys());

      // 1. Add element via palette
      component.onElementCreatedFromPalette({
        elementId: null,
        type: 'STAGE',
        label: 'Stage 2',
        geometry: { x: 100, y: 40, width: 400, height: 80, rotationDeg: 0 },
        zIndex: 10,
      });

      // 2. Drag element
      const added = component.internalElements()[component.internalElements().length - 1];
      component.onElementPointerDown({
        event: new PointerEvent('pointerdown', { pointerId: 1, clientX: 100, clientY: 40 }),
        element: added,
      });
      const svg = fixture.nativeElement.querySelector('.layout-canvas-svg');
      svg.dispatchEvent(
        new PointerEvent('pointermove', {
          pointerId: 1,
          clientX: 200,
          clientY: 100,
          bubbles: true,
        }),
      );
      svg.dispatchEvent(
        new PointerEvent('pointerup', { pointerId: 1, clientX: 200, clientY: 100, bubbles: true }),
      );

      // 3. Duplicate element
      component.duplicateElement(added);

      // 4. Update element geometry and zIndex
      component.updateSelectedElementGeometry({ width: 500, height: 120 });
      component.updateSelectedElementZIndex(15);

      // 5. Remove element
      component.removeElement(added);

      // Verify invariant: seat count and seat selections are completely unaffected
      const currentSections = component.sections();
      const currentSeatCount = currentSections.reduce(
        (sum, sec) => sum + sec.seats.filter((s) => s.isActive).length,
        0,
      );

      expect(currentSeatCount).toBe(initialSeatCount);
      expect(component.selectedSeatKeys()).toEqual(initialSelectedSeats);
      for (let i = 0; i < currentSections.length; i++) {
        expect(currentSections[i].seats.length).toBe(initialSections[i].seats.length);
      }
    });

    it('escapes hostile script labels and renders as text without injected nodes/paths (Injection Scope)', () => {
      const hostile = "<script>alert('XSS')</script><svg onload=alert(1)>";
      component.onElementCreatedFromPalette({
        elementId: null,
        type: 'LABEL',
        label: hostile,
        geometry: { x: 100, y: 240, width: 200, height: 44, rotationDeg: 0 },
        zIndex: 5,
      });
      fixture.detectChanges();

      const scripts = fixture.nativeElement.querySelectorAll('script');
      expect(scripts.length).toBe(0);

      const labelNodes = fixture.nativeElement.querySelectorAll('.standalone-label');
      const hostileNode = Array.from(labelNodes).find(
        (n: any) => String(n.textContent).trim() === hostile,
      );
      expect(hostileNode).toBeDefined();

      // No arbitrary SVG path tags created for label
      const paths = fixture.nativeElement.querySelectorAll('path.custom-label-path');
      expect(paths.length).toBe(0);
    });

    it('retains existing elementId during move/resize, while duplicates have null ID (ID Invariant)', () => {
      const existing = mockElements[0]; // elementId: 'elem-stage'
      expect(existing.elementId).toBe('elem-stage');

      // Select and drag existing element
      component.onElementPointerDown({
        event: new PointerEvent('pointerdown', { pointerId: 1, clientX: 200, clientY: 20 }),
        element: existing,
      });

      const svg = fixture.nativeElement.querySelector('.layout-canvas-svg');
      svg.dispatchEvent(
        new PointerEvent('pointermove', { pointerId: 1, clientX: 250, clientY: 70, bubbles: true }),
      );

      const updatedExisting = component.internalElements().find((e) => e.label === existing.label);
      expect(updatedExisting).toBeDefined();
      expect(updatedExisting!.elementId).toBe('elem-stage'); // Preserved!

      // Duplicate existing element
      const duplicate = component.duplicateElement(existing);
      expect(duplicate).not.toBeNull();
      expect(duplicate!.elementId).toBeNull(); // Must be null!
      expect(duplicate!.type).toBe(existing.type);
      expect(duplicate!.geometry.x).toBe(existing.geometry.x + 20);
      expect(duplicate!.geometry.y).toBe(existing.geometry.y + 20);
    });

    it('clamps duplicate at maximum coordinate (100000, 100000) within canvas bounds', () => {
      const atMax: VenueLayoutElement = {
        elementId: 'elem-max',
        type: 'DECORATION',
        label: null,
        geometry: { x: 100000, y: 100000, width: 100, height: 100, rotationDeg: 0 },
        zIndex: 2,
      };

      component.internalElements.set([atMax]);
      fixture.detectChanges();

      const dup = component.duplicateElement(atMax);
      expect(dup).not.toBeNull();
      expect(dup!.elementId).toBeNull();
      // 100000 + 20 must clamp to 100000
      expect(dup!.geometry.x).toBe(100000);
      expect(dup!.geometry.y).toBe(100000);
    });

    it('clamps move, resize, rotate, and z-index within contract bounds', () => {
      const elem = mockElements[0];
      component.selectElement(elem, 0);

      // Move beyond min (negative)
      component.updateSelectedElementGeometry({ x: -50, y: -100 });
      let updated = component.selectedElement()!;
      expect(updated.geometry.x).toBe(0);
      expect(updated.geometry.y).toBe(0);

      // Move beyond max (150000)
      component.updateSelectedElementGeometry({ x: 150000, y: 120000 });
      updated = component.selectedElement()!;
      expect(updated.geometry.x).toBe(100000);
      expect(updated.geometry.y).toBe(100000);

      // Resize: width/height strictly positive and <= 100000
      component.updateSelectedElementGeometry({ width: -20, height: 0 });
      updated = component.selectedElement()!;
      expect(updated.geometry.width).toBe(0.001);
      expect(updated.geometry.height).toBe(0.001);

      component.updateSelectedElementGeometry({ width: 200000, height: 300000 });
      updated = component.selectedElement()!;
      expect(updated.geometry.width).toBe(100000);
      expect(updated.geometry.height).toBe(100000);

      // Rotate: normalize into [-180, 180]
      component.updateSelectedElementGeometry({ rotationDeg: 270 });
      updated = component.selectedElement()!;
      expect(updated.geometry.rotationDeg).toBe(-90);

      // Z-Index: clamp to [-1000, 1000]
      component.updateSelectedElementZIndex(-5000);
      updated = component.selectedElement()!;
      expect(updated.zIndex).toBe(-1000);

      component.updateSelectedElementZIndex(9999);
      updated = component.selectedElement()!;
      expect(updated.zIndex).toBe(1000);
    });

    it('fails and rejects blank or whitespace-only LABEL before state mutation', () => {
      const labelElem: VenueLayoutElement = {
        elementId: 'elem-lbl',
        type: 'LABEL',
        label: 'Original Label',
        geometry: { x: 100, y: 240, width: 200, height: 44, rotationDeg: 0 },
        zIndex: 1,
      };

      component.internalElements.set([labelElem]);
      component.selectElement(labelElem, 0);

      // Attempt empty label
      const successEmpty = component.updateSelectedElementLabel('');
      expect(successEmpty).toBeFalse();
      expect(component.elementValidationError()).toContain('LABEL requires visible non-blank text');
      expect(component.selectedElement()!.label).toBe('Original Label'); // Unchanged!

      // Attempt whitespace-only label
      const successWhitespace = component.updateSelectedElementLabel('    ');
      expect(successWhitespace).toBeFalse();
      expect(component.elementValidationError()).toContain('LABEL requires visible non-blank text');
      expect(component.selectedElement()!.label).toBe('Original Label'); // Unchanged!

      // Valid label succeeds and clears error
      const successValid = component.updateSelectedElementLabel('Valid Label');
      expect(successValid).toBeTrue();
      expect(component.elementValidationError()).toBeNull();
      expect(component.selectedElement()!.label).toBe('Valid Label');
    });

    it('rejects unsupported element type without mutating draft', () => {
      const initialCount = component.internalElements().length;
      component.onElementCreatedFromPalette({
        elementId: null,
        type: 'NON_EXISTENT' as any,
        label: 'Invalid',
        geometry: { x: 10, y: 10, width: 50, height: 50, rotationDeg: 0 },
        zIndex: 0,
      });

      expect(component.internalElements().length).toBe(initialCount);
      expect(component.elementValidationError()).toContain('Unsupported layout element type');
    });

    it('normalizes malformed palette payloads: null ID, blank LABEL, clamped bounds (REV-002)', () => {
      // Non-null elementId on a "new" element is forced to null
      component.onElementCreatedFromPalette({
        elementId: 'smuggled-id',
        type: 'STAGE',
        label: 'Smuggled',
        geometry: { x: 100, y: 40, width: 400, height: 80, rotationDeg: 0 },
        zIndex: 3,
      });
      const created = component.internalElements()[component.internalElements().length - 1];
      expect(created.elementId).toBeNull();
      expect(created.label).toBe('Smuggled');

      // Blank LABEL rejected without draft mutation
      const beforeBlank = component.internalElements().length;
      component.onElementCreatedFromPalette({
        elementId: null,
        type: 'LABEL',
        label: '   ',
        geometry: { x: 100, y: 240, width: 200, height: 44, rotationDeg: 0 },
        zIndex: 3,
      });
      expect(component.internalElements().length).toBe(beforeBlank);
      expect(component.elementValidationError()).toContain('LABEL requires visible non-blank text');

      // Out-of-range geometry and z-index are clamped, not retained
      component.onElementCreatedFromPalette({
        elementId: null,
        type: 'BARRIER',
        label: null,
        geometry: { x: -50, y: 200000, width: 0, height: 500000, rotationDeg: 270 },
        zIndex: 5000,
      });
      const clamped = component.internalElements()[component.internalElements().length - 1];
      expect(clamped.elementId).toBeNull();
      expect(clamped.geometry.x).toBe(0);
      expect(clamped.geometry.y).toBe(100000);
      expect(clamped.geometry.width).toBe(0.001);
      expect(clamped.geometry.height).toBe(100000);
      expect(clamped.geometry.rotationDeg).toBe(-90);
      expect(clamped.zIndex).toBe(1000);

      // Non-finite geometry rejected without draft mutation
      const beforeNaN = component.internalElements().length;
      component.onElementCreatedFromPalette({
        elementId: null,
        type: 'DECORATION',
        label: null,
        geometry: { x: NaN, y: 0, width: 10, height: 10, rotationDeg: 0 },
        zIndex: 0,
      });
      expect(component.internalElements().length).toBe(beforeNaN);
      expect(component.elementValidationError()).toContain('Invalid element geometry');
    });

    it('keeps editable element nodes hit-testable and non-editable nodes inert (REV-001)', () => {
      fixture.componentRef.setInput('editable', true);
      fixture.detectChanges();
      const node = fixture.nativeElement.querySelector('.layout-element-node') as Element;
      expect(node).not.toBeNull();
      expect(getComputedStyle(node).pointerEvents).not.toBe('none');

      fixture.componentRef.setInput('editable', false);
      fixture.detectChanges();
      const inert = fixture.nativeElement.querySelector('.layout-element-node') as Element;
      expect(inert).not.toBeNull();
      expect(getComputedStyle(inert).pointerEvents).toBe('none');
    });

    it('mutually excludes section and element selection and clears both on background click', () => {
      // Simulate the parent designer: emitted section selection flows back into the input.
      component.selectionChanged.subscribe((sel) =>
        fixture.componentRef.setInput('selectedSectionIds', sel),
      );
      // 1. Select section
      component.onSectionClick({
        event: new MouseEvent('click'),
        section: mockSections[0],
      });
      expect(component.selectedIdSet().has('sec-orchestra')).toBeTrue();
      expect(component.selectedElement()).toBeNull();

      // 2. Select element
      const elem = component.internalElements()[0];
      component.onElementClick({
        event: new MouseEvent('click'),
        element: elem,
      });
      expect(component.selectedElement()).toBe(elem);
      expect(component.selectedIdSet().size).toBe(0); // Section deselected!

      // 3. Select section again -> element deselected
      component.onSectionClick({
        event: new MouseEvent('click'),
        section: mockSections[1],
      });
      expect(component.selectedIdSet().has('sec-balcony')).toBeTrue();
      expect(component.selectedElement()).toBeNull();

      // 4. Select element again and click canvas background (< 4px pan)
      component.onElementClick({
        event: new MouseEvent('click'),
        element: elem,
      });
      expect(component.selectedElement()).toBe(elem);

      const svg = fixture.nativeElement.querySelector('.layout-canvas-svg');
      svg.dispatchEvent(
        new PointerEvent('pointerdown', {
          pointerId: 1,
          clientX: 5,
          clientY: 5,
          button: 0,
          bubbles: true,
        }),
      );
      svg.dispatchEvent(
        new PointerEvent('pointerup', { pointerId: 1, clientX: 5, clientY: 5, bubbles: true }),
      );

      expect(component.selectedElement()).toBeNull();
      expect(component.selectedIdSet().size).toBe(0);
    });

    it('deletes element only from unsaved draft and deselects', () => {
      const elem = component.internalElements()[0];
      component.selectElement(elem, 0);
      expect(component.selectedElement()).toBe(elem);

      let removedElem: VenueLayoutElement | null = null;
      component.elementRemoved.subscribe((e) => (removedElem = e));

      component.removeSelectedElement();

      expect<VenueLayoutElement | null>(removedElem).toBe(elem);
      expect(component.selectedElement()).toBeNull();
      expect(component.internalElements().includes(elem)).toBeFalse();
    });

    it('supports keyboard navigation alternatives: arrow keys to move, delete to remove', () => {
      const elem = component.internalElements()[0];
      component.selectElement(elem, 0);
      const initialX = elem.geometry.x;
      const initialY = elem.geometry.y;

      // ArrowRight -> move +10
      component.onElementKeyDown({
        event: new KeyboardEvent('keydown', { key: 'ArrowRight' }),
        element: component.selectedElement()!,
      });
      expect(component.selectedElement()!.geometry.x).toBe(initialX + 10);

      // ArrowDown -> move +10
      component.onElementKeyDown({
        event: new KeyboardEvent('keydown', { key: 'ArrowDown' }),
        element: component.selectedElement()!,
      });
      expect(component.selectedElement()!.geometry.y).toBe(initialY + 10);

      // Delete -> removes from draft
      component.onElementKeyDown({
        event: new KeyboardEvent('keydown', { key: 'Delete' }),
        element: component.selectedElement()!,
      });
      expect(component.selectedElement()).toBeNull();
    });

    it('supports numeric form inputs to alter properties with bounds enforcement', () => {
      const elem = component.internalElements()[0];
      component.selectElement(elem, 0);

      // Numeric change X
      component.onNumericParamChange('x', { target: { value: '350' } } as unknown as Event);
      expect(component.selectedElement()!.geometry.x).toBe(350);

      // Numeric change Width
      component.onNumericParamChange('width', { target: { value: '450' } } as unknown as Event);
      expect(component.selectedElement()!.geometry.width).toBe(450);

      // Numeric change Rotation
      component.onNumericParamChange('rotationDeg', {
        target: { value: '45' },
      } as unknown as Event);
      expect(component.selectedElement()!.geometry.rotationDeg).toBe(45);

      // Numeric change Z-Index
      component.onNumericParamChange('zIndex', { target: { value: '12' } } as unknown as Event);
      expect(component.selectedElement()!.zIndex).toBe(12);
    });
  });
});
