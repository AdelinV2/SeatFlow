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
