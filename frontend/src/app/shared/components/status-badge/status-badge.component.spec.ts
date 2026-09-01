import { ComponentFixture, TestBed } from '@angular/core/testing';
import { StatusBadgeComponent } from './status-badge.component';

describe('StatusBadgeComponent', () => {
  let fixture: ComponentFixture<StatusBadgeComponent>;
  let component: StatusBadgeComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [StatusBadgeComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(StatusBadgeComponent);
    component = fixture.componentInstance;
  });

  it('maps available status to the emerald badge', () => {
    fixture.componentRef.setInput('status', 'AVAILABLE');
    fixture.detectChanges();

    expect(component.configuration().label).toBe('Available');
    expect(component.configuration().classes).toContain('emerald');
    expect(fixture.nativeElement.textContent).toContain('Available');
  });

  it('normalizes status casing and maps cancelled to rose', () => {
    fixture.componentRef.setInput('status', 'cancelled');
    fixture.detectChanges();

    expect(component.configuration().label).toBe('Cancelled');
    expect(component.configuration().classes).toContain('rose');
  });

  it('maps confirmed, pending, and expired statuses accurately', () => {
    fixture.componentRef.setInput('status', 'CONFIRMED');
    fixture.detectChanges();
    expect(component.configuration().label).toBe('Confirmed');
    expect(component.configuration().classes).toContain('emerald');

    fixture.componentRef.setInput('status', 'PENDING');
    fixture.detectChanges();
    expect(component.configuration().label).toBe('Pending');
    expect(component.configuration().classes).toContain('amber');

    fixture.componentRef.setInput('status', 'EXPIRED');
    fixture.detectChanges();
    expect(component.configuration().label).toBe('Expired');
    expect(component.configuration().classes).toContain('rose');
  });

  it('renders an accessible neutral fallback for unknown statuses', () => {
    fixture.componentRef.setInput('status', 'ARCHIVED_CUSTOM_STATUS');
    fixture.detectChanges();

    const badge: HTMLElement = fixture.nativeElement.querySelector('[aria-label]');
    expect(component.configuration().label).toBe('Unknown');
    expect(badge.getAttribute('aria-label')).toBe('Status: Unknown');
  });
});
