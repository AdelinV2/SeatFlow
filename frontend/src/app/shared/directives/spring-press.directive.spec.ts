import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SpringPressDirective } from './spring-press.directive';

@Component({
  standalone: true,
  imports: [SpringPressDirective],
  template: `<button [appSpringPress]="disabled()" [disabled]="disabled()">Click me</button>`,
})
class TestHostComponent {
  readonly disabled = signal(false);
}

describe('SpringPressDirective', () => {
  let fixture: ComponentFixture<TestHostComponent>;
  let hostComponent: TestHostComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [TestHostComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(TestHostComponent);
    hostComponent = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('applies tactile spring damping classes to host', () => {
    const button: HTMLElement = fixture.nativeElement.querySelector('button');
    expect(button.classList).toContain('btn-spring');
    expect(button.classList).toContain('active:scale-[0.97]');
    expect(button.classList).toContain('transition-all');
  });

  it('handles disabled state via directive input', () => {
    hostComponent.disabled.set(true);
    fixture.detectChanges();

    const button: HTMLButtonElement = fixture.nativeElement.querySelector('button');
    expect(button.disabled).toBeTrue();
    expect(button.classList).toContain('pointer-events-none');
    expect(button.classList).toContain('opacity-50');
  });
});
