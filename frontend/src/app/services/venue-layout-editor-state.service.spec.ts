import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import {
  SaveVenueLayoutRequest,
  VenueLayout,
  VenueLayoutElement,
  VenueSectionLayout,
  VenueSectionSeat,
} from '../models/venue.model';
import { AdminVenueApiService } from './admin-venue-api.service';
import { VenueLayoutEditorStateService } from './venue-layout-editor-state.service';

// Compile-time contract: partial Phase-11 records must not type-check.
// Each declaration below must fail compilation if optional fields or `any` IDs return.
// @ts-expect-error - seat position geometry is required
const _badSeat: VenueSectionSeat = {
  seatId: 's-1',
  rowLabel: 'A',
  seatNumber: 1,
  gridX: 0,
  gridY: 0,
  isActive: true,
};
// @ts-expect-error - section geometry/activity fields are required
const _badSection: VenueSectionLayout = {
  sectionId: 'sec-1',
  name: 'Orchestra',
  rowCount: 1,
  colCount: 1,
  seats: [],
};
// @ts-expect-error - layout version, totals and elements are required
const _badLayout: VenueLayout = {
  venueId: 'v-1',
  name: 'Grand Theatre',
  capacity: 100,
  sections: [],
};
// @ts-expect-error - element type and geometry are required
const _badElement: VenueLayoutElement = {
  elementId: 'el-1',
  label: null,
  zIndex: 0,
};

