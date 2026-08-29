import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Seat } from '../../../models/seat.model';
import { SelectionDockComponent } from './selection-dock.component';

describe('SelectionDockComponent', () => {
  let fixture: ComponentFixture<SelectionDockComponent>;
  let component: SelectionDockComponent;

  const createSeat = (index: number, price = 50, currency = 'USD'): Seat => ({
    id: `seat-${index}`,
    sectionId: 'sec-1',
    sectionName: 'Floor',
    rowLabel: 'A',
    seatNumber: index,
    gridX: index,
    gridY: 0,
    price,
    currency,
    status: 'AVAILABLE',
    isActive: true,
  });

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SelectionDockComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(SelectionDockComponent);
    component = fixture.componentInstance;
  });

  it('should compute count and total price correctly', () => {
    fixture.componentRef.setInput('selectedSeats', [
      createSeat(1, 45),
      createSeat(2, 55),
    ]);
    fixture.detectChanges();

    expect(component.count()).toBe(2);
    expect(component.totalPrice()).toBe(100);
    expect(component.currencyCode()).toBe('USD');
  });

  it('should emit seatRemoved when removal button is clicked', () => {
    const seat1 = createSeat(1, 45);
    fixture.componentRef.setInput('selectedSeats', [seat1]);
    fixture.detectChanges();

    const removedSeats: Seat[] = [];
    component.seatRemoved.subscribe((seat) => removedSeats.push(seat));

    const removeBtn = fixture.nativeElement.querySelector('.seat-pill button');
    removeBtn?.click();

    expect(removedSeats).toEqual([seat1]);
  });

  it('should emit checkoutTriggered when checkout button is clicked', () => {
    fixture.componentRef.setInput('selectedSeats', [createSeat(1, 45)]);
    fixture.detectChanges();

    let triggered = false;
    component.checkoutTriggered.subscribe(() => {
      triggered = true;
    });

    const checkoutBtn = fixture.nativeElement.querySelector('app-tactile-button button');
    checkoutBtn?.click();

    expect(triggered).toBeTrue();
  });
});
