import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TactileButtonComponent } from './tactile-button.component';

describe('TactileButtonComponent', () => {
  let fixture: ComponentFixture<TactileButtonComponent>;
  let component: TactileButtonComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [TactileButtonComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(TactileButtonComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('emits the native mouse event when activated', () => {
    const clickSpy = jasmine.createSpy('click');
    component.clicked.subscribe(clickSpy);

    fixture.nativeElement.querySelector('button').click();

    expect(clickSpy).toHaveBeenCalledOnceWith(jasmine.any(MouseEvent));
  });

  it('suppresses clicks while disabled', () => {
    const clickSpy = jasmine.createSpy('click');
    component.clicked.subscribe(clickSpy);
    fixture.componentRef.setInput('disabled', true);
    fixture.detectChanges();

    const button: HTMLButtonElement = fixture.nativeElement.querySelector('button');
    button.click();

    expect(button.disabled).toBeTrue();
    expect(clickSpy).not.toHaveBeenCalled();
  });

  it('suppresses clicks and exposes busy state while loading', () => {
    const clickSpy = jasmine.createSpy('click');
    component.clicked.subscribe(clickSpy);
    fixture.componentRef.setInput('loading', true);
    fixture.detectChanges();

    const button: HTMLButtonElement = fixture.nativeElement.querySelector('button');
    button.click();

    expect(button.disabled).toBeTrue();
    expect(button.getAttribute('aria-busy')).toBe('true');
    expect(clickSpy).not.toHaveBeenCalled();
  });

  it('applies primary sheen, spring damping, and requested sizing', () => {
    fixture.componentRef.setInput('size', 'lg');
    fixture.detectChanges();

    const button: HTMLButtonElement = fixture.nativeElement.querySelector('button');

    expect(button.classList).toContain('animate-sheen');
    expect(button.classList).toContain('btn-spring');
    expect(button.classList).toContain('active:scale-[0.97]');
    expect(button.classList).toContain('px-6');
  });

  it('supports fullWidth expansion across host and button', () => {
    fixture.componentRef.setInput('fullWidth', true);
    fixture.detectChanges();

    const host: HTMLElement = fixture.nativeElement;
    const button: HTMLButtonElement = host.querySelector('button')!;

    expect(host.classList).toContain('w-full');
    expect(button.classList).toContain('w-full');
  });
});
