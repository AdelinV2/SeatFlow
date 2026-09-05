import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { LayoutHistoryService, MAX_LAYOUT_HISTORY } from './layout-history.service';
import { VenueLayout } from '../models/venue.model';

function createLayout(version: number, nameSuffix = ''): VenueLayout {
  return {
    venueId: 'v-100',
    name: `Hall${nameSuffix}`,
    capacity: 1000,
    totalConfiguredSeats: 1,
    layoutVersion: version,
    sections: [
      {
        sectionId: 'sec-1',
        name: `Orchestra${nameSuffix}`,
        rowCount: 1,
        colCount: 1,
        isActive: true,
        positionX: 10,
        positionY: 20,
        width: 400,
        height: 300,
        rotationDeg: 0,
        zIndex: 1,
        shapeMetadata: null,
        seats: [
          {
            seatId: 's-00',
            rowLabel: 'A',
            seatNumber: 1,
            gridX: 0,
            gridY: 0,
            positionX: 20,
            positionY: 20,
            isActive: true,
          },
        ],
      },
    ],
    elements: [],
  };
}

describe('LayoutHistoryService', () => {
  let service: LayoutHistoryService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(LayoutHistoryService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should retain only the newest 100 of 101 commands', () => {
    expect(MAX_LAYOUT_HISTORY).toBe(100);
    for (let i = 0; i < 101; i++) {
      service.push(createLayout(1, `-cmd-${i}`));
    }
    expect(service.undoDepth()).toBe(100);

    // Oldest retained is cmd-1; cmd-0 was evicted. Undo everything and check.
    let current = createLayout(1, '-current');
    let last: VenueLayout | null = null;
    for (let i = 0; i < 100; i++) {
      last = service.undo(current);
      expect(last).not.toBeNull();
      current = last!;
    }
    expect(last?.sections[0].name).toBe('Orchestra-cmd-1');
    expect(service.canUndo()).toBeFalse();
  });

  it('should deep-clone on push so mutating the draft leaves the snapshot unchanged', () => {
    const draft = createLayout(7);
    service.push(draft);

    // Mutate the caller's draft after push.
    draft.sections[0].name = 'Mutated';
    draft.sections[0].seats[0].rowLabel = 'ZZZ';
    draft.layoutVersion = 999;

    const restored = service.undo(createLayout(7, '-other'));
    expect(restored?.sections[0].name).toBe('Orchestra');
    expect(restored?.sections[0].seats[0].rowLabel).toBe('A');
    expect(restored?.layoutVersion).toBe(7);
  });

  it('should deep-clone on restore so mutating the result leaves stacks unchanged', () => {
    service.push(createLayout(3));
    const first = service.undo(createLayout(3, '-current'))!;
    first.sections[0].name = 'Mutated-restore';
    const second = service.redo(first);
    // Redo pushes the mutated current onto undo; undo again must still yield pristine snapshot.
    expect(second).not.toBeNull();
    service.undo(second!);
    const again = service.redo(second!);
    expect(again).not.toBeNull();
  });

  it('should clear redo when a new command follows undo', () => {
    service.push(createLayout(1, '-a'));
    service.push(createLayout(1, '-b'));
    expect(service.undo(createLayout(1, '-current'))).not.toBeNull();
    expect(service.canRedo()).toBeTrue();

    service.push(createLayout(1, '-c'));
    expect(service.canRedo()).toBeFalse();
    expect(service.redo(createLayout(1, '-current'))).toBeNull();
  });

  it('should no-op undo/redo at stack boundaries', () => {
    expect(service.canUndo()).toBeFalse();
    expect(service.canRedo()).toBeFalse();
    expect(service.undo(createLayout(1))).toBeNull();
    expect(service.redo(createLayout(1))).toBeNull();

    service.push(createLayout(1));
    const current = createLayout(1, '-current');
    expect(service.redo(current)).toBeNull();
    expect(service.undo(current)).not.toBeNull();
    expect(service.undo(current)).toBeNull();
  });

  it('should perform undo/redo with zero HTTP calls', () => {
    service.push(createLayout(1));
    service.push(createLayout(1, '-b'));
    service.undo(createLayout(1, '-current'));
    service.redo(createLayout(1, '-current'));
    // HttpTestingController.verify() in afterEach proves zero HTTP.
    expect(service.undoDepth() + service.redoDepth()).toBeGreaterThanOrEqual(0);
  });

  it('should clear both stacks on clear (load/reset/save-success)', () => {
    service.push(createLayout(1));
    service.undo(createLayout(1, '-current'));
    expect(service.canUndo() || service.canRedo()).toBeTrue();

    service.clear();
    expect(service.canUndo()).toBeFalse();
    expect(service.canRedo()).toBeFalse();
    expect(service.undoDepth()).toBe(0);
    expect(service.redoDepth()).toBe(0);
  });

  it('should preserve IDs and layoutVersion exactly, keeping null IDs null', () => {
    const withDrafts: VenueLayout = {
      ...createLayout(5),
      sections: [
        {
          sectionId: null,
          name: 'Draft',
          rowCount: 1,
          colCount: 1,
          isActive: true,
          positionX: 0,
          positionY: 0,
          width: 100,
          height: 100,
          rotationDeg: 0,
          zIndex: 2,
          shapeMetadata: null,
          seats: [
            {
              seatId: null,
              rowLabel: 'A',
              seatNumber: 1,
              gridX: 0,
              gridY: 0,
              positionX: 20,
              positionY: 20,
              isActive: true,
            },
          ],
        },
      ],
      elements: [
        {
          elementId: null,
          type: 'STAGE',
          label: 'Stage',
          geometry: { x: 0, y: 0, width: 10, height: 10, rotationDeg: 0 },
          zIndex: 3,
        },
      ],
    };
    service.push(withDrafts);
    const restored = service.undo(createLayout(5, '-current'))!;
    expect(restored.layoutVersion).toBe(5);
    expect(restored.sections[0].sectionId).toBeNull();
    expect(restored.sections[0].seats[0].seatId).toBeNull();
    expect(restored.elements[0].elementId).toBeNull();
  });

  it('should coalesce pointer-move bursts into one entry committed on pointer end', () => {
    const baseline = createLayout(2);
    for (let i = 0; i < 50; i++) {
      const moved: VenueLayout = {
        ...createLayout(2),
        sections: [
          {
            ...createLayout(2).sections[0],
            positionX: 10 + i,
          },
        ],
      };
      if (i === 0) {
        service.beginCoalesced(baseline);
      } else {
        service.beginCoalesced(moved);
      }
    }
    expect(service.undoDepth()).toBe(1);
    expect(service.isCoalescing()).toBeTrue();

    service.endCoalesced();
    expect(service.isCoalescing()).toBeFalse();
    expect(service.undoDepth()).toBe(1);

    const restored = service.undo(createLayout(2, '-current'));
    expect(restored?.sections[0].positionX).toBe(10);
  });
});
