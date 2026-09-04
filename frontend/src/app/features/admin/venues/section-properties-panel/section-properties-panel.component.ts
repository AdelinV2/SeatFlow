import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { VenueSectionLayout, VenueSectionSeat } from '../../../../models/venue.model';
import {
  GenerateSeatsOptions,
  getSeatKey,
  isSeatSelected,
} from '../../../../services/seat-layout-generator.service';

@Component({
  selector: 'app-section-properties-panel',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './section-properties-panel.component.html',
  styleUrl: './section-properties-panel.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SectionPropertiesPanelComponent {
  // Inputs
  readonly section = input<VenueSectionLayout | null>(null);
  readonly venueCapacity = input<number>(0);
  readonly totalConfiguredActiveSeats = input<number>(0);
  readonly selectedSeatKeys = input<Set<string>>(new Set());
  readonly validationMessage = input<string | null>(null);

  // Outputs
  readonly sectionUpdated = output<Partial<VenueSectionLayout>>();
  readonly duplicateSection = output<void>();
  readonly deactivateSection = output<void>();
  readonly reactivateSection = output<void>();
  readonly removeSection = output<void>();
  readonly generateSeats = output<GenerateSeatsOptions>();
  readonly bulkActivate = output<boolean>();
  readonly bulkTranslate = output<{ deltaX: number; deltaY: number }>();
  readonly bulkSetRowLabel = output<string>();
  readonly bulkRenumber = output<number>();
  readonly seatSelectionChanged = output<Set<string>>();
  readonly sectionColorChanged = output<string>();
  readonly seatColorAssigned = output<{ seatKeys: string[]; color: string }>();
  readonly rowToggled = output<{ rowLabel: string; active?: boolean }>();
  readonly colToggled = output<{ colIndex: number; active?: boolean }>();
  readonly centerAisleCreated = output<void>();
  readonly dualAislesCreated = output<void>();
  readonly allSeatsActivated = output<void>();
  readonly rowAppended = output<void>();
  readonly colAppended = output<void>();

  // Color Theme Presets
  readonly PRESET_COLORS: ReadonlyArray<{ name: string; hex: string }> = [
    { name: 'Royal Indigo', hex: '#6366f1' },
    { name: 'Jewel Emerald', hex: '#059669' },
    { name: 'Amber Gold', hex: '#f59e0b' },
    { name: 'Ruby Rose', hex: '#f43f5e' },
    { name: 'Deep Violet', hex: '#8b5cf6' },
    { name: 'Ocean Cyan', hex: '#0ea5e9' },
    { name: 'Sunset Coral', hex: '#f97316' },
    { name: 'Fuchsia Pink', hex: '#d946ef' },
  ];

  // Quick Row / Col selectors
  readonly selectedQuickRow = signal<string>('');
  readonly selectedQuickCol = signal<number>(0);
  readonly customHexColor = signal<string>('#6366f1');

  // Generator form fields
  readonly generatorRows = signal<number>(10);
  readonly generatorCols = signal<number>(15);
  readonly generatorRowStart = signal<number>(0);
  readonly generatorSeatStart = signal<number>(1);
  readonly generatorPitchX = signal<number>(44);
  readonly generatorPitchY = signal<number>(44);
  readonly generatorOriginX = signal<number>(20);
  readonly generatorOriginY = signal<number>(20);

  // Bulk action inputs
  readonly translateDeltaX = signal<number>(0);
  readonly translateDeltaY = signal<number>(0);
  readonly bulkRowLabelText = signal<string>('');
  readonly bulkStartNumberVal = signal<number>(1);

  // UI state
  readonly showGeneratorAccordion = signal<boolean>(false);
  readonly showBulkTools = signal<boolean>(true);
  readonly showSeatTable = signal<boolean>(false);

  // Computed state
  readonly seatsList = computed<VenueSectionSeat[]>(() => {
    return this.section()?.seats || [];
  });

  readonly selectedCount = computed<number>(() => {
    return this.selectedSeatKeys().size;
  });

  readonly hasSelection = computed<boolean>(() => {
    return this.selectedCount() > 0;
  });

  readonly canRemove = computed<boolean>(() => {
    return this.section()?.sectionId === null;
  });

  readonly isSectionActive = computed<boolean>(() => {
    return this.section()?.isActive ?? true;
  });

  readonly activeSeatsCount = computed<number>(() => {
    return this.seatsList().filter((s) => s.isActive).length;
  });

  readonly allSeatsSelected = computed<boolean>(() => {
    const list = this.seatsList();
    if (list.length === 0) return false;
    const keys = this.selectedSeatKeys();
    return list.every((s) => isSeatSelected(s, keys));
  });

  readonly currentSectionColor = computed<string>(() => {
    const meta = this.section()?.shapeMetadata as Record<string, unknown> | null;
    if (meta && typeof meta['color'] === 'string' && meta['color']) {
      return meta['color'];
    }
    return '#6366f1';
  });

  readonly availableRows = computed(() => {
    const seats = this.seatsList();
    const map = new Map<string, { label: string; total: number; active: number }>();
    for (const s of seats) {
      let item = map.get(s.rowLabel);
      if (!item) {
        item = { label: s.rowLabel, total: 0, active: 0 };
        map.set(s.rowLabel, item);
      }
      item.total++;
      if (s.isActive) item.active++;
    }
    return Array.from(map.values());
  });

  readonly availableCols = computed(() => {
    const seats = this.seatsList();
    const sec = this.section();
    const colCount = sec?.colCount || 0;
    const cols: { colIndex: number; total: number; active: number }[] = [];
    for (let c = 0; c < colCount; c++) {
      const colSeats = seats.filter((s) => s.gridX === c);
      cols.push({
        colIndex: c,
        total: colSeats.length,
        active: colSeats.filter((s) => s.isActive).length,
      });
    }
    return cols;
  });

  selectSectionColor(color: string): void {
    this.sectionColorChanged.emit(color);
  }

  assignColorToSelectedSeats(color: string): void {
    const keys = Array.from(this.selectedSeatKeys());
    if (keys.length > 0) {
      this.seatColorAssigned.emit({ seatKeys: keys, color });
    }
  }

  triggerRowToggle(rowLabel: string, active?: boolean): void {
    this.rowToggled.emit({ rowLabel, active });
  }

  triggerColToggle(colIndex: number, active?: boolean): void {
    this.colToggled.emit({ colIndex, active });
  }

  triggerCenterAisle(): void {
    this.centerAisleCreated.emit();
  }

  triggerDualAisles(): void {
    this.dualAislesCreated.emit();
  }

  triggerActivateAllSeats(): void {
    this.allSeatsActivated.emit();
  }

  triggerAppendRow(): void {
    this.rowAppended.emit();
  }

  triggerAppendCol(): void {
    this.colAppended.emit();
  }

  selectRowSeats(rowLabel: string): void {
    const seats = this.seatsList().filter((s) => s.rowLabel === rowLabel);
    const keys = new Set(this.selectedSeatKeys());
    for (const s of seats) {
      keys.add(s.seatId || getSeatKey(s));
    }
    this.seatSelectionChanged.emit(keys);
  }

  selectColSeats(colIndex: number): void {
    const seats = this.seatsList().filter((s) => s.gridX === colIndex);
    const keys = new Set(this.selectedSeatKeys());
    for (const s of seats) {
      keys.add(s.seatId || getSeatKey(s));
    }
    this.seatSelectionChanged.emit(keys);
  }

  isSeatSelected(seat: VenueSectionSeat): boolean {
    return isSeatSelected(seat, this.selectedSeatKeys());
  }

  toggleSeat(seat: VenueSectionSeat): void {
    const current = new Set(this.selectedSeatKeys());
    const key = getSeatKey(seat);
    const idKey = seat.seatId;

    if (idKey && current.has(idKey)) {
      current.delete(idKey);
    } else if (current.has(key)) {
      current.delete(key);
    } else {
      current.add(idKey || key);
    }

    this.seatSelectionChanged.emit(current);
  }

  selectAllSeats(): void {
    const all = new Set<string>();
    for (const seat of this.seatsList()) {
      all.add(seat.seatId || getSeatKey(seat));
    }
    this.seatSelectionChanged.emit(all);
  }

  clearSelection(): void {
    this.seatSelectionChanged.emit(new Set());
  }

  onPropertyChange(field: keyof VenueSectionLayout, value: unknown): void {
    this.sectionUpdated.emit({ [field]: value });
  }

  onNumericPropertyChange(field: keyof VenueSectionLayout, event: Event): void {
    const inputEl = event.target as HTMLInputElement;
    const num = parseFloat(inputEl.value);
    if (!Number.isNaN(num)) {
      this.sectionUpdated.emit({ [field]: num });
    }
  }

  triggerGenerateSeats(): void {
    const sec = this.section();
    if (!sec) return;

    this.generateSeats.emit({
      rowCount: Number(this.generatorRows()),
      colCount: Number(this.generatorCols()),
      rowLabelStartIndex: Number(this.generatorRowStart()),
      seatNumberStart: Number(this.generatorSeatStart()),
      pitchX: Number(this.generatorPitchX()),
      pitchY: Number(this.generatorPitchY()),
      originX: Number(this.generatorOriginX()),
      originY: Number(this.generatorOriginY()),
      isActive: true,
      sectionWidth: sec.width,
      sectionHeight: sec.height,
      venueCapacity: this.venueCapacity(),
      totalOtherActiveSeats: Math.max(
        0,
        this.totalConfiguredActiveSeats() - this.activeSeatsCount(),
      ),
    });
  }

  triggerBulkActivate(targetActive: boolean): void {
    this.bulkActivate.emit(targetActive);
  }

  triggerBulkTranslate(): void {
    this.bulkTranslate.emit({
      deltaX: Number(this.translateDeltaX()),
      deltaY: Number(this.translateDeltaY()),
    });
  }

  triggerBulkSetRowLabel(): void {
    const label = this.bulkRowLabelText().trim();
    if (label) {
      this.bulkSetRowLabel.emit(label);
    }
  }

  triggerBulkRenumber(): void {
    const startNum = Number(this.bulkStartNumberVal());
    if (startNum >= 1) {
      this.bulkRenumber.emit(startNum);
    }
  }
}
