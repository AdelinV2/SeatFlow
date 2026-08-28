import { ComponentFixture, TestBed } from '@angular/core/testing';
import { GlassCardComponent } from './glass-card.component';

describe('GlassCardComponent', () => {
  let fixture: ComponentFixture<GlassCardComponent>;
  let component: GlassCardComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [GlassCardComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(GlassCardComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('renders default raised elevation and medium padding', () => {
    const card: HTMLElement = fixture.nativeElement.querySelector('.glass-card');
    expect(card).toBeTruthy();
    expect(card.classList).toContain('shadow-[var(--shadow-diffuse)]');
    expect(card.classList).toContain('p-5');
  });

  it('applies flat elevation and small padding when configured', () => {
    fixture.componentRef.setInput('elevation', 'flat');
    fixture.componentRef.setInput('padding', 'sm');
    fixture.detectChanges();

    const card: HTMLElement = fixture.nativeElement.querySelector('.glass-card');
    expect(card.classList).toContain('shadow-none');
    expect(card.classList).toContain('p-3');
  });

  it('applies elevated and interactive classes when configured', () => {
    fixture.componentRef.setInput('elevation', 'elevated');
    fixture.componentRef.setInput('interactive', true);
    fixture.detectChanges();

    const card: HTMLElement = fixture.nativeElement.querySelector('.glass-card');
    expect(card.classList).toContain('glass-card--elevated');
    expect(card.classList).toContain('glass-card--interactive');
  });
});