describe('VenueLayoutEditorStateService', () => {
  let service: VenueLayoutEditorStateService;
  let apiSpy: jasmine.SpyObj<AdminVenueApiService>;

  const createMockLayout = (): VenueLayout => ({
    venueId: 'ven-123',
    name: 'Symphony Hall',
    capacity: 2500,
    totalConfiguredSeats: 3,
    layoutVersion: 7,
    elements: [
      {
        elementId: 'el-stage-1',
        type: 'STAGE',
        label: 'Main Stage',
        geometry: {
          x: 100.5,
          y: 25.25,
          width: 800.75,
          height: 200,
          rotationDeg: -15.5,
        },
        zIndex: 10,
      },
      {
        elementId: null, // new client-side element
        type: 'AISLE',
        label: null,
        geometry: { x: 500, y: 300, width: 50, height: 400, rotationDeg: 0 },
        zIndex: -5,
      },
    ],
    sections: [
      {
        sectionId: 'sec-orch-1',
        name: 'Orchestra',
        rowCount: 2,
        colCount: 2,
        isActive: true,
        positionX: 120.125,
        positionY: 250.875,
        width: 500,
        height: 300,
        rotationDeg: 5.5,
        zIndex: 2,
        shapeMetadata: { stroke: '#fff', fill: '#333' },
        seats: [
          {
            seatId: 'seat-101',
            rowLabel: 'A',
            seatNumber: 1,
            gridX: 0,
            gridY: 0,
            positionX: 10.5,
            positionY: 15.75,
            isActive: true,
          },
          {
            seatId: 'seat-102',
            rowLabel: 'A',
            seatNumber: 2,
            gridX: 1,
            gridY: 0,
            positionX: 50.5,
            positionY: 15.75,
            isActive: false, // inactive seat
          },
          {
            seatId: null, // new client-side seat
            rowLabel: 'B',
            seatNumber: 1,
            gridX: 0,
            gridY: 1,
            positionX: 10.5,
            positionY: 55.75,
            isActive: true,
          },
        ],
      },
      {
        sectionId: null, // new client-side section
        name: 'Balcony',
        rowCount: 1,
        colCount: 1,
        isActive: true,
        positionX: 120,
        positionY: 600,
        width: 500,
        height: 150,
        rotationDeg: -10,
        zIndex: 3,
        shapeMetadata: null,
        seats: [],
      },
    ],
  });

  beforeEach(() => {
    apiSpy = jasmine.createSpyObj<AdminVenueApiService>('AdminVenueApiService', [
      'getEditableLayout',
      'validateLayout',
      'saveLayout',
    ]);

    TestBed.configureTestingModule({
      providers: [
        VenueLayoutEditorStateService,
        { provide: AdminVenueApiService, useValue: apiSpy },
      ],
    });

    service = TestBed.inject(VenueLayoutEditorStateService);
  });

  it('should initialize with null layout, baseline, loadError and false isDirty, isSaving', () => {
    expect(service.layout()).toBeNull();
    expect(service.baseline()).toBeNull();
    expect(service.loadError()).toBeNull();
    expect(service.isDirty()).toBeFalse();
    expect(service.isSaving()).toBeFalse();
  });

  it('should load layout and initialize baseline and draft with deep copies', () => {
    const mockData = createMockLayout();
    apiSpy.getEditableLayout.and.returnValue(of(mockData));

    service.load('ven-123').subscribe();

    expect(apiSpy.getEditableLayout).toHaveBeenCalledWith('ven-123');
    expect(service.layout()).toEqual(mockData);
    expect(service.baseline()).toEqual(mockData);
    expect(service.isDirty()).toBeFalse();
    expect(service.loadError()).toBeNull();
  });

  it('should prove fixture mutation after load does not mutate stored baseline or draft by reference', () => {
    const mockData = createMockLayout();
    apiSpy.getEditableLayout.and.returnValue(of(mockData));

    service.load('ven-123').subscribe();

    // Mutate the original fixture
    mockData.name = 'Hacked Venue Name';
    mockData.sections[0].name = 'Hacked Section Name';
    mockData.sections[0].seats[0].rowLabel = 'Z';
    mockData.elements[0].geometry.x = 9999;

    expect(service.baseline()?.name).toBe('Symphony Hall');
    expect(service.baseline()?.sections[0].name).toBe('Orchestra');
    expect(service.baseline()?.sections[0].seats[0].rowLabel).toBe('A');
    expect(service.baseline()?.elements[0].geometry.x).toBe(100.5);

    expect(service.layout()?.name).toBe('Symphony Hall');
    expect(service.layout()?.sections[0].name).toBe('Orchestra');
    expect(service.layout()?.sections[0].seats[0].rowLabel).toBe('A');
    expect(service.layout()?.elements[0].geometry.x).toBe(100.5);
  });

  it('should clear stale baseline and layout if load fails, and populate loadError', () => {
    // First load succeeds
    const initialData = createMockLayout();
    service.applyServerSnapshot(initialData);
    expect(service.baseline()).not.toBeNull();

    // Second load fails
    apiSpy.getEditableLayout.and.returnValue(
      throwError(() => ({ error: { message: 'Venue not found' } })),
    );

    service.load('ven-nonexistent').subscribe({
      error: () => {
        // Expected
      },
    });

    expect(service.baseline()).toBeNull();
    expect(service.layout()).toBeNull();
    expect(service.loadError()).toBe('Venue not found');
    expect(service.isDirty()).toBeFalse();
  });

  it('should compute isDirty=false initially, true after draft changes, and false when reverted', () => {
    service.applyServerSnapshot(createMockLayout());
    expect(service.isDirty()).toBeFalse();

    service.replaceDraft((current) => {
      current.sections[0].seats[0].positionX = 99.9;
      return current;
    });
    expect(service.isDirty()).toBeTrue();

    service.replaceDraft((current) => {
      current.sections[0].seats[0].positionX = 10.5;
      return current;
    });
    expect(service.isDirty()).toBeFalse();
  });

  it('should not mark dirty when object keys in shapeMetadata are reordered', () => {
    const layout = createMockLayout();
    layout.sections[0].shapeMetadata = { fill: '#333', stroke: '#fff' };
    service.applyServerSnapshot(layout);
    expect(service.isDirty()).toBeFalse();

    service.replaceDraft((current) => {
      current.sections[0].shapeMetadata = { stroke: '#fff', fill: '#333' };
      return current;
    });
    expect(service.isDirty()).toBeFalse();
  });

  it('should not mark dirty when transient UI properties (pan, zoom, selection) are added to draft', () => {
    service.applyServerSnapshot(createMockLayout());
    expect(service.isDirty()).toBeFalse();

    const editable = JSON.parse(JSON.stringify(service.layout())) as Record<string, unknown>;
    editable['zoom'] = 2.5;
    editable['pan'] = { x: 100, y: 200 };
    (editable['sections'] as Record<string, unknown>[])[0]['selected'] = true;
    (editable['sections'] as Record<string, unknown>[])[0]['isDragging'] = true;
    (
      (
        (editable['sections'] as Record<string, unknown>[])[0]['seats'] as Record<string, unknown>[]
      )[0] as Record<string, unknown>
    )['hovered'] = true;
    (editable['elements'] as Record<string, unknown>[])[0]['isSelected'] = true;

    service.replaceDraft(editable as unknown as VenueLayout);
    expect(service.isDirty()).toBeFalse();
  });

  it('should reset draft to baseline and reset isDirty to false on resetToBaseline()', () => {
    service.applyServerSnapshot(createMockLayout());
    service.replaceDraft((current) => {
      current.sections[0].positionX = 888;
      current.elements[0].zIndex = -99;
      return current;
    });
    expect(service.isDirty()).toBeTrue();

    service.resetToBaseline();

    expect(service.isDirty()).toBeFalse();
    expect(service.layout()?.sections[0].positionX).toBe(120.125);
    expect(service.layout()?.elements[0].zIndex).toBe(10);
  });

  it('should build save request preserving stable UUIDs and keeping null IDs for new records', () => {
    service.applyServerSnapshot(createMockLayout());

    const req = service.buildSaveRequest();

    expect(req.layoutVersion).toBe(7);
    expect(req.sections.length).toBe(2);

    // Section 1: existing section with stable UUID
    expect(req.sections[0].sectionId).toBe('sec-orch-1');
    expect(req.sections[0].seats[0].seatId).toBe('seat-101');
    expect(req.sections[0].seats[1].seatId).toBe('seat-102');
    expect(req.sections[0].seats[1].isActive).toBeFalse();

    // Section 1: seat 3 is new client-side seat with null ID
    expect(req.sections[0].seats[2].seatId).toBeNull();
    expect(req.sections[0].seats[2].rowLabel).toBe('B');

    // Section 2: new client-side section with null ID
    expect(req.sections[1].sectionId).toBeNull();
    expect(req.sections[1].name).toBe('Balcony');

    // Elements: existing element and new element
    expect(req.elements.length).toBe(2);
    expect(req.elements[0].elementId).toBe('el-stage-1');
    expect(req.elements[0].type).toBe('STAGE');
    expect(req.elements[1].elementId).toBeNull();
    expect(req.elements[1].type).toBe('AISLE');
  });

  it('should use baseline layoutVersion exactly in buildSaveRequest even if draft layoutVersion was altered', () => {
    service.applyServerSnapshot(createMockLayout());
    expect(service.baseline()?.layoutVersion).toBe(7);

    service.replaceDraft((current) => {
      (current as unknown as Record<string, unknown>)['layoutVersion'] = 999;
      return current;
    });

    const req = service.buildSaveRequest();
    expect(req.layoutVersion).toBe(7);
  });

  it('should preserve decimal coordinates, negative rotations, and z-index through buildSaveRequest', () => {
    service.applyServerSnapshot(createMockLayout());

    const req = service.buildSaveRequest();

    // Decimals and negatives on section
    expect(req.sections[0].positionX).toBe(120.125);
    expect(req.sections[0].positionY).toBe(250.875);
    expect(req.sections[0].rotationDeg).toBe(5.5);
    expect(req.sections[1].rotationDeg).toBe(-10);

    // Decimals on seats
    expect(req.sections[0].seats[0].positionX).toBe(10.5);
    expect(req.sections[0].seats[0].positionY).toBe(15.75);

    // Decimals, negatives, and z-indices on elements
    expect(req.elements[0].geometry.x).toBe(100.5);
    expect(req.elements[0].geometry.y).toBe(25.25);
    expect(req.elements[0].geometry.width).toBe(800.75);
    expect(req.elements[0].geometry.rotationDeg).toBe(-15.5);
    expect(req.elements[0].zIndex).toBe(10);
    expect(req.elements[1].zIndex).toBe(-5);
  });

  it('should keep gridX/gridY alongside positionX/positionY in buildSaveRequest', () => {
    service.applyServerSnapshot(createMockLayout());

    const req = service.buildSaveRequest();
    const seat0 = req.sections[0].seats[0];

    expect(seat0.gridX).toBe(0);
    expect(seat0.gridY).toBe(0);
    expect(seat0.positionX).toBe(10.5);
    expect(seat0.positionY).toBe(15.75);

    const seat1 = req.sections[0].seats[1];
    expect(seat1.gridX).toBe(1);
    expect(seat1.gridY).toBe(0);
    expect(seat1.positionX).toBe(50.5);
    expect(seat1.positionY).toBe(15.75);
  });

  it('should exclude any transient UI state from the buildSaveRequest output JSON', () => {
    service.applyServerSnapshot(createMockLayout());

    const editable = JSON.parse(JSON.stringify(service.layout())) as Record<string, unknown>;
    editable['extraUiField'] = 'should-not-exist';
    (editable['sections'] as Record<string, unknown>[])[0]['selected'] = true;
    (editable['sections'] as Record<string, unknown>[])[0]['isDragging'] = true;
    (editable['sections'] as Record<string, unknown>[])[0]['dragOffset'] = { x: 5, y: 10 };
    (
      (
        (editable['sections'] as Record<string, unknown>[])[0]['seats'] as Record<string, unknown>[]
      )[0] as Record<string, unknown>
    )['isHovered'] = true;
    (editable['elements'] as Record<string, unknown>[])[0]['isEditing'] = true;
    service.replaceDraft(editable as unknown as VenueLayout);

    const req = service.buildSaveRequest();
    const serialized = JSON.parse(JSON.stringify(req));

    // Request root keys: exactly layoutVersion, sections, elements
    expect(Object.keys(serialized).sort()).toEqual(['elements', 'layoutVersion', 'sections']);
    expect((serialized as Record<string, unknown>)['extraUiField']).toBeUndefined();

    // Section keys: exactly backend contract keys
    expect(Object.keys(serialized.sections[0]).sort()).toEqual([
      'colCount',
      'height',
      'isActive',
      'name',
      'positionX',
      'positionY',
      'rotationDeg',
      'rowCount',
      'seats',
      'sectionId',
      'shapeMetadata',
      'width',
      'zIndex',
    ]);
    expect(serialized.sections[0].selected).toBeUndefined();
    expect(serialized.sections[0].isDragging).toBeUndefined();

    // Seat keys: exactly backend contract keys
    expect(Object.keys(serialized.sections[0].seats[0]).sort()).toEqual([
      'gridX',
      'gridY',
      'isActive',
      'positionX',
      'positionY',
      'rowLabel',
      'seatId',
      'seatNumber',
    ]);
    expect(serialized.sections[0].seats[0].isHovered).toBeUndefined();

    // Element keys: exactly backend contract keys
    expect(Object.keys(serialized.elements[0]).sort()).toEqual([
      'elementId',
      'geometry',
      'label',
      'type',
      'zIndex',
    ]);
    expect(serialized.elements[0].isEditing).toBeUndefined();
  });

  it('should handle empty sections and elements arrays in round-trip serialization', () => {
    const emptyLayout: VenueLayout = {
      venueId: 'ven-empty',
      name: 'Empty Venue',
      capacity: 0,
      totalConfiguredSeats: 0,
      layoutVersion: 1,
      sections: [],
      elements: [],
    };

    service.applyServerSnapshot(emptyLayout);
    expect(service.isDirty()).toBeFalse();

    const req = service.buildSaveRequest();
    expect(req.layoutVersion).toBe(1);
    expect(req.sections).toEqual([]);
    expect(req.elements).toEqual([]);
  });

  it('should update baseline and draft on successful save, setting isSaving and isDirty to false', () => {
    service.applyServerSnapshot(createMockLayout());

    service.replaceDraft((current) => {
      current.sections[0].positionX = 200;
      return current;
    });
    expect(service.isDirty()).toBeTrue();

    const base = createMockLayout();
    const updatedServerResponse: VenueLayout = {
      ...base,
      layoutVersion: 8,
      sections: [
        {
          ...base.sections[0],
          positionX: 200,
          seats: [
            base.sections[0].seats[0],
            base.sections[0].seats[1],
            {
              ...base.sections[0].seats[2],
              seatId: 'seat-new-assigned-uuid', // server assigned UUID
            },
          ],
        },
        {
          ...base.sections[1],
          sectionId: 'sec-new-assigned-uuid', // server assigned UUID
        },
      ],
    };

    apiSpy.saveLayout.and.returnValue(of(updatedServerResponse));

    service.save('ven-123').subscribe((res) => {
      expect(res.layoutVersion).toBe(8);
    });

    expect(apiSpy.saveLayout).toHaveBeenCalled();
    expect(service.isSaving()).toBeFalse();
    expect(service.baseline()?.layoutVersion).toBe(8);
    expect(service.baseline()?.sections[1].sectionId).toBe('sec-new-assigned-uuid');
    expect(service.layout()?.sections[0].seats[2].seatId).toBe('seat-new-assigned-uuid');
    expect(service.isDirty()).toBeFalse();
  });

  it('should not mutate baseline or increment layoutVersion when save fails, and keep isSaving false', () => {
    service.applyServerSnapshot(createMockLayout());

    service.replaceDraft((current) => {
      current.sections[0].positionX = 350;
      return current;
    });
    expect(service.isDirty()).toBeTrue();

    apiSpy.saveLayout.and.returnValue(
      throwError(() => ({
        status: 409,
        error: { errorCode: 'SF_409_CONFLICT', message: 'Stale layout version' },
      })),
    );

    service.save('ven-123').subscribe({
      error: (err) => {
        expect(err.status).toBe(409);
      },
    });

    expect(service.isSaving()).toBeFalse();
    expect(service.baseline()?.layoutVersion).toBe(7);
    expect(service.baseline()?.sections[0].positionX).toBe(120.125);
    expect(service.layout()?.sections[0].positionX).toBe(350);
    expect(service.isDirty()).toBeTrue();
  });

  it('should allow replaceDraft using an updater function', () => {
    service.applyServerSnapshot(createMockLayout());

    service.replaceDraft((curr) => ({
      ...curr,
      sections: curr.sections.map((s, idx) => (idx === 0 ? { ...s, positionY: 777 } : s)),
    }));

    expect(service.layout()?.sections[0].positionY).toBe(777);
    expect(service.isDirty()).toBeTrue();
  });

  it('should delegate validation request to AdminVenueApiService.validateLayout', () => {
    service.applyServerSnapshot(createMockLayout());
    apiSpy.validateLayout.and.returnValue(of(undefined));

    let validated = false;
    service.validate('ven-123').subscribe(() => {
      validated = true;
    });

    expect(apiSpy.validateLayout).toHaveBeenCalledWith(
      'ven-123',
      jasmine.any(Object) as unknown as SaveVenueLayoutRequest,
    );
    expect(validated).toBeTrue();
  });

  it('should throw error when building save request if no layout is loaded', () => {
    expect(() => service.buildSaveRequest()).toThrowError(
      'Cannot build save request: no layout loaded',
    );
  });

  it('should reject undefined seatId while accepting null seatId without mutating baseline', () => {
    service.applyServerSnapshot(createMockLayout());
    const baselineBefore = JSON.parse(JSON.stringify(service.baseline()));

    const malformed = createMockLayout() as unknown as Record<string, unknown>;
    (
      (malformed['sections'] as Record<string, unknown>[])[0]['seats'] as Record<string, unknown>[]
    )[0]['seatId'] = undefined;

    expect(() => service.applyServerSnapshot(malformed as unknown as VenueLayout)).toThrowError(
      /sections\[0\]\.seats\[0\]\.seatId.*string \| null/,
    );
    expect(service.baseline()).toEqual(baselineBefore);
    expect(service.layout()).toEqual(baselineBefore);
    expect(service.isDirty()).toBeFalse();

    const withNullId = createMockLayout();
    withNullId.sections[0].seats[0].seatId = null;
    expect(() => service.applyServerSnapshot(withNullId)).not.toThrow();
    expect(service.baseline()?.sections[0].seats[0].seatId).toBeNull();
  });

  it('should reject undefined section and element IDs while accepting null IDs', () => {
    service.applyServerSnapshot(createMockLayout());
    const baselineBefore = JSON.parse(JSON.stringify(service.baseline()));

    const badSection = createMockLayout() as unknown as Record<string, unknown>;
    (badSection['sections'] as Record<string, unknown>[])[0]['sectionId'] = undefined;
    expect(() => service.applyServerSnapshot(badSection as unknown as VenueLayout)).toThrowError(
      /sections\[0\]\.sectionId/,
    );
    expect(service.baseline()).toEqual(baselineBefore);

    const badElement = createMockLayout() as unknown as Record<string, unknown>;
    (badElement['elements'] as Record<string, unknown>[])[0]['elementId'] = undefined;
    expect(() => service.applyServerSnapshot(badElement as unknown as VenueLayout)).toThrowError(
      /elements\[0\]\.elementId/,
    );
    expect(service.baseline()).toEqual(baselineBefore);
  });

  it('should reject snapshots missing required geometry, activity, version, and array fields', () => {
    service.applyServerSnapshot(createMockLayout());
    const baselineBefore = JSON.parse(JSON.stringify(service.baseline()));

    const cases: Array<{ name: string; mutate: (layout: unknown) => void; pattern: RegExp }> = [
      {
        name: 'missing seat positionX',
        mutate: (layout) => {
          const draft = layout as unknown as Record<string, unknown>;
          delete (
            (
              (draft['sections'] as Record<string, unknown>[])[0]['seats'] as Record<
                string,
                unknown
              >[]
            )[0] as Record<string, unknown>
          )['positionX'];
        },
        pattern: /sections\[0\]\.seats\[0\]\.positionX/,
      },
      {
        name: 'missing seat positionY',
        mutate: (layout) => {
          const draft = layout as unknown as Record<string, unknown>;
          delete (
            (
              (draft['sections'] as Record<string, unknown>[])[0]['seats'] as Record<
                string,
                unknown
              >[]
            )[0] as Record<string, unknown>
          )['positionY'];
        },
        pattern: /sections\[0\]\.seats\[0\]\.positionY/,
      },
      {
        name: 'missing section isActive',
        mutate: (layout) => {
          const draft = layout as unknown as Record<string, unknown>;
          delete ((draft['sections'] as Record<string, unknown>[])[0] as Record<string, unknown>)[
            'isActive'
          ];
        },
        pattern: /sections\[0\]\.isActive/,
      },
      {
        name: 'missing section positionX',
        mutate: (layout) => {
          const draft = layout as unknown as Record<string, unknown>;
          delete ((draft['sections'] as Record<string, unknown>[])[0] as Record<string, unknown>)[
            'positionX'
          ];
        },
        pattern: /sections\[0\]\.positionX/,
      },
      {
        name: 'missing section width',
        mutate: (layout) => {
          const draft = layout as unknown as Record<string, unknown>;
          delete ((draft['sections'] as Record<string, unknown>[])[0] as Record<string, unknown>)[
            'width'
          ];
        },
        pattern: /sections\[0\]\.width/,
      },
      {
        name: 'missing section height',
        mutate: (layout) => {
          const draft = layout as unknown as Record<string, unknown>;
          delete ((draft['sections'] as Record<string, unknown>[])[0] as Record<string, unknown>)[
            'height'
          ];
        },
        pattern: /sections\[0\]\.height/,
      },
      {
        name: 'missing section rotationDeg',
        mutate: (layout) => {
          const draft = layout as unknown as Record<string, unknown>;
          delete ((draft['sections'] as Record<string, unknown>[])[0] as Record<string, unknown>)[
            'rotationDeg'
          ];
        },
        pattern: /sections\[0\]\.rotationDeg/,
      },
      {
        name: 'missing section zIndex',
        mutate: (layout) => {
          const draft = layout as unknown as Record<string, unknown>;
          delete ((draft['sections'] as Record<string, unknown>[])[0] as Record<string, unknown>)[
            'zIndex'
          ];
        },
        pattern: /sections\[0\]\.zIndex/,
      },
      {
        name: 'missing section shapeMetadata',
        mutate: (layout) => {
          const draft = layout as unknown as Record<string, unknown>;
          delete ((draft['sections'] as Record<string, unknown>[])[0] as Record<string, unknown>)[
            'shapeMetadata'
          ];
        },
        pattern: /sections\[0\]\.shapeMetadata/,
      },
      {
        name: 'missing layoutVersion',
        mutate: (layout) => {
          delete (layout as unknown as Record<string, unknown>)['layoutVersion'];
        },
        pattern: /layoutVersion/,
      },
      {
        name: 'missing elements array',
        mutate: (layout) => {
          delete (layout as unknown as Record<string, unknown>)['elements'];
        },
        pattern: /elements/,
      },
      {
        name: 'missing sections array',
        mutate: (layout) => {
          delete (layout as unknown as Record<string, unknown>)['sections'];
        },
        pattern: /sections/,
      },
      {
        name: 'missing seats array',
        mutate: (layout) => {
          const draft = layout as unknown as Record<string, unknown>;
          delete ((draft['sections'] as Record<string, unknown>[])[0] as Record<string, unknown>)[
            'seats'
          ];
        },
        pattern: /sections\[0\]\.seats/,
      },
      {
        name: 'missing element geometry',
        mutate: (layout) => {
          const draft = layout as unknown as Record<string, unknown>;
          delete ((draft['elements'] as Record<string, unknown>[])[0] as Record<string, unknown>)[
            'geometry'
          ];
        },
        pattern: /elements\[0\]\.geometry/,
      },
      {
        name: 'missing element type',
        mutate: (layout) => {
          const draft = layout as unknown as Record<string, unknown>;
          delete ((draft['elements'] as Record<string, unknown>[])[0] as Record<string, unknown>)[
            'type'
          ];
        },
        pattern: /elements\[0\]\.type/,
      },
    ];

    for (const testCase of cases) {
      const malformed = JSON.parse(JSON.stringify(createMockLayout())) as unknown;
      testCase.mutate(malformed);
      expect(() => service.applyServerSnapshot(malformed as VenueLayout))
        .withContext(testCase.name)
        .toThrowError(testCase.pattern);
      expect(service.baseline()).withContext(testCase.name).toEqual(baselineBefore);
      expect(service.layout()).withContext(testCase.name).toEqual(baselineBefore);
    }
    expect(service.isDirty()).toBeFalse();
  });

  it('should reject malformed load without mutating baseline and without issuing a destructive request', () => {
    service.applyServerSnapshot(createMockLayout());
    const baselineBefore = JSON.parse(JSON.stringify(service.baseline()));
    expect(service.isDirty()).toBeFalse();

    const malformed = JSON.parse(JSON.stringify(createMockLayout())) as unknown as Record<
      string,
      unknown
    >;
    (
      (malformed['sections'] as Record<string, unknown>[])[0]['seats'] as Record<string, unknown>[]
    )[0]['seatId'] = undefined;
    delete (
      (malformed['sections'] as Record<string, unknown>[])[0]['seats'] as Record<string, unknown>[]
    )[0]['positionX'];
    apiSpy.getEditableLayout.and.returnValue(of(malformed as unknown as VenueLayout));

    let loadFailed = false;
    service.load('ven-123').subscribe({
      error: () => {
        loadFailed = true;
      },
    });

    expect(loadFailed).toBeTrue();
    expect(service.baseline()).toEqual(baselineBefore);
    expect(service.layout()).toEqual(baselineBefore);
    expect(service.isDirty()).toBeFalse();
    expect(service.loadError()).toMatch(/seatId|positionX/);

    const req = service.buildSaveRequest();
    expect(req.layoutVersion).toBe(7);
    expect(req.sections[0].seats[0].seatId).toBe('seat-101');
    expect(req.sections[0].seats[0].positionX).toBe(10.5);
    expect(apiSpy.saveLayout).not.toHaveBeenCalled();
    expect(apiSpy.validateLayout).not.toHaveBeenCalled();
  });

  it('should reject caller-created partial drafts in replaceDraft and buildSaveRequest', () => {
    service.applyServerSnapshot(createMockLayout());
    const baselineBefore = JSON.parse(JSON.stringify(service.baseline()));
    const layoutBefore = JSON.parse(JSON.stringify(service.layout()));

    const partial = {
      venueId: 'ven-123',
      name: 'Partial',
      capacity: 10,
      totalConfiguredSeats: 0,
      layoutVersion: 7,
      sections: [
        {
          sectionId: null,
          name: 'Incomplete',
          rowCount: 1,
          colCount: 1,
          seats: [],
        },
      ],
      elements: [],
    } as unknown as VenueLayout;

    expect(() => service.replaceDraft(partial)).toThrowError(
      /sections\[0\]\.isActive|sections\[0\]\.positionX/,
    );
    expect(service.layout()).toEqual(layoutBefore);
    expect(service.baseline()).toEqual(baselineBefore);

    expect(() => service.buildSaveRequest()).not.toThrow();
    expect(apiSpy.saveLayout).not.toHaveBeenCalled();
  });

  it('should not issue save or validate requests when the draft is malformed', () => {
    service.applyServerSnapshot(createMockLayout());
    apiSpy.saveLayout.calls.reset();
    apiSpy.validateLayout.calls.reset();

    const editable = JSON.parse(JSON.stringify(service.layout())) as unknown as Record<
      string,
      unknown
    >;
    delete (
      (
        (editable['sections'] as Record<string, unknown>[])[0]['seats'] as Record<string, unknown>[]
      )[0] as Record<string, unknown>
    )['positionX'];

    // replaceDraft itself must reject the malformed draft before it can be stored.
    expect(() => service.replaceDraft(editable as unknown as VenueLayout)).toThrowError(
      /sections\[0\]\.seats\[0\]\.positionX/,
    );
    expect(apiSpy.saveLayout).not.toHaveBeenCalled();
    expect(apiSpy.validateLayout).not.toHaveBeenCalled();

    // Stored draft is still the last good snapshot, so a save still sends the good payload.
    const req = service.buildSaveRequest();
    expect(req.sections[0].seats[0].positionX).toBe(10.5);
  });

  it('should expose frozen layout and baseline snapshots that resist direct nested mutation', () => {
    service.applyServerSnapshot(createMockLayout());
    expect(service.isDirty()).toBeFalse();

    expect(Object.isFrozen(service.layout())).toBeTrue();
    expect(Object.isFrozen(service.baseline())).toBeTrue();
    expect(Object.isFrozen(service.layout()?.sections)).toBeTrue();
    expect(Object.isFrozen(service.layout()?.sections[0])).toBeTrue();
    expect(Object.isFrozen(service.layout()?.sections[0].seats)).toBeTrue();
    expect(Object.isFrozen(service.layout()?.sections[0].seats[0])).toBeTrue();
    expect(Object.isFrozen(service.layout()?.elements)).toBeTrue();
    expect(Object.isFrozen(service.layout()?.elements[0])).toBeTrue();
    expect(Object.isFrozen(service.layout()?.elements[0].geometry)).toBeTrue();

    const dirtyBefore = service.isDirty();
    const layoutAny = service.layout() as unknown as Record<string, unknown>;
    expect(() => {
      (
        (layoutAny['sections'] as Record<string, unknown>[])[0]['seats'] as Record<
          string,
          unknown
        >[]
      )[0]['positionX'] = 99;
    }).toThrow();

    expect(service.layout()?.sections[0].seats[0].positionX).toBe(10.5);
    expect(service.baseline()?.sections[0].seats[0].positionX).toBe(10.5);
    expect(service.isDirty()).toBe(dirtyBefore);
    expect(service.isDirty()).toBeFalse();
  });

  it('should only invalidate dirty state through replaceDraft, not through direct mutation attempts', () => {
    service.applyServerSnapshot(createMockLayout());
    expect(service.isDirty()).toBeFalse();

    const layoutAny = service.layout() as unknown as Record<string, unknown>;
    try {
      (layoutAny['sections'] as Record<string, unknown>[])[0]['positionX'] = 9999;
    } catch {
      // Frozen mutation throws in strict mode; the key assertion is state is unchanged.
    }
    expect(service.layout()?.sections[0].positionX).toBe(120.125);
    expect(service.isDirty()).toBeFalse();

    service.replaceDraft((current) => {
      current.sections[0].positionX = 9999;
      return current;
    });
    expect(service.layout()?.sections[0].positionX).toBe(9999);
    expect(service.isDirty()).toBeTrue();
  });

  it('should give the replaceDraft updater an editable detached clone', () => {
    service.applyServerSnapshot(createMockLayout());

    service.replaceDraft((current) => {
      expect(Object.isFrozen(current)).toBeFalse();
      current.sections[0].name = 'Edited Orchestra';
      return current;
    });

    expect(service.layout()?.sections[0].name).toBe('Edited Orchestra');
    expect(service.baseline()?.sections[0].name).toBe('Orchestra');
    expect(service.isDirty()).toBeTrue();
  });

  it('should keep baseline and draft as independent deep copies across server replacement', () => {
    service.applyServerSnapshot(createMockLayout());

    expect(service.baseline()).not.toBe(service.layout());
    expect(service.baseline()?.sections[0]).not.toBe(service.layout()?.sections[0]);
    expect(service.baseline()?.sections[0].seats[0]).not.toBe(
      service.layout()?.sections[0].seats[0],
    );

    service.replaceDraft((current) => {
      current.sections[0].seats[0].rowLabel = 'Z';
      return current;
    });
    expect(service.layout()?.sections[0].seats[0].rowLabel).toBe('Z');
    expect(service.baseline()?.sections[0].seats[0].rowLabel).toBe('A');
  });
});
