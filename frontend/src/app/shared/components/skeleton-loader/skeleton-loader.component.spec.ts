import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SkeletonLoaderComponent } from './skeleton-loader.component';

describe('SkeletonLoaderComponent', () => {
  let fixture: ComponentFixture<SkeletonLoaderComponent>;
  let component: SkeletonLoaderComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SkeletonLoaderComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(SkeletonLoaderComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('renders default text skeleton with single line and status role', () => {
    const group: HTMLElement = fixture.nativeElement.querySelector('.skeleton-group');
    expect(group.getAttribute('role')).toBe('status');
    expect(group.getAttribute('aria-label')).toBe('Loading content');

    const lines = fixture.nativeElement.querySelectorAll('.skeleton-shimmer');
    expect(lines.length).toBe(1);
  });

  it('renders multiple lines when lines input is greater than 1', () => {
    fixture.componentRef.setInput('lines', 3);
    fixture.detectChanges();

    const lines = fixture.nativeElement.querySelectorAll('.skeleton-shimmer');
    expect(lines.length).toBe(3);
  });

  it('renders circle and rectangle shapes correctly', () => {
    fixture.componentRef.setInput('shape', 'circle');
    fixture.componentRef.setInput('width', '3rem');
    fixture.componentRef.setInput('height', '3rem');
    fixture.detectChanges();

    let shimmer: HTMLElement = fixture.nativeElement.querySelector('.skeleton-shimmer');
    expect(shimmer.classList).toContain('rounded-full');

    fixture.componentRef.setInput('shape', 'rectangle');
    fixture.detectChanges();

    shimmer = fixture.nativeElement.querySelector('.skeleton-shimmer');
    expect(shimmer.classList).toContain('rounded-2xl');
  });
});
