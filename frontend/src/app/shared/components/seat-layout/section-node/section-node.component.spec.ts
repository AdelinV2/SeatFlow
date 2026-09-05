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

  describe('read-only inactive sections and seats non-interactivity (REV-003)', () => {
    it('does NOT emit seatClick on click or Enter/Space keyboard in inactive read-only section', () => {
      fixture.componentRef.setInput('editable', false);
      fixture.componentRef.setInput('section', { ...mockSection, isActive: false });
      fixture.detectChanges();

      let clicked = false;
      component.seatClick.subscribe(() => {
        clicked = true;
      });

      const seatItems = fixture.nativeElement.querySelectorAll('.seat-item');
      expect(seatItems.length).toBe(2);

      // Verify non-interactive attributes on seats
      expect(seatItems[0].getAttribute('tabindex')).toBe('-1');
      expect(seatItems[0].getAttribute('role')).toBeNull();
      expect(seatItems[0].getAttribute('aria-disabled')).toBe('true');
      expect(seatItems[0].classList.contains('non-interactive')).toBeTrue();

      // Click seat
      seatItems[0].dispatchEvent(new MouseEvent('click', { bubbles: true }));
      expect(clicked).toBeFalse();

      // Keyboard Enter
      seatItems[0].dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }),
      );
      expect(clicked).toBeFalse();

      // Keyboard Space
      seatItems[0].dispatchEvent(
        new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true }),
      );
      expect(clicked).toBeFalse();
    });

    it('does NOT emit seatClick for an inactive seat even inside an active read-only section', () => {
      fixture.componentRef.setInput('editable', false);
      fixture.componentRef.setInput('section', { ...mockSection, isActive: true });
      fixture.detectChanges();

      let clickedSeatId: string | null = null;
      component.seatClick.subscribe((data) => {
        clickedSeatId = data.seat.seatId;
      });

      const seatItems = fixture.nativeElement.querySelectorAll('.seat-item');
      // Seat 2 is inactive in mockSection
      expect(seatItems[1].getAttribute('tabindex')).toBe('-1');
      expect(seatItems[1].classList.contains('non-interactive')).toBeTrue();

      seatItems[1].dispatchEvent(new MouseEvent('click', { bubbles: true }));
      expect(clickedSeatId).toBeNull();

      // Active seat 1 still emits
      seatItems[0].dispatchEvent(new MouseEvent('click', { bubbles: true }));
      expect(clickedSeatId as string | null).toBe('seat-1');
    });

    it('retains interactive seats in DOM with tabindex=0 and emits seatClick in editable mode for inactive section', () => {
      fixture.componentRef.setInput('editable', true);
      fixture.componentRef.setInput('section', { ...mockSection, isActive: false });
      fixture.detectChanges();

      let clickedSeatId: string | null = null;
      component.seatClick.subscribe((data) => {
        clickedSeatId = data.seat.seatId;
      });

      const seatItems = fixture.nativeElement.querySelectorAll('.seat-item');
      expect(seatItems.length).toBe(2);
      expect(seatItems[0].getAttribute('tabindex')).toBe('0');
      expect(seatItems[0].getAttribute('role')).toBe('button');
      expect(seatItems[0].classList.contains('non-interactive')).toBeFalse();

      seatItems[0].dispatchEvent(new MouseEvent('click', { bubbles: true }));
      expect(clickedSeatId as string | null).toBe('seat-1');
    });
  });

  describe('Interactive Seat Canvas Tools and Color Themes', () => {
    it('emits seatToggle when toolMode is "toggle" and a seat is clicked in editable mode', () => {
      fixture.componentRef.setInput('editable', true);
      fixture.componentRef.setInput('toolMode', 'toggle');
      fixture.detectChanges();

      let emittedSeat: any = null;
      component.seatToggle.subscribe((data) => {
        emittedSeat = data.seat;
      });

      const seatItems = fixture.nativeElement.querySelectorAll('.seat-item');
      seatItems[0].dispatchEvent(new MouseEvent('click', { bubbles: true }));

      expect(emittedSeat).not.toBeNull();
      expect(emittedSeat.seatId).toBe('seat-1');
    });

    it('emits seatPaint with paintColor when toolMode is "paint" and a seat is clicked', () => {
      fixture.componentRef.setInput('editable', true);
      fixture.componentRef.setInput('toolMode', 'paint');
      fixture.componentRef.setInput('paintColor', '#8B5CF6');
      fixture.detectChanges();

      let emittedColor: string | null = null;
      component.seatPaint.subscribe((data) => {
        emittedColor = data.color;
      });

      const seatItems = fixture.nativeElement.querySelectorAll('.seat-item');
      seatItems[0].dispatchEvent(new MouseEvent('click', { bubbles: true }));

      expect(emittedColor as string | null).toBe('#8B5CF6');
    });

    it('emits seatPaint on Enter in paint mode like the pointer path (keyboard parity)', () => {
      fixture.componentRef.setInput('editable', true);
      fixture.componentRef.setInput('toolMode', 'paint');
      fixture.componentRef.setInput('paintColor', '#8B5CF6');
      fixture.detectChanges();

      let emittedColor: string | null = null;
      let clicked = false;
      component.seatPaint.subscribe((data) => {
        emittedColor = data.color;
      });
      component.seatClick.subscribe(() => {
        clicked = true;
      });

      const seatItems = fixture.nativeElement.querySelectorAll('.seat-item');
      seatItems[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));

      expect(emittedColor as string | null).toBe('#8B5CF6');
      expect(clicked).toBeFalse();
    });

    it('emits seatToggle when a seat is double-clicked in select mode', () => {
      fixture.componentRef.setInput('editable', true);
      fixture.componentRef.setInput('toolMode', 'select');
      fixture.detectChanges();

      let toggledSeat: any = null;
      component.seatToggle.subscribe((data) => {
        toggledSeat = data.seat;
      });

      const seatItems = fixture.nativeElement.querySelectorAll('.seat-item');
      seatItems[0].dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));

      expect(toggledSeat).not.toBeNull();
      expect(toggledSeat.seatId).toBe('seat-1');
    });

    it('emits seatToggle when a seat is Alt-clicked in select mode', () => {
      fixture.componentRef.setInput('editable', true);
      fixture.componentRef.setInput('toolMode', 'select');
      fixture.detectChanges();

      let toggledSeat: any = null;
      component.seatToggle.subscribe((data) => {
        toggledSeat = data.seat;
      });

      const seatItems = fixture.nativeElement.querySelectorAll('.seat-item');
      seatItems[0].dispatchEvent(new MouseEvent('click', { bubbles: true, altKey: true }));

      expect(toggledSeat).not.toBeNull();
      expect(toggledSeat.seatId).toBe('seat-1');
    });

    it('resolves dynamic seat colors from section shapeMetadata', () => {
      const sectionWithColors: VenueSectionLayout = {
        ...mockSection,
        shapeMetadata: {
          color: '#10B981',
          seatColors: {
            '0_0': '#EC4899',
          },
        },
      };

      fixture.componentRef.setInput('section', sectionWithColors);
      fixture.detectChanges();

      expect(component.getSectionColor()).toBe('#10B981');
      expect(component.getSeatColor(sectionWithColors.seats[0])).toBe('#EC4899'); // custom seat color
      expect(component.getSeatColor(sectionWithColors.seats[1])).toBe('#10B981'); // fallback to section color
    });

    it('emits rowClick and rowDblClick when row guide badges are clicked', () => {
      fixture.componentRef.setInput('editable', true);
      fixture.componentRef.setInput('selected', true);
      fixture.detectChanges();

      let clickedRow: string | null = null;
      let dblClickedRow: string | null = null;

      component.rowClick.subscribe((data) => {
        clickedRow = data.rowLabel;
      });
      component.rowDblClick.subscribe((data) => {
        dblClickedRow = data.rowLabel;
      });

      const rowBadges = fixture.nativeElement.querySelectorAll('.row-guide-badge');
      expect(rowBadges.length).toBeGreaterThan(0);

      rowBadges[0].dispatchEvent(new MouseEvent('click', { bubbles: true }));
      expect(clickedRow as string | null).toBe('A');

      rowBadges[0].dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
      expect(dblClickedRow as string | null).toBe('A');
    });

    it('emits colClick and colDblClick when column guide badges are clicked', () => {
      fixture.componentRef.setInput('editable', true);
      fixture.componentRef.setInput('selected', true);
      fixture.detectChanges();

      let clickedCol: number | null = null;
      let dblClickedCol: number | null = null;

      component.colClick.subscribe((data) => {
        clickedCol = data.colIndex;
      });
      component.colDblClick.subscribe((data) => {
        dblClickedCol = data.colIndex;
      });

      const colBadges = fixture.nativeElement.querySelectorAll('.col-guide-badge');
      expect(colBadges.length).toBeGreaterThan(0);

      colBadges[0].dispatchEvent(new MouseEvent('click', { bubbles: true }));
      expect(clickedCol as number | null).toBe(0);

      colBadges[0].dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
      expect(dblClickedCol as number | null).toBe(0);
    });

    it('calculates color luminance and assigns seat-number-dark for light seat colors', () => {
      expect(component.isColorLight('#f59e0b')).toBeTrue(); // Amber is light
      expect(component.isColorLight('#fbbf24')).toBeTrue(); // Yellow is light
      expect(component.isColorLight('#ffffff')).toBeTrue(); // White is light
      expect(component.isColorLight('#6366f1')).toBeFalse(); // Indigo is dark
      expect(component.isColorLight('#10b981')).toBeFalse(); // Emerald is dark

      const sectionWithAmber: VenueSectionLayout = {
        ...mockSection,
        shapeMetadata: {
          color: '#f59e0b',
        },
      };
      fixture.componentRef.setInput('section', sectionWithAmber);
      fixture.detectChanges();

      const seatTexts = fixture.nativeElement.querySelectorAll('.seat-number');
      // Seat 1 is active, should have .seat-number-dark
      expect(seatTexts[0].classList.contains('seat-number-dark')).toBeTrue();
    });
  });
});
