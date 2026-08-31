import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { KeyboardSeatNavDirective } from './keyboard-seat-nav.directive';

@Component({
  standalone: true,
  imports: [KeyboardSeatNavDirective],
  template: `
    <div
      appKeyboardSeatNav
      [currentRow]="row()"
      [currentCol]="col()"
      [maxRows]="maxRows()"
      [maxCols]="maxCols()"
      (navigate)="onNavigate($event)"
      (activate)="onActivate()"
      tabindex="0"
    >
      Test Seat
    </div>
  `,
})
class TestHostComponent {
  readonly row = signal(2);
  readonly col = signal(3);
  readonly maxRows = signal(5);
  readonly maxCols = signal(6);

  lastNavigation?: { row: number; col: number };
  activateCount = 0;

  onNavigate(coords: { row: number; col: number }): void {
    this.lastNavigation = coords;
  }

  onActivate(): void {
    this.activateCount++;
  }
}

describe('KeyboardSeatNavDirective', () => {
  let fixture: ComponentFixture<TestHostComponent>;
  let component: TestHostComponent;
  let element: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [TestHostComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(TestHostComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    element = fixture.nativeElement.querySelector('[appKeyboardSeatNav]');
  });

  it('should navigate up on ArrowUp', () => {
    const event = new KeyboardEvent('keydown', { key: 'ArrowUp', cancelable: true });
    element.dispatchEvent(event);

    expect(component.lastNavigation).toEqual({ row: 1, col: 3 });
    expect(event.defaultPrevented).toBeTrue();
  });

  it('should navigate down on ArrowDown', () => {
    const event = new KeyboardEvent('keydown', { key: 'ArrowDown', cancelable: true });
    element.dispatchEvent(event);

    expect(component.lastNavigation).toEqual({ row: 3, col: 3 });
    expect(event.defaultPrevented).toBeTrue();
  });

  it('should navigate left on ArrowLeft', () => {
    const event = new KeyboardEvent('keydown', { key: 'ArrowLeft', cancelable: true });
    element.dispatchEvent(event);

    expect(component.lastNavigation).toEqual({ row: 2, col: 2 });
    expect(event.defaultPrevented).toBeTrue();
  });

  it('should navigate right on ArrowRight', () => {
    const event = new KeyboardEvent('keydown', { key: 'ArrowRight', cancelable: true });
    element.dispatchEvent(event);

    expect(component.lastNavigation).toEqual({ row: 2, col: 4 });
    expect(event.defaultPrevented).toBeTrue();
  });

  it('should clamp at upper and left boundaries (0, 0)', () => {
    component.row.set(0);
    component.col.set(0);
    fixture.detectChanges();

    const eventUp = new KeyboardEvent('keydown', { key: 'ArrowUp', cancelable: true });
    element.dispatchEvent(eventUp);
    expect(component.lastNavigation).toEqual({ row: 0, col: 0 });

    const eventLeft = new KeyboardEvent('keydown', { key: 'ArrowLeft', cancelable: true });
    element.dispatchEvent(eventLeft);
    expect(component.lastNavigation).toEqual({ row: 0, col: 0 });
  });

  it('should clamp at lower and right boundaries (maxRows-1, maxCols-1)', () => {
    component.row.set(4);
    component.col.set(5);
    fixture.detectChanges();

    const eventDown = new KeyboardEvent('keydown', { key: 'ArrowDown', cancelable: true });
    element.dispatchEvent(eventDown);
    expect(component.lastNavigation).toEqual({ row: 4, col: 5 });

    const eventRight = new KeyboardEvent('keydown', { key: 'ArrowRight', cancelable: true });
    element.dispatchEvent(eventRight);
    expect(component.lastNavigation).toEqual({ row: 4, col: 5 });
  });

  it('should trigger activate on Enter key', () => {
    const event = new KeyboardEvent('keydown', { key: 'Enter', cancelable: true });
    element.dispatchEvent(event);

    expect(component.activateCount).toBe(1);
    expect(event.defaultPrevented).toBeTrue();
  });

  it('should trigger activate on Space key', () => {
    const event = new KeyboardEvent('keydown', { key: ' ', cancelable: true });
    element.dispatchEvent(event);

    expect(component.activateCount).toBe(1);
    expect(event.defaultPrevented).toBeTrue();
  });

  it('should ignore other keys like Tab or Escape', () => {
    const eventTab = new KeyboardEvent('keydown', { key: 'Tab', cancelable: true });
    element.dispatchEvent(eventTab);

    expect(component.lastNavigation).toBeUndefined();
    expect(component.activateCount).toBe(0);
    expect(eventTab.defaultPrevented).toBeFalse();
  });
});
