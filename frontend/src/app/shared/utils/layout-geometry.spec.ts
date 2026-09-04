import { VenueLayoutElement, VenueSectionLayout } from '../../models/venue.model';
import {
  calculateCornerResize,
  calculateRotation,
  clampDimension,
  clampNumber,
  clampSectionTransform,
  clampZoom,
  clientDeltaToWorld,
  clientPointToWorld,
  CornerHandle,
  DEFAULT_LAYOUT_BOUNDS,
  layoutBounds,
  MAX_DIMENSION,
  MAX_POSITION,
  MAX_ROTATION,
  MAX_Z_INDEX,
  MAX_ZOOM,
  MIN_DIMENSION,
  MIN_POSITION,
  MIN_ROTATION,
  MIN_Z_INDEX,
  MIN_ZOOM,
  normalizeRotation,
  Point,
  SectionTransform,
  sectionLocalToWorld,
  snap,
  sortedLayoutItems,
} from './layout-geometry';

describe('layout-geometry pure utilities', () => {
  describe('clientPointToWorld and clientDeltaToWorld (Zoom coordinate drift risk)', () => {
    it('computes exact same world delta at zoom 0.5, 1.0, and 2.0', () => {
      const deltaAtHalfZoom = clientDeltaToWorld({ x: 50, y: 30 }, 0.5);
      const deltaAtOneZoom = clientDeltaToWorld({ x: 100, y: 60 }, 1.0);
      const deltaAtDoubleZoom = clientDeltaToWorld({ x: 200, y: 120 }, 2.0);

      expect(deltaAtHalfZoom).toEqual({ x: 100, y: 60 });
      expect(deltaAtOneZoom).toEqual({ x: 100, y: 60 });
      expect(deltaAtDoubleZoom).toEqual({ x: 100, y: 60 });
    });

    it('converts client point to world coordinate taking container origin, pan, and zoom into account', () => {
      const origin = { left: 50, top: 40 };
      const pan = { x: 100, y: 60 };
      const zoom = 2.0;

      // World point = ((client - origin) - pan) / zoom
      // client = (350, 260) -> client - origin = (300, 220) -> minus pan = (200, 160) -> / 2 = (100, 80)
      const worldPoint = clientPointToWorld({ x: 350, y: 260 }, origin, pan, zoom);
      expect(worldPoint).toEqual({ x: 100, y: 80 });
    });

    it('falls back to zoom 1.0 when zoom is 0, negative, or NaN', () => {
      const origin = { left: 0, top: 0 };
      const pan = { x: 0, y: 0 };

      expect(clientPointToWorld({ x: 150, y: 200 }, origin, pan, 0)).toEqual({ x: 150, y: 200 });
      expect(clientPointToWorld({ x: 150, y: 200 }, origin, pan, -2)).toEqual({ x: 150, y: 200 });
      expect(clientPointToWorld({ x: 150, y: 200 }, origin, pan, NaN)).toEqual({ x: 150, y: 200 });
      expect(clientDeltaToWorld({ x: 80, y: 40 }, 0)).toEqual({ x: 80, y: 40 });
    });
  });

  describe('sectionLocalToWorld', () => {
    it('maps local points directly in unrotated section', () => {
      const section: SectionTransform = {
        positionX: 100,
        positionY: 200,
        width: 300,
        height: 150,
        rotationDeg: 0,
      };
      expect(sectionLocalToWorld({ x: 0, y: 0 }, section)).toEqual({ x: 100, y: 200 });
      expect(sectionLocalToWorld({ x: 300, y: 150 }, section)).toEqual({ x: 400, y: 350 });
      expect(sectionLocalToWorld({ x: 50, y: 75 }, section)).toEqual({ x: 150, y: 275 });
    });

    it('maps local points correctly in 90-degree rotated section around center', () => {
      const section: SectionTransform = {
        positionX: 100,
        positionY: 100,
        width: 200,
        height: 100,
        rotationDeg: 90,
      };
      // Center is (200, 150)
      // Local (200, 100) (SE): delta from center is (100, 50). Rotated by 90 deg: (-50, 100).
      // World point: center + (-50, 100) = (150, 250).
      expect(sectionLocalToWorld({ x: 200, y: 100 }, section)).toEqual({ x: 150, y: 250 });
      // Local (0, 0) (NW): delta from center is (-100, -50). Rotated by 90 deg: (50, -100).
      // World point: center + (50, -100) = (250, 50).
      expect(sectionLocalToWorld({ x: 0, y: 0 }, section)).toEqual({ x: 250, y: 50 });
      // Local (0, 100) (SW): delta (-100, 50). Rotated: (-50, -100).
      // World point: (150, 50).
      expect(sectionLocalToWorld({ x: 0, y: 100 }, section)).toEqual({ x: 150, y: 50 });
      // Local (200, 0) (NE): delta (100, -50). Rotated: (50, 100).
      // World point: (250, 250).
      expect(sectionLocalToWorld({ x: 200, y: 0 }, section)).toEqual({ x: 250, y: 250 });
    });
  });

  describe('snap', () => {
    it('preserves exact decimal values when step is 0 or negative', () => {
      expect(snap(12.3456, 0)).toBe(12.3456);
      expect(snap(45.678, -5)).toBe(45.678);
      expect(snap(99.999, -1)).toBe(99.999);
    });

    it('rounds values to nearest positive step', () => {
      expect(snap(23, 10)).toBe(20);
      expect(snap(25, 10)).toBe(30);
      expect(snap(27, 10)).toBe(30);
      expect(snap(12.4, 5)).toBe(10);
      expect(snap(12.6, 5)).toBe(15);
      expect(snap(0.34, 0.1)).toBe(0.3);
      expect(snap(0.36, 0.1)).toBe(0.4);
    });

    it('returns 0 for non-finite values', () => {
      expect(snap(NaN, 10)).toBe(0);
      expect(snap(Infinity, 10)).toBe(0);
    });
  });

  describe('normalizeRotation', () => {
    it('preserves valid boundaries -180 and 180 degrees', () => {
      expect(normalizeRotation(-180)).toBe(-180);
      expect(normalizeRotation(180)).toBe(180);
      expect(normalizeRotation(0)).toBe(0);
    });

    it('normalizes angles continuously when crossing 180 degrees', () => {
      expect(normalizeRotation(181)).toBe(-179);
      expect(normalizeRotation(190)).toBe(-170);
      expect(normalizeRotation(270)).toBe(-90);
      expect(normalizeRotation(360)).toBe(0);
      expect(normalizeRotation(540)).toBe(180);
      expect(normalizeRotation(541)).toBe(-179);
    });

    it('normalizes negative angles continuously when crossing -180 degrees', () => {
      expect(normalizeRotation(-181)).toBe(179);
      expect(normalizeRotation(-190)).toBe(170);
      expect(normalizeRotation(-270)).toBe(90);
      expect(normalizeRotation(-360)).toBe(0);
      expect(normalizeRotation(-540)).toBe(-180);
      expect(normalizeRotation(-541)).toBe(179);
    });

    it('returns 0 for non-finite values', () => {
      expect(normalizeRotation(NaN)).toBe(0);
      expect(normalizeRotation(Infinity)).toBe(0);
    });
  });

  describe('clampSectionTransform and bounds (TASK-P11-003 parity)', () => {
    it('covers every inclusive boundary for position, dimension, rotation, and zIndex', () => {
      // Lower boundaries
      const atMinBounds: SectionTransform = {
        positionX: MIN_POSITION,
        positionY: MIN_POSITION,
        width: MIN_DIMENSION,
        height: MIN_DIMENSION,
        rotationDeg: MIN_ROTATION,
        zIndex: MIN_Z_INDEX,
      };
      expect(clampSectionTransform(atMinBounds)).toEqual(atMinBounds);

      // Upper boundaries
      const atMaxBounds: SectionTransform = {
        positionX: MAX_POSITION,
        positionY: MAX_POSITION,
        width: MAX_DIMENSION,
        height: MAX_DIMENSION,
        rotationDeg: MAX_ROTATION,
        zIndex: MAX_Z_INDEX,
      };
      expect(clampSectionTransform(atMaxBounds)).toEqual(atMaxBounds);
    });

    it('clamps values that exceed boundaries', () => {
      const outOfBounds: SectionTransform = {
        positionX: -10,
        positionY: 100050,
        width: 0,
        height: 150000,
        rotationDeg: 200,
        zIndex: 1500,
      };

      const clamped = clampSectionTransform(outOfBounds);
      expect(clamped.positionX).toBe(MIN_POSITION);
      expect(clamped.positionY).toBe(MAX_POSITION);
      expect(clamped.width).toBe(MIN_DIMENSION);
      expect(clamped.height).toBe(MAX_DIMENSION);
      expect(clamped.rotationDeg).toBe(-160); // 200 normalized to -160, within [-180, 180]
      expect(clamped.zIndex).toBe(MAX_Z_INDEX);
    });

    it('preserves exact positive decimal dimensions 0.001 and 0.5 without coercing to 1 (REV-002)', () => {
      const transformWithDecimals: SectionTransform = {
        positionX: 50,
        positionY: 50,
        width: 0.001,
        height: 0.5,
        rotationDeg: 0,
        zIndex: 0,
      };
      const clamped = clampSectionTransform(transformWithDecimals);
      expect(clamped.width).toBe(0.001);
      expect(clamped.height).toBe(0.5);
    });

    it('clamps zero and negative dimensions strictly to MIN_DIMENSION (0.001) (REV-002)', () => {
      const zeroDims: SectionTransform = {
        positionX: 10,
        positionY: 10,
        width: 0,
        height: -5,
        rotationDeg: 0,
      };
      const clamped = clampSectionTransform(zeroDims);
      expect(clamped.width).toBe(0.001);
      expect(clamped.height).toBe(0.001);
    });

    it('preserves exact upper bound 100000 and clamps dimensions exceeding 100000', () => {
      const maxDims: SectionTransform = {
        positionX: 0,
        positionY: 0,
        width: 100000,
        height: 100000,
        rotationDeg: 0,
      };
      expect(clampSectionTransform(maxDims).width).toBe(100000);
      expect(clampSectionTransform(maxDims).height).toBe(100000);

      const exceededDims: SectionTransform = {
        positionX: 0,
        positionY: 0,
        width: 100001,
        height: 150000,
        rotationDeg: 0,
      };
      expect(clampSectionTransform(exceededDims).width).toBe(100000);
      expect(clampSectionTransform(exceededDims).height).toBe(100000);
    });

    it('clamps negative zIndex below -1000 to -1000', () => {
      const transform: SectionTransform = {
        positionX: 100,
        positionY: 100,
        width: 200,
        height: 200,
        rotationDeg: 0,
        zIndex: -1500,
      };
      expect(clampSectionTransform(transform).zIndex).toBe(MIN_Z_INDEX);
    });

    it('omits zIndex when undefined in source transform', () => {
      const transform: SectionTransform = {
        positionX: 50,
        positionY: 50,
        width: 100,
        height: 100,
        rotationDeg: 45,
      };
      const clamped = clampSectionTransform(transform);
      expect(clamped.zIndex).toBeUndefined();
    });

    it('handles NaN/infinite numbers safely with fallbacks', () => {
      const nanTransform: SectionTransform = {
        positionX: NaN,
        positionY: Infinity,
        width: NaN,
        height: -50,
        rotationDeg: NaN,
        zIndex: NaN,
      };
      const clamped = clampSectionTransform(nanTransform);
      expect(clamped.positionX).toBe(MIN_POSITION);
      expect(clamped.positionY).toBe(MIN_POSITION);
      expect(clamped.width).toBe(MIN_DIMENSION);
      expect(clamped.height).toBe(MIN_DIMENSION);
      expect(clamped.rotationDeg).toBe(0);
      expect(clamped.zIndex).toBe(0);
    });
  });

  describe('clampZoom', () => {
    it('clamps zoom between MIN_ZOOM (0.25) and MAX_ZOOM (4.0)', () => {
      expect(clampZoom(0.1)).toBe(MIN_ZOOM);
      expect(clampZoom(0.25)).toBe(0.25);
      expect(clampZoom(1.5)).toBe(1.5);
      expect(clampZoom(4.0)).toBe(4.0);
      expect(clampZoom(5.5)).toBe(MAX_ZOOM);
    });

    it('falls back to 1.0 on invalid or non-positive zoom', () => {
      expect(clampZoom(0)).toBe(1.0);
      expect(clampZoom(-1)).toBe(1.0);
      expect(clampZoom(NaN)).toBe(1.0);
      expect(clampZoom(Infinity)).toBe(1.0);
    });
  });

  describe('layoutBounds', () => {
    it('returns DEFAULT_LAYOUT_BOUNDS when layout is completely empty', () => {
      const bounds = layoutBounds([], []);
      expect(bounds).toEqual(DEFAULT_LAYOUT_BOUNDS);
    });

    it('computes exact axis-aligned bounds for unrotated sections and elements', () => {
      const sections: VenueSectionLayout[] = [
        {
          sectionId: 'sec-1',
          name: 'Orchestra',
          rowCount: 2,
          colCount: 2,
          isActive: true,
          positionX: 100,
          positionY: 200,
          width: 300,
          height: 150,
          rotationDeg: 0,
          zIndex: 0,
          shapeMetadata: null,
          seats: [],
        },
      ];

      const elements: VenueLayoutElement[] = [
        {
          elementId: 'elem-1',
          type: 'STAGE',
          label: 'Stage',
          geometry: { x: 50, y: 50, width: 400, height: 80, rotationDeg: 0 },
          zIndex: 1,
        },
      ];

      const bounds = layoutBounds(sections, elements);
      expect(bounds.minX).toBe(50);
      expect(bounds.minY).toBe(50);
      expect(bounds.maxX).toBe(450); // element extends to 50 + 400 = 450
      expect(bounds.maxY).toBe(350); // section extends to 200 + 150 = 350
      expect(bounds.width).toBe(400);
      expect(bounds.height).toBe(300);
    });

    it('computes rotated bounds accurately for rotated sections', () => {
      // 200x100 box at (100, 100), rotated 90 degrees around center (200, 150)
      // When rotated 90 deg, width becomes 100 (from 150 to 250) and height becomes 200 (from 50 to 250)
      const sections: VenueSectionLayout[] = [
        {
          sectionId: 'sec-rot',
          name: 'Rotated Block',
          rowCount: 1,
          colCount: 1,
          isActive: true,
          positionX: 100,
          positionY: 100,
          width: 200,
          height: 100,
          rotationDeg: 90,
          zIndex: 0,
          shapeMetadata: null,
          seats: [],
        },
      ];

      const bounds = layoutBounds(sections);
      expect(bounds.minX).toBe(150);
      expect(bounds.maxX).toBe(250);
      expect(bounds.minY).toBe(50);
      expect(bounds.maxY).toBe(250);
      expect(bounds.width).toBe(100);
      expect(bounds.height).toBe(200);
    });
  });

  describe('sortedLayoutItems (Rendering order and stability risk)', () => {
    it('sorts generic items ascending by zIndex', () => {
      const items = [
        { id: 'c', zIndex: 10 },
        { id: 'a', zIndex: -5 },
        { id: 'b', zIndex: 0 },
      ];
      const sorted = sortedLayoutItems(items);
      expect(sorted.map((i) => i.id)).toEqual(['a', 'b', 'c']);
    });

    it('maintains deterministic stable tie-break order for equal z-index items', () => {
      const items = [
        { id: 'beta', zIndex: 0 },
        { id: 'alpha', zIndex: 0 },
        { id: 'gamma', zIndex: 0 },
      ];
      const sorted = sortedLayoutItems(items);
      expect(sorted.map((i) => i.id)).toEqual(['alpha', 'beta', 'gamma']);
    });

    it('preserves array index order when ids are missing on equal z-index items', () => {
      const items = [
        { name: 'first', zIndex: 1 },
        { name: 'second', zIndex: 1 },
        { name: 'third', zIndex: 1 },
      ];
      const sorted = sortedLayoutItems(items);
      expect(sorted.map((i) => i.name)).toEqual(['first', 'second', 'third']);
    });

    it('combines and sorts sections and layout elements by zIndex with stable keys', () => {
      const sections: VenueSectionLayout[] = [
        {
          sectionId: 'sec-2',
          name: 'Balcony',
          rowCount: 1,
          colCount: 1,
          isActive: true,
          positionX: 0,
          positionY: 0,
          width: 100,
          height: 100,
          rotationDeg: 0,
          zIndex: 5,
          shapeMetadata: null,
          seats: [],
        },
        {
          sectionId: 'sec-1',
          name: 'Orchestra',
          rowCount: 1,
          colCount: 1,
          isActive: true,
          positionX: 0,
          positionY: 0,
          width: 100,
          height: 100,
          rotationDeg: 0,
          zIndex: 0,
          shapeMetadata: null,
          seats: [],
        },
      ];

      const elements: VenueLayoutElement[] = [
        {
          elementId: 'elem-stage',
          type: 'STAGE',
          label: 'Stage',
          geometry: { x: 0, y: 0, width: 200, height: 50, rotationDeg: 0 },
          zIndex: -1,
        },
        {
          elementId: 'elem-aisle',
          type: 'AISLE',
          label: 'Main Aisle',
          geometry: { x: 0, y: 0, width: 50, height: 200, rotationDeg: 0 },
          zIndex: 0,
        },
      ];

      const sorted = sortedLayoutItems(sections, elements);
      expect(sorted.length).toBe(4);
      // Expected z order:
      // 1. STAGE (zIndex -1)
      // 2. AISLE or Orchestra (zIndex 0, tie break by stableKey)
      // 3. Orchestra or AISLE (zIndex 0)
      // 4. Balcony (zIndex 5)
      expect(sorted[0].kind).toBe('element');
      expect((sorted[0].data as VenueLayoutElement).type).toBe('STAGE');

      expect(sorted[3].kind).toBe('section');
      expect((sorted[3].data as VenueSectionLayout).name).toBe('Balcony');
    });
  });

  describe('calculateCornerResize', () => {
    const initial: SectionTransform = {
      positionX: 100,
      positionY: 100,
      width: 200,
      height: 150,
      rotationDeg: 0,
      zIndex: 0,
    };

    it('resizes south-east handle without changing position', () => {
      const result = calculateCornerResize(initial, 'se', { x: 50, y: 30 });
      expect(result.positionX).toBe(100);
      expect(result.positionY).toBe(100);
      expect(result.width).toBe(250);
      expect(result.height).toBe(180);
    });

    it('resizes north-west handle and shifts position to anchor opposite corner', () => {
      const result = calculateCornerResize(initial, 'nw', { x: 40, y: 20 });
      // Width decreases by 40 -> 160; height decreases by 20 -> 130
      // Position shifts right and down: x = 100 + 40 = 140; y = 100 + 20 = 120
      expect(result.positionX).toBe(140);
      expect(result.positionY).toBe(120);
      expect(result.width).toBe(160);
      expect(result.height).toBe(130);
    });

    it('clamps minimum dimensions so width and height remain strictly > 0', () => {
      const result = calculateCornerResize(initial, 'se', { x: -300, y: -300 });
      expect(result.width).toBe(MIN_DIMENSION);
      expect(result.height).toBe(MIN_DIMENSION);
    });

    it('applies snapStep to resized dimensions', () => {
      const result = calculateCornerResize(initial, 'se', { x: 23, y: 17 }, 10);
      // width = 200 + 23 = 223 -> snap 10 = 220
      // height = 150 + 17 = 167 -> snap 10 = 170
      expect(result.width).toBe(220);
      expect(result.height).toBe(170);
    });

    it('respects rotation during corner resize', () => {
      const rotated: SectionTransform = {
        positionX: 100,
        positionY: 100,
        width: 200,
        height: 100,
        rotationDeg: 90,
      };
      // At 90 deg rotation, a world delta in +Y aligns with local +X
      const result = calculateCornerResize(rotated, 'se', { x: 0, y: 50 });
      expect(result.width).toBe(250);
      expect(result.height).toBe(100);
      // NW corner at (0, 0) remains fixed at world point (250, 50)
      expect(result.positionX).toBe(75);
      expect(result.positionY).toBe(125);
    });

    describe('rotated corner resize opposite corner world preservation (REV-001)', () => {
      const rotated90: SectionTransform = {
        positionX: 100,
        positionY: 100,
        width: 200,
        height: 100,
        rotationDeg: 90,
      };

      it('preserves opposite SE world corner when dragging NW handle at 90 degrees', () => {
        const oldSEWorld = sectionLocalToWorld({ x: 200, y: 100 }, rotated90);
        expect(oldSEWorld).toEqual({ x: 150, y: 250 });

        // Drag NW handle by worldDelta {-20, 40}, which corresponds to local {40, 20}
        const result = calculateCornerResize(rotated90, 'nw', { x: -20, y: 40 });
        expect(result.positionX).toBe(110);
        expect(result.positionY).toBe(130);
        expect(result.width).toBe(160);
        expect(result.height).toBe(80);

        const newSection: SectionTransform = { ...rotated90, ...result };
        const newSEWorld = sectionLocalToWorld({ x: result.width, y: result.height }, newSection);
        expect(newSEWorld).toEqual(oldSEWorld);
      });

      it('preserves opposite SW world corner when dragging NE handle at 90 degrees', () => {
        const oldSWWorld = sectionLocalToWorld({ x: 0, y: 100 }, rotated90);
        expect(oldSWWorld).toEqual({ x: 150, y: 50 });

        // At 90 deg: localDx = worldDelta.y, localDy = -worldDelta.x
        // To change width by +40 (localDx = 40) and height by -20 (localDy = -20):
        // worldDelta.y = 40, -worldDelta.x = -20 => worldDelta.x = 20
        const result = calculateCornerResize(rotated90, 'ne', { x: 20, y: 40 });
        expect(result.width).toBe(240);
        expect(result.height).toBe(120);

        const newSection: SectionTransform = { ...rotated90, ...result };
        const newSWWorld = sectionLocalToWorld({ x: 0, y: result.height }, newSection);
        expect(newSWWorld).toEqual(oldSWWorld);
      });

      it('preserves opposite NE world corner when dragging SW handle at 90 degrees', () => {
        const oldNEWorld = sectionLocalToWorld({ x: 200, y: 0 }, rotated90);
        expect(oldNEWorld).toEqual({ x: 250, y: 250 });

        // SW handle: localDx = worldDelta.y, localDy = -worldDelta.x
        // To change width by -20 (localDx = 20) and height by +20 (localDy = 20):
        // worldDelta.y = 20, -worldDelta.x = 20 => worldDelta.x = -20
        const result = calculateCornerResize(rotated90, 'sw', { x: -20, y: 20 });
        expect(result.width).toBe(180);
        expect(result.height).toBe(120);

        const newSection: SectionTransform = { ...rotated90, ...result };
        const newNEWorld = sectionLocalToWorld({ x: result.width, y: 0 }, newSection);
        expect(newNEWorld).toEqual(oldNEWorld);
      });

      it('preserves opposite NW world corner when dragging SE handle at 90 degrees', () => {
        const oldNWWorld = sectionLocalToWorld({ x: 0, y: 0 }, rotated90);
        expect(oldNWWorld).toEqual({ x: 250, y: 50 });

        // At 90 deg: localDx = worldDelta.y, localDy = -worldDelta.x
        // World delta { x: 0, y: 50 } -> localDx = 50, localDy = 0
        const result = calculateCornerResize(rotated90, 'se', { x: 0, y: 50 });
        expect(result.width).toBe(250);
        expect(result.height).toBe(100);

        const newSection: SectionTransform = { ...rotated90, ...result };
        const newNWWorld = sectionLocalToWorld({ x: 0, y: 0 }, newSection);
        expect(newNWWorld).toEqual(oldNWWorld);
      });

      it('preserves opposite world corner for arbitrary non-orthogonal rotation (45 degrees)', () => {
        const rotated45: SectionTransform = {
          positionX: 200,
          positionY: 150,
          width: 120,
          height: 80,
          rotationDeg: 45,
        };

        const handles: CornerHandle[] = ['nw', 'ne', 'sw', 'se'];
        for (const h of handles) {
          const result = calculateCornerResize(rotated45, h, { x: 15, y: -10 });
          const newSection: SectionTransform = { ...rotated45, ...result };

          let oldOppositeLocal: Point;
          let newOppositeLocal: Point;
          switch (h) {
            case 'se':
              oldOppositeLocal = { x: 0, y: 0 };
              newOppositeLocal = { x: 0, y: 0 };
              break;
            case 'sw':
              oldOppositeLocal = { x: rotated45.width, y: 0 };
              newOppositeLocal = { x: result.width, y: 0 };
              break;
            case 'ne':
              oldOppositeLocal = { x: 0, y: rotated45.height };
              newOppositeLocal = { x: 0, y: result.height };
              break;
            case 'nw':
              oldOppositeLocal = { x: rotated45.width, y: rotated45.height };
              newOppositeLocal = { x: result.width, y: result.height };
              break;
          }

          const oldWorld = sectionLocalToWorld(oldOppositeLocal, rotated45);
          const newWorld = sectionLocalToWorld(newOppositeLocal, newSection);

          expect(Math.abs(newWorld.x - oldWorld.x)).toBeLessThanOrEqual(0.005);
          expect(Math.abs(newWorld.y - oldWorld.y)).toBeLessThanOrEqual(0.005);
        }
      });
    });

    describe('dimension boundaries across all corner handles (REV-002)', () => {
      const handles: CornerHandle[] = ['nw', 'ne', 'se', 'sw'];

      it('clamps to MIN_DIMENSION (0.001) when dragged past zero for all handles', () => {
        for (const h of handles) {
          const delta =
            h === 'se'
              ? { x: -500, y: -500 }
              : h === 'nw'
                ? { x: 500, y: 500 }
                : h === 'ne'
                  ? { x: -500, y: 500 }
                  : { x: 500, y: -500 };
          const result = calculateCornerResize(initial, h, delta);
          expect(result.width).toBe(MIN_DIMENSION);
          expect(result.height).toBe(MIN_DIMENSION);
        }
      });

      it('preserves initial dimensions 0.001 and 0.5 without coercing to 1 for all handles', () => {
        const smallInitial: SectionTransform = {
          positionX: 50,
          positionY: 50,
          width: 0.001,
          height: 0.5,
          rotationDeg: 0,
        };

        for (const h of handles) {
          const result = calculateCornerResize(smallInitial, h, { x: 0, y: 0 });
          expect(result.width).toBe(0.001);
          expect(result.height).toBe(0.5);
        }
      });

      it('preserves upper bound 100000 and clamps excessive dimensions for all handles', () => {
        const maxInitial: SectionTransform = {
          positionX: 0,
          positionY: 0,
          width: 100000,
          height: 100000,
          rotationDeg: 0,
        };

        for (const h of handles) {
          const preserved = calculateCornerResize(maxInitial, h, { x: 0, y: 0 });
          expect(preserved.width).toBe(100000);
          expect(preserved.height).toBe(100000);

          const delta =
            h === 'se'
              ? { x: 500, y: 500 }
              : h === 'nw'
                ? { x: -500, y: -500 }
                : h === 'ne'
                  ? { x: 500, y: -500 }
                  : { x: -500, y: 500 };
          const clamped = calculateCornerResize(maxInitial, h, delta);
          expect(clamped.width).toBe(100000);
          expect(clamped.height).toBe(100000);
        }
      });
    });
  });

  describe('calculateRotation', () => {
    const section: SectionTransform = {
      positionX: 100,
      positionY: 100,
      width: 200,
      height: 200,
      rotationDeg: 0,
    };
    // Center is (200, 200)

    it('calculates 0 deg when cursor is straight up from center', () => {
      expect(calculateRotation(section, { x: 200, y: 50 })).toBe(0);
    });

    it('calculates 90 deg when cursor is to the right of center', () => {
      expect(calculateRotation(section, { x: 350, y: 200 })).toBe(90);
    });

    it('calculates 180 deg when cursor is straight down from center', () => {
      expect(calculateRotation(section, { x: 200, y: 350 })).toBe(180);
    });

    it('calculates -90 deg when cursor is to the left of center', () => {
      expect(calculateRotation(section, { x: 50, y: 200 })).toBe(-90);
    });

    it('applies snapStep to calculated rotation', () => {
      // 82 degrees with snapStep 15 snaps to 75 or 90 -> 82/15 = 5.466 -> 5 * 15 = 75
      // Let's test with a point roughly at 82 deg
      const rot = calculateRotation(section, { x: 340, y: 180 }, 15);
      expect(rot % 15).toBe(0);
    });
  });
});
