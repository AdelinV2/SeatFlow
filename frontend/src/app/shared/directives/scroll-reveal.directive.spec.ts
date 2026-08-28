import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ScrollRevealDirective } from './scroll-reveal.directive';

@Component({
  template: `
    <div appScrollReveal [delay]="100" [threshold]="0.2" id="target">
      Scroll Content
    </div>
  `,
  imports: [ScrollRevealDirective],
})
class TestHostComponent {}

describe('ScrollRevealDirective', () => {
  let fixture: ComponentFixture<TestHostComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [TestHostComponent, ScrollRevealDirective],
    }).compileComponents();

    fixture = TestBed.createComponent(TestHostComponent);
    fixture.detectChanges();
  });

  it('should initialize element styles', () => {
    const el = fixture.nativeElement.querySelector('#target') as HTMLElement;
    expect(el).toBeTruthy();
    expect(el.style.transition).toContain('opacity');
    expect(el.style.transition).toContain('transform');
  });
});
