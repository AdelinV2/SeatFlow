import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SectionNodeComponent } from './section-node.component';
import { VenueSectionLayout } from '../../../../models/venue.model';
import { CornerHandle } from '../../../utils/layout-geometry';

describe('SectionNodeComponent', () => {
  let component: SectionNodeComponent;
  let fixture: ComponentFixture<SectionNodeComponent>;

  const mockSection: VenueSectionLayout = {
    sectionId: 'sec-101',
    name: 'Balcony Left',
    rowCount: 2,
    colCount: 2,
    isActive: true,
    positionX: 120,
    positionY: 80,
    width: 200,
    height: 150,
    rotationDeg: 15,
    zIndex: 1,
    shapeMetadata: null,
    seats: [
      {
        seatId: 'seat-1',
        rowLabel: 'A',
        seatNumber: 1,
        gridX: 0,
        gridY: 0,
        positionX: 30,
        positionY: 40,
        isActive: true,
      },
      {
        seatId: 'seat-2',
        rowLabel: 'A',
        seatNumber: 2,
        gridX: 1,
        gridY: 0,
        positionX: 80,
        positionY: 40,
        isActive: false, // inactive seat
      },
    ],
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SectionNodeComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(SectionNodeComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('section', mockSection);
    fixture.detectChanges();
  });

  it('applies translate and rotate transform once on the section node group', () => {
    // translate(120 80) rotate(15 100 75) where width/2 = 100, height/2 = 75
    expect(component.transformString()).toBe('translate(120 80) rotate(15 100 75)');
    const element = fixture.nativeElement.querySelector('.section-node');
    expect(element.getAttribute('transform')).toBe('translate(120 80) rotate(15 100 75)');
  });

  it('renders seats at their section-local coordinates', () => {
    const seatItems = fixture.nativeElement.querySelectorAll('.seat-item');
    expect(seatItems.length).toBe(2);

    // Seat 1 at local (30, 40)
    expect(seatItems[0].getAttribute('transform')).toBe('translate(30 40)');
    // Seat 2 at local (80, 40)
    expect(seatItems[1].getAttribute('transform')).toBe('translate(80 40)');
  });

  it('renders inactive seats with explicit .inactive class', () => {
    const seatItems = fixture.nativeElement.querySelectorAll('.seat-item');
    expect(seatItems[0].classList.contains('inactive')).toBeFalse();
    expect(seatItems[1].classList.contains('inactive')).toBeTrue();
  });

  it('renders inactive sections with explicit .section-inactive class and accessible indicator', () => {
    const inactiveSection: VenueSectionLayout = {
      ...mockSection,
      isActive: false,
    };
    fixture.componentRef.setInput('section', inactiveSection);
    fixture.detectChanges();

    const element = fixture.nativeElement.querySelector('.section-node');
    expect(element.classList.contains('section-inactive')).toBeTrue();
    expect(element.getAttribute('aria-label')).toContain('(inactive)');
  });

  it('does NOT render transform handles when selected=false', () => {
    fixture.componentRef.setInput('selected', false);
    fixture.componentRef.setInput('editable', true);
    fixture.detectChanges();

    const handles = fixture.nativeElement.querySelector('.transform-handles');
    expect(handles).toBeNull();
  });

  it('does NOT render transform handles when editable=false, even if selected=true', () => {
    fixture.componentRef.setInput('selected', true);
    fixture.componentRef.setInput('editable', false);
    fixture.detectChanges();

    const handles = fixture.nativeElement.querySelector('.transform-handles');
    expect(handles).toBeNull();
  });

  it('renders 4 corner handles and 1 rotation handle with accessible labels when selected=true and editable=true', () => {
    fixture.componentRef.setInput('selected', true);
    fixture.componentRef.setInput('editable', true);
    fixture.detectChanges();

    const handlesContainer = fixture.nativeElement.querySelector('.transform-handles');
    expect(handlesContainer).not.toBeNull();

    const cornerHandles = fixture.nativeElement.querySelectorAll('.corner-handle');
    expect(cornerHandles.length).toBe(4);

    const rotationHandle = fixture.nativeElement.querySelector('.rotation-handle');
    expect(rotationHandle).not.toBeNull();
    expect(rotationHandle.getAttribute('aria-label')).toBe('Rotate section');
    expect(rotationHandle.getAttribute('role')).toBe('button');

    const nw = fixture.nativeElement.querySelector('.corner-handle.nw');
    expect(nw.getAttribute('aria-label')).toBe('Resize north-west');
    expect(nw.getAttribute('role')).toBe('button');
  });

  it('emits handlePointerDown when a corner handle receives pointerdown', () => {
    fixture.componentRef.setInput('selected', true);
    fixture.componentRef.setInput('editable', true);
    fixture.detectChanges();

    let emittedHandle: string | null = null;
    component.handlePointerDown.subscribe((data) => {
      emittedHandle = data.handle;
    });

    const seHandle = fixture.nativeElement.querySelector('.corner-handle.se');
    const pointerEvent = new PointerEvent('pointerdown', { bubbles: true, cancelable: true });
    seHandle.dispatchEvent(pointerEvent);

    expect(emittedHandle as string | null).toBe('se');
  });

  it('emits handlePointerDown when the rotation handle receives pointerdown', () => {
    fixture.componentRef.setInput('selected', true);
    fixture.componentRef.setInput('editable', true);
    fixture.detectChanges();

    let emittedHandle: string | null = null;
    component.handlePointerDown.subscribe((data) => {
      emittedHandle = data.handle;
    });

    const rotHandle = fixture.nativeElement.querySelector('.rotation-handle');
    const pointerEvent = new PointerEvent('pointerdown', { bubbles: true, cancelable: true });
    rotHandle.dispatchEvent(pointerEvent);

    expect(emittedHandle as string | null).toBe('rotate');
  });

  it('emits sectionClick when section boundary is clicked', () => {
    let clickedSectionId: string | null = null;
    component.sectionClick.subscribe((data) => {
      clickedSectionId = data.section.sectionId;
    });

    const sectionEl = fixture.nativeElement.querySelector('.section-node');
    sectionEl.dispatchEvent(new MouseEvent('click', { bubbles: true }));

    expect(clickedSectionId as string | null).toBe('sec-101');
  });

  it('does NOT emit sectionClick on inactive section when in read-only mode (editable=false)', () => {
    fixture.componentRef.setInput('editable', false);
    fixture.componentRef.setInput('section', { ...mockSection, isActive: false });
    fixture.detectChanges();

    let clicked = false;
    component.sectionClick.subscribe(() => {
      clicked = true;
    });

    const sectionEl = fixture.nativeElement.querySelector('.section-node');
    sectionEl.dispatchEvent(new MouseEvent('click', { bubbles: true }));

    expect(clicked).toBeFalse();
  });

  it('emits seatClick when a seat circle is clicked', () => {
    let clickedSeatId: string | null = null;
    component.seatClick.subscribe((data) => {
      clickedSeatId = data.seat.seatId;
    });

    const seatItems = fixture.nativeElement.querySelectorAll('.seat-item');
    seatItems[0].dispatchEvent(new MouseEvent('click', { bubbles: true }));

    expect(clickedSeatId as string | null).toBe('seat-1');
  });
});
