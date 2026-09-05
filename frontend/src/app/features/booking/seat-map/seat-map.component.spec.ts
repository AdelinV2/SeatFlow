import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { MatSnackBar } from '@angular/material/snack-bar';
import { LayoutCanvasComponent } from '../../../shared/components/seat-layout/layout-canvas/layout-canvas.component';
import {
  EventSeatMapLayoutElement,
  Seat,
  SeatMapSectionResponse,
} from '../../../models/seat.model';
import { VenueSectionLayout, VenueSectionSeat } from '../../../models/venue.model';
import { MUTED_SEAT_GRAY, SeatMapComponent, tierColorFor } from './seat-map.component';

describe('SeatMapComponent', () => {
  let fixture: ComponentFixture<SeatMapComponent>;
  let component: SeatMapComponent;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  const createSeat = (overrides: Partial<Seat> & { id: string }): Seat => ({
    sectionId: 'section-a',
    sectionName: 'Orchestra',
    rowLabel: 'A',
    seatNumber: 1,
    gridX: 0,
    gridY: 0,
    price: 150,
    currency: 'RON',
    status: 'AVAILABLE',
    isActive: true,
    positionX: 100,
    positionY: 300,
    sectionPositionX: 0,
    sectionPositionY: 200,
    sectionWidth: 484,
    sectionHeight: 44,
    sectionRotationDeg: 0,
    sectionZIndex: 1,
    categoryName: 'Categoria A',
    pricingTierId: 'tier-a',
    ...overrides,
  });

  const sectionsData: SeatMapSectionResponse[] = [
    {
      sectionId: 'section-a',
      name: 'Orchestra',
      rowCount: 1,
      colCount: 12,
      isActive: true,
      positionX: 0,
      positionY: 200,
      width: 528,
      height: 44,
      rotationDeg: 0,
      zIndex: 1,
      seats: [],
      pricingTiers: [
        {
          id: 'tier-a',
          sectionId: 'section-a',
          categoryName: 'Categoria A',
          price: 150,
          currency: 'RON',
        },
      ],
    },
    {
      sectionId: 'section-b',
      name: 'Balcony',
      rowCount: 1,
      colCount: 2,
      isActive: true,
      positionX: 600,
      positionY: 100,
      width: 88,
      height: 44,
      rotationDeg: 15,
      zIndex: 2,
      seats: [],
      pricingTiers: [
        {
          id: 'tier-b',
          sectionId: 'section-b',
          categoryName: 'Categoria B',
          price: 120,
          currency: 'RON',
        },
      ],
    },
    {
      sectionId: 'section-c',
      name: 'Closed Loft',
      rowCount: 1,
      colCount: 1,
      isActive: false,
      seats: [],
    },
  ];

  const layoutElements: EventSeatMapLayoutElement[] = [
    {
      elementId: 'stage-1',
      type: 'STAGE',
      label: 'Main Stage',
      geometry: { x: 0, y: 0, width: 528, height: 60, rotationDeg: 0 },
      zIndex: 0,
    },
    {
      elementId: 'aisle-1',
      type: 'AISLE',
      label: null,
      geometry: { x: 90, y: 290, width: 120, height: 44 },
      zIndex: 3,
    },
  ];

  const buildSeats = (): Seat[] => {
    const seats: Seat[] = [];
    for (let index = 0; index < 11; index += 1) {
      seats.push(
        createSeat({
          id: `seat-${index + 1}`,
          seatNumber: index + 1,
          gridX: index,
          positionX: 100 + index * 44,
        }),
      );
    }
    seats.push(
      createSeat({
        id: 'seat-b1',
        sectionId: 'section-b',
        sectionName: 'Balcony',
        seatNumber: 1,
        gridX: 0,
        gridY: 0,
        price: 120,
        positionX: 610,
        positionY: 110,
        sectionPositionX: 600,
        sectionPositionY: 100,
        sectionWidth: 88,
        sectionHeight: 44,
        sectionRotationDeg: 15,
        sectionZIndex: 2,
        categoryName: 'Categoria B',
        pricingTierId: 'tier-b',
      }),
      createSeat({
        id: 'seat-b2',
        sectionId: 'section-b',
        sectionName: 'Balcony',
        seatNumber: 2,
        gridX: 1,
        gridY: 0,
        price: 120,
        positionX: 654,
        positionY: 110,
        sectionPositionX: 600,
        sectionPositionY: 100,
        sectionWidth: 88,
        sectionHeight: 44,
        sectionRotationDeg: 15,
        sectionZIndex: 2,
        categoryName: 'Categoria B',
        pricingTierId: 'tier-b',
      }),
      createSeat({
        id: 'seat-inactive',
        seatNumber: 12,
        gridX: 11,
        positionX: 584,
        status: 'DISABLED',
        isActive: false,
        price: 0,
      }),
      createSeat({
        id: 'seat-loft',
        sectionId: 'section-c',
        sectionName: 'Closed Loft',
        gridX: 0,
        positionX: 10,
        positionY: 10,
      }),
    );
    return seats;
  };

  const canvasEventFor = (seatId: string) => ({
    event: new MouseEvent('click'),
    seat: { seatId } as unknown as VenueSectionSeat,
    section: {} as unknown as VenueSectionLayout,
    additive: false,
  });

  beforeEach(async () => {
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    await TestBed.configureTestingModule({
      imports: [SeatMapComponent],
      providers: [{ provide: MatSnackBar, useValue: snackBar }],
    }).compileComponents();

    fixture = TestBed.createComponent(SeatMapComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('seats', buildSeats());
    fixture.componentRef.setInput('sectionsData', sectionsData);
    fixture.componentRef.setInput('layoutElements', layoutElements);
    fixture.componentRef.setInput(
      'selectedSeatIds',
      new Set(Array.from({ length: 10 }, (_, index) => `seat-${index + 1}`)),
    );
    fixture.detectChanges();
  });

  it('blocks an eleventh selection and shows the required warning', () => {
    const emitted: Seat[] = [];
    component.seatToggled.subscribe((seat) => emitted.push(seat));

    component.handleSeatClick(component.seatById().get('seat-11')!);

    expect(emitted).toEqual([]);
    expect(snackBar.open).toHaveBeenCalledWith(
      'Maximum 10 seats allowed per reservation.',
      'Close',
      jasmine.objectContaining({ panelClass: 'snack-warning' }),
    );
  });

  it('emits available seats and announces both selection directions', () => {
    fixture.componentRef.setInput('selectedSeatIds', new Set<string>());
    const emitted: Seat[] = [];
    component.seatToggled.subscribe((seat) => emitted.push(seat));

    component.handleSeatClick(component.seatById().get('seat-1')!);

    expect(emitted.map((seat) => seat.id)).toEqual(['seat-1']);
    expect(component.liveAnnouncement()).toContain('Selected seat in row A, seat 1');

    fixture.componentRef.setInput('selectedSeatIds', new Set(['seat-1']));
    fixture.detectChanges();
    component.handleSeatClick(component.seatById().get('seat-1')!);
    expect(component.liveAnnouncement()).toContain('Deselected seat in row A, seat 1');
  });

  it('renders all active sections simultaneously on one canvas without tabs', () => {
    expect(fixture.nativeElement.querySelector('.section-tab-btn')).toBeNull();
    expect(fixture.nativeElement.querySelectorAll('.section-node').length).toBe(2);

    const renderedIds = new Set(
      Array.from(fixture.nativeElement.querySelectorAll('.seat-item')).map((node) =>
        (node as Element).getAttribute('data-seat-id'),
      ),
    );
    expect(renderedIds.has('seat-1')).toBeTrue();
    expect(renderedIds.has('seat-b1')).toBeTrue();

    const canvas = fixture.nativeElement.querySelector('app-layout-canvas');
    expect(canvas).toBeTruthy();
  });

  it('delegates spatial rendering to the shared canvas in read-only mode', () => {
    const canvasSections = component.canvasSections();
    expect(canvasSections.map((section) => section.sectionId)).toEqual(['section-a', 'section-b']);
    expect(component.canvasElements().length).toBe(2);

    const rotated = canvasSections.find((section) => section.sectionId === 'section-b')!;
    expect(rotated.rotationDeg).toBe(15);
    const rotatedNode = Array.from(fixture.nativeElement.querySelectorAll('.section-node')).find(
      (node) => (node as Element).getAttribute('transform')?.includes('rotate(15 '),
    );
    expect(rotatedNode).toBeTruthy();
  });

  it('derives legacy grid fallbacks matching the event-service adapter defaults', () => {
    const legacySections: SeatMapSectionResponse[] = [
      {
        sectionId: 'legacy',
        name: 'Legacy Hall',
        rowCount: 2,
        colCount: 10,
        seats: [],
      },
    ];
    const legacySeats: Seat[] = [
      createSeat({
        id: 'legacy-1',
        sectionId: 'legacy',
        sectionName: 'Legacy Hall',
        gridX: 2,
        gridY: 1,
        seatNumber: 3,
        positionX: undefined,
        positionY: undefined,
        sectionPositionX: undefined,
        sectionPositionY: undefined,
        sectionWidth: undefined,
        sectionHeight: undefined,
        sectionRotationDeg: undefined,
        sectionZIndex: undefined,
      }),
    ];
    fixture.componentRef.setInput('sectionsData', legacySections);
    fixture.componentRef.setInput('seats', legacySeats);
    fixture.detectChanges();

    const canvas = component.canvasSections()[0];
    expect(canvas.positionX).toBe(0);
    expect(canvas.positionY).toBe(0);
    expect(canvas.width).toBe(440);
    expect(canvas.height).toBe(88);
    expect(canvas.rotationDeg).toBe(0);
    expect(canvas.zIndex).toBe(0);
    expect(canvas.seats[0].positionX).toBe(88);
    expect(canvas.seats[0].positionY).toBe(44);
  });

  it('builds a tier-colored legend and dims without hiding on highlight', () => {
    const legend = component.categoryLegend();
    expect(legend.map((tier) => tier.categoryName)).toEqual(['Categoria A', 'Categoria B']);
    expect(legend[0].price).toBe(150);
    expect(legend[0].color).toBe('#6366f1');
    expect(legend[1].color).toBe('#f97316');
    expect(legend[0].color).not.toBe(legend[1].color);
    expect(legend[0].availableCount).toBe(11);

    const seatCountBefore = component
      .canvasSections()
      .reduce((sum, section) => sum + section.seats.length, 0);
    component.toggleLegendCategory('tier-a');
    fixture.detectChanges();

    expect(component.isTierHighlighted('tier-a')).toBeTrue();
    expect(component.customerSeatStates().get('seat-b1')?.dimmed).toBeTrue();
    expect(component.customerSeatStates().get('seat-1')?.dimmed).toBeFalse();
    // Highlighting never removes seats from the unified canvas.
    expect(component.canvasSections().reduce((sum, section) => sum + section.seats.length, 0)).toBe(
      seatCountBefore,
    );

    component.toggleLegendCategory('tier-a');
    expect(component.isTierHighlighted('tier-a')).toBeFalse();
  });

  it('colors available seats by tier and mutes held/sold seats in gray', () => {
    expect(component.customerSeatStates().get('seat-1')?.color).toBe('#6366f1');
    expect(component.customerSeatStates().get('seat-b1')?.color).toBe('#f97316');

    fixture.componentRef.setInput(
      'seats',
      buildSeats().map((seat) =>
        seat.id === 'seat-1' ? { ...seat, status: 'HELD' as const } : seat,
      ),
    );
    fixture.detectChanges();

    expect(component.customerSeatStates().get('seat-1')?.status).toBe('HELD');
    expect(component.customerSeatStates().get('seat-1')?.color).toBe(MUTED_SEAT_GRAY);
    expect(component.customerSeatStates().get('seat-2')?.status).toBe('AVAILABLE');
  });

  it('updates only the matching seat ID on live status changes, keeping geometry', () => {
    const before = component.canvasSections();
    const seat1Before = before
      .flatMap((section) => section.seats)
      .find((seat) => seat.seatId === 'seat-2')!;

    fixture.componentRef.setInput(
      'seats',
      buildSeats().map((seat) =>
        seat.id === 'seat-2' ? { ...seat, status: 'SOLD' as const } : seat,
      ),
    );
    fixture.detectChanges();

    const after = component.canvasSections();
    const seat1After = after
      .flatMap((section) => section.seats)
      .find((seat) => seat.seatId === 'seat-2')!;
    expect(seat1After.positionX).toBe(seat1Before.positionX);
    expect(seat1After.positionY).toBe(seat1Before.positionY);
    expect(component.customerSeatStates().get('seat-2')?.status).toBe('SOLD');
    expect(component.customerSeatStates().get('seat-1')?.status).toBe('AVAILABLE');
  });

  it('maps canvas seat activation back to the original Seat by ID', () => {
    fixture.componentRef.setInput('selectedSeatIds', new Set<string>());
    const emitted: Seat[] = [];
    component.seatToggled.subscribe((seat) => emitted.push(seat));

    component.onCanvasSeatSelected(canvasEventFor('seat-b1'));

    expect(emitted.length).toBe(1);
    expect(emitted[0]).toEqual(
      jasmine.objectContaining({ id: 'seat-b1', sectionId: 'section-b', price: 120 }),
    );
  });

  it('reaches seats by pointer through overlapping decorative elements', () => {
    const elementNodes = fixture.nativeElement.querySelectorAll('.layout-element-node');
    expect(elementNodes.length).toBe(2);
    for (const node of Array.from(elementNodes)) {
      const element = node as HTMLElement;
      expect(element.getAttribute('aria-hidden')).toBe('true');
      expect(element.getAttribute('tabindex')).toBe('-1');
      expect(getComputedStyle(element).pointerEvents).toBe('none');
    }

    fixture.componentRef.setInput('selectedSeatIds', new Set<string>());
    const emitted: Seat[] = [];
    component.seatToggled.subscribe((seat) => emitted.push(seat));

    const seatNode = fixture.nativeElement.querySelector('[data-seat-id="seat-3"]') as HTMLElement;
    seatNode.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();

    expect(emitted.map((seat) => seat.id)).toEqual(['seat-3']);
  });

  it('excludes inactive sections and keeps inactive seats non-focusable', () => {
    expect(
      component.canvasSections().some((section) => section.sectionId === 'section-c'),
    ).toBeFalse();
    expect(fixture.nativeElement.querySelector('[data-seat-id="seat-loft"]')).toBeNull();

    const inactive = fixture.nativeElement.querySelector(
      '[data-seat-id="seat-inactive"]',
    ) as HTMLElement;
    expect(inactive.getAttribute('tabindex')).toBe('-1');
    expect(inactive.getAttribute('aria-disabled')).toBe('true');
    expect(component.bookableSeats().some((seat) => seat.id === 'seat-inactive')).toBeFalse();
  });

  it('keeps a single roving tabindex across the unified hall', () => {
    const focused = Array.from(fixture.nativeElement.querySelectorAll('.seat-item[tabindex="0"]'));
    expect(focused.length).toBe(1);
    expect((focused[0] as Element).getAttribute('data-seat-id')).toBe(
      component.effectiveActiveSeatId(),
    );
  });

  it('moves keyboard focus by grid coordinates, ignoring visual rotation', () => {
    const first = fixture.nativeElement.querySelector('[data-seat-id="seat-1"]') as HTMLElement;
    first.dispatchEvent(new FocusEvent('focusin', { bubbles: true }));
    first.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    fixture.detectChanges();

    expect(document.activeElement?.getAttribute('data-seat-id')).toBe('seat-2');
    expect(component.effectiveActiveSeatId()).toBe('seat-2');
  });

  it('exposes status labels, tooltips, and conflict flags per seat', () => {
    fixture.componentRef.setInput('conflictingSeatIds', new Set(['seat-1']));
    fixture.detectChanges();

    const node = fixture.nativeElement.querySelector('[data-seat-id="seat-1"]') as HTMLElement;
    expect(node.getAttribute('aria-label')).toContain('Categoria A');
    expect(node.getAttribute('aria-label')).toContain('available');
    expect(node.querySelector('title')?.textContent).toContain('Categoria A');
    expect(node.classList.contains('seat-conflicted')).toBeTrue();
  });

  it('renders preview mode read-only without prices or selection', () => {
    fixture.componentRef.setInput('previewMode', true);
    fixture.componentRef.setInput('selectedSeatIds', new Set<string>());
    fixture.detectChanges();

    expect(component.categoryLegend()).toEqual([]);
    expect(fixture.nativeElement.querySelector('.pricing-legend-bar')).toBeNull();
    expect(fixture.nativeElement.querySelectorAll('.seat-item').length).toBeGreaterThan(0);

    const emitted: Seat[] = [];
    component.seatToggled.subscribe((seat) => emitted.push(seat));
    component.onCanvasSeatSelected(canvasEventFor('seat-1'));

    expect(emitted).toEqual([]);
    expect(component.liveAnnouncement()).toContain('not simulated');
  });

  it('renders a named empty state when no active layout exists', () => {
    fixture.componentRef.setInput('seats', []);
    fixture.componentRef.setInput('sectionsData', []);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-layout-canvas')).toBeNull();
    const emptyState = fixture.nativeElement.querySelector('.empty-layout-state');
    expect(emptyState?.getAttribute('role')).toBe('status');
    expect(emptyState?.textContent).toContain('No seats configured');
  });

  it('renders identical section/seat/element transforms in customer vs preview mode', () => {
    const snapshotTransforms = (): string[] =>
      Array.from(fixture.nativeElement.querySelectorAll('.section-node')).map((node) =>
        (node as Element).getAttribute('transform'),
      ) as string[];
    const snapshotElementGeometry = (): string[] =>
      Array.from(fixture.nativeElement.querySelectorAll('.layout-element-node')).map((node) => {
        const element = node as Element;
        const rect = element.querySelector('rect');
        return `${element.getAttribute('transform')}::${rect?.getAttribute('width')}x${rect?.getAttribute('height')}`;
      });
    const snapshotSeatIds = (): (string | null)[] =>
      Array.from(fixture.nativeElement.querySelectorAll('.seat-item')).map((node) =>
        (node as Element).getAttribute('data-seat-id'),
      );

    // Customer mode: STAGE element renders with its saved geometry.
    expect(component.canvasElements().length).toBe(2);
    const customerSections = component.canvasSections();
    const customerElementsModel = component.canvasElements();
    const customerTransforms = snapshotTransforms();
    const customerElements = snapshotElementGeometry();
    const customerSeats = snapshotSeatIds();
    expect(customerElements.length).toBe(2);
    expect(customerElements[0]).toContain('translate(0 0)');
    expect(customerElements[0]).toContain('528x60');

    // Same fixture through the admin Customer-preview path: geometry identical,
    // only presentation (prices/legend/selection) is suppressed.
    fixture.componentRef.setInput('previewMode', true);
    fixture.detectChanges();

    expect(component.canvasSections()).toEqual(customerSections);
    expect(component.canvasElements()).toEqual(customerElementsModel);
    expect(snapshotTransforms()).toEqual(customerTransforms);
    expect(snapshotElementGeometry()).toEqual(customerElements);
    expect(snapshotSeatIds()).toEqual(customerSeats);
    // Preview still shows the saved STAGE (unsaved drafts flow via previewElements).
    expect(fixture.nativeElement.querySelectorAll('.layout-element-node').length).toBe(2);
  });

  it('drops null/degenerate/unknown elements so a saved STAGE stays visible', () => {
    // Backend EventServiceImpl.toLayoutElement emits geometry: null for legacy
    // rows; those must never become invisible zero-size rects at the origin
    // (which would also pollute the auto-fit bounds), and unknown runtime
    // types must never surface designer validation chrome to customers.
    fixture.componentRef.setInput('layoutElements', [
      ...layoutElements,
      { elementId: 'ghost', type: 'STAGE', label: 'Ghost', geometry: null, zIndex: 0 },
      {
        elementId: 'flat',
        type: 'AISLE',
        label: null,
        geometry: { x: 1, y: 1, width: 0, height: 10 },
        zIndex: 0,
      },
      {
        elementId: 'mystery',
        type: 'FUTURE_TYPE',
        label: null,
        geometry: { x: 1, y: 1, width: 10, height: 10 },
        zIndex: 0,
      },
    ]);
    fixture.detectChanges();

    expect(component.canvasElements().map((element) => element.elementId)).toEqual([
      'stage-1',
      'aisle-1',
    ]);
    // Saved STAGE keeps its exact geometry; no zero-size rects reach the canvas.
    expect(component.canvasElements()[0]).toEqual(
      jasmine.objectContaining({
        type: 'STAGE',
        geometry: { x: 0, y: 0, width: 528, height: 60, rotationDeg: 0 },
      }),
    );
    expect(fixture.nativeElement.querySelectorAll('.layout-element-node').length).toBe(2);
  });

  it('enables one-shot auto-fit on the read-only customer canvas', () => {
    const canvasDebug = fixture.debugElement.query(By.directive(LayoutCanvasComponent));
    expect(canvasDebug).toBeTruthy();
    const canvas = canvasDebug.componentInstance as LayoutCanvasComponent;
    expect(canvas.autoFitOnLoad()).toBeTrue();
    expect(canvas.editable()).toBeFalse();
    // Read-only fit covers sections AND floating elements (STAGE above seats).
    expect(canvas.sections().length).toBe(2);
    expect(canvas.elements().length).toBe(2);
  });

  it('applies section shapeMetadata color to seats and section tiers in the legend', () => {
    const orangeSection: SeatMapSectionResponse = {
      sectionId: 'sec-orange',
      name: 'TEST',
      rowCount: 1,
      colCount: 2,
      isActive: true,
      positionX: 0,
      positionY: 0,
      width: 88,
      height: 44,
      rotationDeg: 0,
      zIndex: 0,
      shapeMetadata: { color: '#f97316' },
      seats: [],
      pricingTiers: [
        { id: 'tier-vip', sectionId: 'sec-orange', categoryName: 'VIP', price: 35, currency: 'USD' },
        { id: 'tier-std', sectionId: 'sec-orange', categoryName: 'Standard', price: 20, currency: 'USD' },
        { id: 'tier-child', sectionId: 'sec-orange', categoryName: 'Child', price: 10, currency: 'USD' },
      ],
    };
    const orangeSeats: Seat[] = [
      createSeat({
        id: 'orange-1',
        sectionId: 'sec-orange',
        sectionName: 'TEST',
        seatNumber: 1,
        gridX: 0,
        gridY: 0,
        price: 20,
        currency: 'USD',
        status: 'AVAILABLE',
        isActive: true,
        categoryName: 'Standard',
        pricingTierId: 'tier-std',
      }),
      createSeat({
        id: 'orange-2',
        sectionId: 'sec-orange',
        sectionName: 'TEST',
        seatNumber: 2,
        gridX: 1,
        gridY: 0,
        price: 20,
        currency: 'USD',
        status: 'AVAILABLE',
        isActive: true,
        categoryName: 'Standard',
        pricingTierId: 'tier-std',
      }),
    ];

    fixture.componentRef.setInput('sectionsData', [orangeSection]);
    fixture.componentRef.setInput('seats', orangeSeats);
    fixture.detectChanges();

    // Seats must use section shapeMetadata color (#f97316), NOT arbitrary category palette
    expect(component.customerSeatStates().get('orange-1')?.color).toBe('#f97316');
    expect(component.customerSeatStates().get('orange-2')?.color).toBe('#f97316');

    // All tiers on this section (VIP, Standard, Child) must inherit the section's color
    const legend = component.categoryLegend();
    expect(legend.length).toBe(3);
    for (const tier of legend) {
      expect(tier.color).toBe('#f97316');
    }
  });

  it('positions synthetic stage cleanly above sections without colliding with seats when minY is 0', () => {
    fixture.componentRef.setInput('layoutElements', []);
    fixture.componentRef.setInput('sectionsData', [
      {
        sectionId: 'sec-top',
        name: 'TopSec',
        rowCount: 5,
        colCount: 5,
        isActive: true,
        positionX: 0,
        positionY: 0,
        width: 220,
        height: 220,
        seats: [],
      },
    ]);
    fixture.detectChanges();

    const stage = component.canvasElements().find((e) => e.type === 'STAGE');
    expect(stage).toBeDefined();
    // minY is 0, stage height is 60; stageY must be 0 - 60 - 30 = -90 (30px above sections)
    expect(stage!.geometry.y).toBe(-90);
    expect(stage!.geometry.height).toBe(60);
    // Stage must be completely above the section: stage.y + stage.height = -30 < 0
    expect(stage!.geometry.y + stage!.geometry.height).toBeLessThan(0);
  });

  it('automatically staggers multiple sections positioned at origin (0, 0) to avoid overlap', () => {
    fixture.componentRef.setInput('sectionsData', [
      {
        sectionId: 'sec-1',
        name: 'TEST',
        rowCount: 10,
        colCount: 15,
        isActive: true,
        positionX: 0,
        positionY: 0,
        width: 660,
        height: 440,
        seats: [],
      },
      {
        sectionId: 'sec-2',
        name: 'QWERR',
        rowCount: 5,
        colCount: 5,
        isActive: true,
        positionX: 0,
        positionY: 0,
        width: 220,
        height: 220,
        seats: [],
      },
    ]);
    fixture.detectChanges();

    const canvasSecs = component.canvasSections();
    expect(canvasSecs.length).toBe(2);
    expect(canvasSecs[0].positionY).toBe(0);
    // Second section must be placed below the first section (height 440 + 44 gap = 484)
    expect(canvasSecs[1].positionY).toBe(484);
    // They must not overlap vertically
    expect(canvasSecs[1].positionY).toBeGreaterThanOrEqual(
      canvasSecs[0].positionY + canvasSecs[0].height,
    );
  });

  it('preserves exact designer coordinates when sections have explicit non-zero positions', () => {
    fixture.componentRef.setInput('sectionsData', [
      {
        sectionId: 'sec-test',
        name: 'TEST',
        rowCount: 10,
        colCount: 15,
        isActive: true,
        positionX: 100,
        positionY: 350,
        width: 660,
        height: 440,
        seats: [],
      },
      {
        sectionId: 'sec-qwerr',
        name: 'QWERR',
        rowCount: 5,
        colCount: 5,
        isActive: true,
        positionX: 300,
        positionY: 850,
        width: 220,
        height: 220,
        seats: [],
      },
    ]);
    fixture.detectChanges();

    const canvasSecs = component.canvasSections();
    expect(canvasSecs.length).toBe(2);
    expect(canvasSecs[0].positionX).toBe(100);
    expect(canvasSecs[0].positionY).toBe(350);
    expect(canvasSecs[1].positionX).toBe(300);
    expect(canvasSecs[1].positionY).toBe(850);
  });

  it('groups pricing legend by section with section-branded cards and chips in sectionLegendGroups', () => {
    const groups = component.sectionLegendGroups();
    expect(groups.length).toBe(2);

    // Section A group
    expect(groups[0].sectionName).toBe('Orchestra');
    expect(groups[0].sectionColor).toBe('#6366f1');
    expect(groups[0].tiers.length).toBe(1);
    expect(groups[0].tiers[0].categoryName).toBe('Categoria A');
    expect(groups[0].tiers[0].price).toBe(150);
    expect(groups[0].tiers[0].color).toBe('#6366f1');

    // Section B group
    expect(groups[1].sectionName).toBe('Balcony');
    expect(groups[1].sectionColor).toBe('#f97316');
    expect(groups[1].tiers.length).toBe(1);
    expect(groups[1].tiers[0].categoryName).toBe('Categoria B');
    expect(groups[1].tiers[0].price).toBe(120);
    expect(groups[1].tiers[0].color).toBe('#f97316');
  });

  it('accurately preserves 2D venue designer layout when top section starts at (0, 0) and second section is below it', () => {
    // Simulating sections arriving in alphabetical order [QWERR, TEST] from backend
    fixture.componentRef.setInput('layoutElements', []);
    fixture.componentRef.setInput('sectionsData', [
      {
        sectionId: 'sec-qwerr',
        name: 'qwerr',
        rowCount: 5,
        colCount: 5,
        isActive: true,
        positionX: 0,
        positionY: 524,
        width: 260,
        height: 260,
        shapeMetadata: { color: '#8b5cf6' },
        seats: [],
      },
      {
        sectionId: 'sec-test',
        name: 'test',
        rowCount: 10,
        colCount: 15,
        isActive: true,
        positionX: 0,
        positionY: 0,
        width: 700,
        height: 480,
        shapeMetadata: { color: '#f97316' },
        seats: [],
      },
    ]);
    fixture.detectChanges();

    const canvasSecs = component.canvasSections();
    expect(canvasSecs.length).toBe(2);

    const qwerrSec = canvasSecs.find((s) => s.sectionId === 'sec-qwerr')!;
    const testSec = canvasSecs.find((s) => s.sectionId === 'sec-test')!;

    // TEST must be at top (y = 0) and QWERR must be below it (y = 524)
    expect(testSec.positionX).toBe(0);
    expect(testSec.positionY).toBe(0);
    expect((testSec.shapeMetadata as Record<string, unknown>)?.['color']).toBe('#f97316');

    expect(qwerrSec.positionX).toBe(0);
    expect(qwerrSec.positionY).toBe(524);
    expect((qwerrSec.shapeMetadata as Record<string, unknown>)?.['color']).toBe('#8b5cf6');

    // Synthetic STAGE must be placed above the topmost section (minY = 0)
    const elements = component.canvasElements();
    const stage = elements.find((e) => e.type === 'STAGE')!;
    expect(stage).toBeTruthy();
    expect(stage.geometry.y).toBeLessThan(0);
  });
});
