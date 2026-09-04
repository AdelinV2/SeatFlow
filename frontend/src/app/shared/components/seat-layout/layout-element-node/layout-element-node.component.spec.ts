import { ComponentFixture, TestBed } from '@angular/core/testing';
import { LayoutElementNodeComponent } from './layout-element-node.component';
import { VenueLayoutElement } from '../../../../models/venue.model';
import { CornerHandle } from '../../../utils/layout-geometry';

describe('LayoutElementNodeComponent', () => {
  let component: LayoutElementNodeComponent;
  let fixture: ComponentFixture<LayoutElementNodeComponent>;

  const defaultStage: VenueLayoutElement = {
    elementId: 'elem-stage-1',
    type: 'STAGE',
    label: 'Main Stage',
    geometry: { x: 100, y: 50, width: 400, height: 80, rotationDeg: 0 },
    zIndex: 0,
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [LayoutElementNodeComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(LayoutElementNodeComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('element', defaultStage);
    fixture.detectChanges();
  });

  describe('Sanitized Rendering & Primitives', () => {
    it('renders rounded rect for STAGE', () => {
      const rect = fixture.nativeElement.querySelector('.stage-rect');
      expect(rect).not.toBeNull();
      expect(rect.getAttribute('rx')).toBe('10');
      expect(rect.getAttribute('ry')).toBe('10');
      expect(rect.getAttribute('width')).toBe('400');
      expect(rect.getAttribute('height')).toBe('80');

      const title = fixture.nativeElement.querySelector('.stage-title');
      expect(title).not.toBeNull();
      expect(title.textContent.trim()).toBe('Main Stage');
    });

    it('renders rounded rect for AISLE', () => {
      fixture.componentRef.setInput('element', {
        elementId: 'elem-aisle-1',
        type: 'AISLE',
        label: 'Aisle 1',
        geometry: { x: 100, y: 160, width: 300, height: 40, rotationDeg: 0 },
        zIndex: 1,
      });
      fixture.detectChanges();

      const rect = fixture.nativeElement.querySelector('.aisle-rect');
      expect(rect).not.toBeNull();
      expect(rect.getAttribute('rx')).toBe('4');
      expect(rect.getAttribute('ry')).toBe('4');

      const title = fixture.nativeElement.querySelector('.aisle-title');
      expect(title.textContent.trim()).toBe('Aisle 1');
    });

    it('renders rounded rect for LABEL', () => {
      fixture.componentRef.setInput('element', {
        elementId: 'elem-lbl-1',
        type: 'LABEL',
        label: 'VIP Section',
        geometry: { x: 100, y: 240, width: 200, height: 44, rotationDeg: 0 },
        zIndex: 2,
      });
      fixture.detectChanges();

      const rect = fixture.nativeElement.querySelector('.label-rect');
      expect(rect).not.toBeNull();
      expect(rect.getAttribute('rx')).toBe('4');

      const label = fixture.nativeElement.querySelector('.standalone-label');
      expect(label.textContent.trim()).toBe('VIP Section');
    });

    it('renders thin rect for BARRIER', () => {
      fixture.componentRef.setInput('element', {
        elementId: 'elem-bar-1',
        type: 'BARRIER',
        label: null,
        geometry: { x: 100, y: 320, width: 300, height: 20, rotationDeg: 0 },
        zIndex: 3,
      });
      fixture.detectChanges();

      const rect = fixture.nativeElement.querySelector('.barrier-rect');
      expect(rect).not.toBeNull();
      expect(rect.getAttribute('rx')).toBeNull();
      expect(rect.getAttribute('ry')).toBeNull();
    });

    it('renders rounded rect for DECORATION', () => {
      fixture.componentRef.setInput('element', {
        elementId: 'elem-dec-1',
        type: 'DECORATION',
        label: 'Fountain',
        geometry: { x: 100, y: 380, width: 100, height: 100, rotationDeg: 0 },
        zIndex: 4,
      });
      fixture.detectChanges();

      const rect = fixture.nativeElement.querySelector('.decoration-rect');
      expect(rect).not.toBeNull();
      expect(rect.getAttribute('rx')).toBe('6');

      const title = fixture.nativeElement.querySelector('.decoration-title');
      expect(title.textContent.trim()).toBe('Fountain');
    });
  });

  describe('Security & XSS Injection Proof (Task §9)', () => {
    it('escapes hostile script injection in label and produces zero script elements or raw markup', () => {
      const hostileLabel = "<script>alert('XSS')</script><img src=x onerror=alert(1)>";
      fixture.componentRef.setInput('element', {
        elementId: 'elem-sec-1',
        type: 'LABEL',
        label: hostileLabel,
        geometry: { x: 100, y: 240, width: 200, height: 44, rotationDeg: 0 },
        zIndex: 1,
      });
      fixture.detectChanges();

      const scripts = fixture.nativeElement.querySelectorAll('script');
      expect(scripts.length).toBe(0);

      const imgs = fixture.nativeElement.querySelectorAll('img');
      expect(imgs.length).toBe(0);

      const label = fixture.nativeElement.querySelector('.standalone-label');
      expect(label.textContent.trim()).toBe(hostileLabel);
    });

    it('contains no dynamic SVG path elements', () => {
      const paths = fixture.nativeElement.querySelectorAll('path');
      expect(paths.length).toBe(0);
    });
  });

  describe('Unsupported Runtime Types (Task §7 & §9)', () => {
    it('produces no editable node, no handles, and shows validation message on invalid type', () => {
      fixture.componentRef.setInput('element', {
        elementId: 'elem-bad-1',
        type: 'INVALID_TYPE' as any,
        label: 'Bad',
        geometry: { x: 50, y: 50, width: 100, height: 50, rotationDeg: 0 },
        zIndex: 0,
      });
      fixture.componentRef.setInput('selected', true);
      fixture.componentRef.setInput('editable', true);
      fixture.detectChanges();

      // No transform handles
      const handles = fixture.nativeElement.querySelectorAll('.transform-handle');
      expect(handles.length).toBe(0);

      // Shows invalid placeholder with alert
      const invalidNode = fixture.nativeElement.querySelector('.invalid-element-node');
      expect(invalidNode).not.toBeNull();
      expect(invalidNode.getAttribute('role')).toBe('alert');

      const errorMsg = fixture.nativeElement.querySelector('.element-validation-message');
      expect(errorMsg.textContent).toContain('Invalid: INVALID_TYPE');
    });
  });

  describe('Transform Handles and Interaction', () => {
    it('renders rotation handle and 4 corner handles when selected and editable', () => {
      fixture.componentRef.setInput('selected', true);
      fixture.componentRef.setInput('editable', true);
      fixture.detectChanges();

      const rotationHandle = fixture.nativeElement.querySelector('.rotation-handle');
      expect(rotationHandle).not.toBeNull();

      const cornerHandles = fixture.nativeElement.querySelectorAll('.corner-handle');
      expect(cornerHandles.length).toBe(4);
      expect(fixture.nativeElement.querySelector('.corner-handle.nw')).not.toBeNull();
      expect(fixture.nativeElement.querySelector('.corner-handle.ne')).not.toBeNull();
      expect(fixture.nativeElement.querySelector('.corner-handle.se')).not.toBeNull();
      expect(fixture.nativeElement.querySelector('.corner-handle.sw')).not.toBeNull();
    });

    it('does NOT render transform handles when not selected or not editable', () => {
      fixture.componentRef.setInput('selected', false);
      fixture.componentRef.setInput('editable', true);
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelectorAll('.transform-handle').length).toBe(0);

      fixture.componentRef.setInput('selected', true);
      fixture.componentRef.setInput('editable', false);
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelectorAll('.transform-handle').length).toBe(0);
    });

    it('emits elementPointerDown on pointerdown when editable', () => {
      let emitted: any = null;
      component.elementPointerDown.subscribe((data) => (emitted = data));

      const g = fixture.nativeElement.querySelector('.layout-element-node');
      g.dispatchEvent(
        new PointerEvent('pointerdown', { pointerId: 1, clientX: 150, clientY: 70, bubbles: true }),
      );

      expect(emitted).not.toBeNull();
      expect(emitted.element).toBe(defaultStage);
    });

    it('emits handlePointerDown when dragging corner handle', () => {
      fixture.componentRef.setInput('selected', true);
      fixture.componentRef.setInput('editable', true);
      fixture.detectChanges();

      let emittedHandle: CornerHandle | 'rotate' | null = null;
      component.handlePointerDown.subscribe((data) => (emittedHandle = data.handle));

      const seHandle = fixture.nativeElement.querySelector('.corner-handle.se');
      seHandle.dispatchEvent(
        new PointerEvent('pointerdown', {
          pointerId: 1,
          clientX: 500,
          clientY: 130,
          bubbles: true,
        }),
      );

      expect<CornerHandle | 'rotate' | null>(emittedHandle).toBe('se');
    });

    it('emits handlePointerDown when dragging rotation handle', () => {
      fixture.componentRef.setInput('selected', true);
      fixture.componentRef.setInput('editable', true);
      fixture.detectChanges();

      let emittedHandle: CornerHandle | 'rotate' | null = null;
      component.handlePointerDown.subscribe((data) => (emittedHandle = data.handle));

      const rotHandle = fixture.nativeElement.querySelector('.rotation-handle');
      rotHandle.dispatchEvent(
        new PointerEvent('pointerdown', { pointerId: 1, clientX: 300, clientY: 26, bubbles: true }),
      );

      expect<CornerHandle | 'rotate' | null>(emittedHandle).toBe('rotate');
    });
  });
});
