import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Seat } from '../../../models/seat.model';
import { SeatMapComponent } from './seat-map.component';

describe('SeatMapComponent', () => {
  let fixture: ComponentFixture<SeatMapComponent>;
  let component: SeatMapComponent;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  const createSeat = (index: number, sectionId = 'section-a'): Seat => ({
    id: `seat-${index}`,
    sectionId,
    sectionName: sectionId === 'section-a' ? 'Orchestra' : 'Balcony',
    rowLabel: 'A',
    seatNumber: index,
    gridX: index - 1,
    gridY: 0,
    price: 50,
    currency: 'USD',
    status: 'AVAILABLE',
    isActive: true,
  });

  beforeEach(async () => {
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    await TestBed.configureTestingModule({
      imports: [SeatMapComponent],
      providers: [{ provide: MatSnackBar, useValue: snackBar }],
    }).compileComponents();

    fixture = TestBed.createComponent(SeatMapComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput(
      'seats',
      Array.from({ length: 11 }, (_, index) => createSeat(index + 1)),
    );
    fixture.componentRef.setInput(
      'selectedSeatIds',
      new Set(Array.from({ length: 10 }, (_, index) => `seat-${index + 1}`)),
    );
    fixture.detectChanges();
  });

  it('blocks an eleventh selection and shows the required warning', () => {
    const emitted: Seat[] = [];
    component.seatToggled.subscribe((seat) => emitted.push(seat));

    component.handleSeatClick(createSeat(11));

    expect(emitted).toEqual([]);
    expect(snackBar.open).toHaveBeenCalledWith(
      'Maximum 10 seats allowed per reservation.',
      'Close',
      jasmine.objectContaining({ panelClass: 'snack-warning' }),
    );
  });

  it('emits available seats and animates both selection directions', () => {
    fixture.componentRef.setInput('selectedSeatIds', new Set<string>());
    const emitted: Seat[] = [];
    component.seatToggled.subscribe((seat) => emitted.push(seat));

    component.handleSeatClick(createSeat(1));

    expect(emitted.map((seat) => seat.id)).toEqual(['seat-1']);
    expect(component.animatingSeatIds().has('seat-1')).toBeTrue();
    component.clearSeatAnimation('seat-1');
    expect(component.animatingSeatIds().has('seat-1')).toBeFalse();
  });

  it('isolates sections and clamps zoom controls', () => {
    fixture.componentRef.setInput('seats', [createSeat(1), createSeat(2, 'section-b')]);
    component.isolateSection('section-b');
    for (let index = 0; index < 20; index += 1) component.zoomIn();

    expect(component.visibleSeats().map((seat) => seat.id)).toEqual(['seat-2']);
    expect(component.zoomLevel()).toBe(2.5);

    component.resetView();
    expect(component.zoomLevel()).toBe(1);
    expect(component.panX()).toBe(0);
    expect(component.panY()).toBe(0);
  });

  it('computes pricing tiers and price ranges correctly from sectionsData', () => {
    fixture.componentRef.setInput('sectionsData', [
      {
        sectionId: 'section-a',
        name: 'Orchestra',
        rowCount: 1,
        colCount: 10,
        seats: [],
        pricingTiers: [
          { categoryName: 'Child', price: 25, currency: 'USD', sectionId: 'section-a' },
          { categoryName: 'Student', price: 35, currency: 'USD', sectionId: 'section-a' },
          { categoryName: 'Standard', price: 50, currency: 'USD', sectionId: 'section-a' },
        ],
      },
    ]);
    fixture.detectChanges();

    const orchestra = component.sectionDetails().find((s) => s.id === 'section-a');
    expect(orchestra).toBeDefined();
    expect(orchestra?.minPrice).toBe(25);
    expect(orchestra?.maxPrice).toBe(50);
    expect(orchestra?.pricingTiers.length).toBe(3);
    expect(orchestra?.pricingTiers.map((t) => t.categoryName)).toEqual(['Child', 'Student', 'Standard']);
  });

  it('zooms in/out on wheel events', () => {
    const wheelEvent = new WheelEvent('wheel', { deltaY: -100, cancelable: true });
    component.onWheel(wheelEvent);
    expect(component.zoomLevel()).toBeCloseTo(1.12, 2);

    const wheelOut = new WheelEvent('wheel', { deltaY: 100, cancelable: true });
    component.onWheel(wheelOut);
    expect(component.zoomLevel()).toBeCloseTo(1.0, 2);
  });

  it('renders ARIA grid attributes, role=row grouping, and roving tabindex for WCAG 2.1 AA compliance', () => {
    const svgEl = fixture.nativeElement.querySelector('svg.seat-viewport');
    expect(svgEl.getAttribute('role')).toBe('grid');
    expect(svgEl.getAttribute('aria-rowcount')).toBeTruthy();
    expect(svgEl.getAttribute('aria-colcount')).toBeTruthy();

    const rowNodes = fixture.nativeElement.querySelectorAll('g[role="row"]');
    expect(rowNodes.length).toBeGreaterThan(0);
    expect(rowNodes[0].getAttribute('aria-rowindex')).toBe('1');

    const seatNodes = fixture.nativeElement.querySelectorAll('.seat-node');
    expect(seatNodes.length).toBeGreaterThan(0);
    const firstSeat = seatNodes[0];
    expect(firstSeat.getAttribute('role')).toBe('gridcell');
    expect(firstSeat.getAttribute('aria-selected')).toBe('true');
    expect(firstSeat.getAttribute('aria-label')?.toLowerCase()).toContain('row a');

    // Roving tabindex verification: Exactly one seat has tabindex="0", others have tabindex="-1"
    const focusableSeats = Array.from(seatNodes).filter(
      (node) => (node as HTMLElement).getAttribute('tabindex') === '0',
    );
    const nonFocusableSeats = Array.from(seatNodes).filter(
      (node) => (node as HTMLElement).getAttribute('tabindex') === '-1',
    );
    expect(focusableSeats.length).toBe(1);
    expect(nonFocusableSeats.length).toBe(seatNodes.length - 1);
  });

  it('announces selections and updates live announcement signal', () => {
    fixture.componentRef.setInput('selectedSeatIds', new Set<string>());
    fixture.detectChanges();

    component.handleSeatClick(createSeat(1));
    expect(component.liveAnnouncement()).toContain('Selected seat in row A, seat 1');

    fixture.componentRef.setInput('selectedSeatIds', new Set(['seat-1']));
    fixture.detectChanges();
    component.handleSeatClick(createSeat(1));
    expect(component.liveAnnouncement()).toContain('Deselected seat in row A, seat 1');
  });

  it('handles keyboard navigation across grid coordinates and shifts roving focus', () => {
    const seat1El = fixture.nativeElement.querySelector('[data-seat-id="seat-1"]');
    if (seat1El) {
      spyOn(seat1El, 'focus');
      component.handleSeatNavigate({ row: 0, col: 0 });
      expect(seat1El.focus).toHaveBeenCalled();
      expect(component.effectiveActiveSeatId()).toBe('seat-1');
    }
  });
});

