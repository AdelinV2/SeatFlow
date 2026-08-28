import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HoldCountdownComponent } from './hold-countdown.component';

describe('HoldCountdownComponent', () => {
  let fixture: ComponentFixture<HoldCountdownComponent>;
  let component: HoldCountdownComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [HoldCountdownComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(HoldCountdownComponent);
    component = fixture.componentInstance;
    jasmine.clock().install();
    jasmine.clock().mockDate(new Date(2026, 7, 28, 10));
  });

  afterEach(() => {
    fixture.destroy();
    jasmine.clock().uninstall();
  });

  it('ticks down and formats the remaining time', () => {
    fixture.componentRef.setInput('expiresAt', new Date(Date.now() + 125_000));
    fixture.detectChanges();

    expect(component.remainingSeconds()).toBe(125);
    expect(component.formattedTime()).toBe('02:05');

    jasmine.clock().tick(1_000);
    fixture.detectChanges();

    expect(component.remainingSeconds()).toBe(124);
    expect(component.formattedTime()).toBe('02:04');
  });

  it('computes the SVG progress offset from the configured duration', () => {
    fixture.componentRef.setInput('totalDurationSeconds', 10);
    fixture.componentRef.setInput('expiresAt', new Date(Date.now() + 5_000));
    fixture.detectChanges();

    expect(component.progressPercentage()).toBe(50);
    expect(component.strokeDashoffset()).toBeCloseTo(component.circleCircumference / 2, 5);
  });

  it('enters the urgent state below 120 seconds', () => {
    fixture.componentRef.setInput('expiresAt', new Date(Date.now() + 119_000));
    fixture.detectChanges();

    expect(component.isUrgent()).toBeTrue();
    expect(fixture.nativeElement.querySelector('[role="timer"]').classList).toContain(
      'hold-countdown--urgent',
    );
  });

  it('emits expired exactly once at zero', () => {
    const expiredSpy = jasmine.createSpy('expired');
    component.expired.subscribe(expiredSpy);
    fixture.componentRef.setInput('expiresAt', new Date(Date.now() + 1_000));
    fixture.detectChanges();

    jasmine.clock().tick(1_000);
    fixture.detectChanges();
    jasmine.clock().tick(2_000);

    expect(component.remainingSeconds()).toBe(0);
    expect(component.formattedTime()).toBe('00:00');
    expect(expiredSpy).toHaveBeenCalledTimes(1);
  });
});
