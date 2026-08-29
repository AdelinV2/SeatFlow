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
});
