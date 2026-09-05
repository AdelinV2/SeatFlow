import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  HostListener,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import {
  EventSeatMapElementGeometry,
  EventSeatMapLayoutElement,
  Seat,
  SeatMapSectionResponse,
} from '../../../models/seat.model';
import {
  LayoutElementType,
  VenueLayoutElement,
  VenueSectionLayout,
  VenueSectionSeat,
} from '../../../models/venue.model';
import {
  CanvasSeatSelectedEvent,
  CustomerSeatPresentation,
  LayoutCanvasComponent,
} from '../../../shared/components/seat-layout/layout-canvas/layout-canvas.component';
import { isValidLayoutElementType } from '../../../shared/components/seat-layout/layout-element-palette/layout-element-palette.component';
import { CurrencyFormatPipe } from '../../../shared/pipes/currency-format.pipe';
import { resolveSectionColor, SECTION_PALETTE } from '../../../shared/utils/layout-geometry';

export interface PricingLegendTier {
  key: string;
  categoryName: string;
  price: number;
  currency: string;
  color: string;
  availableCount: number;
  totalCount: number;
}

export interface SectionPricingLegendGroup {
  sectionId: string;
  sectionName: string;
  sectionColor: string;
  totalSeats: number;
  availableSeats: number;
  tiers: PricingLegendTier[];
}

/** Muted neutral gray for held/sold/unavailable seats (ADR-015). */
export const MUTED_SEAT_GRAY = '#64748b';

const TIER_PALETTE: Record<string, string> = {
  'categoria a': '#7c3aed',
  'categoria b': '#2563eb',
  'categoria c': '#059669',
  'categoria d': '#d97706',
  'categoria e': '#db2777',
  vip: '#a855f7',
  premium: '#a855f7',
  standard: '#6366f1',
  student: '#10b981',
  child: '#f59e0b',
  senior: '#0ea5e9',
};

const FALLBACK_TIER_COLORS = [
  '#6366f1',
  '#0ea5e9',
  '#10b981',
  '#f59e0b',
  '#ec4899',
  '#14b8c6',
  '#f43f5e',
  '#8b5cf6',
];

/** Deterministic high-contrast tier color for a pricing category name. */
export function tierColorFor(categoryName: string | null | undefined): string {
  const normalized = (categoryName || 'Standard').trim().toLowerCase();
  const direct = TIER_PALETTE[normalized];
  if (direct) {
    return direct;
  }
  let hash = 0;
  for (let index = 0; index < normalized.length; index += 1) {
    hash = (hash * 31 + normalized.charCodeAt(index)) >>> 0;
  }
  return FALLBACK_TIER_COLORS[hash % FALLBACK_TIER_COLORS.length];
}

/** Stable legend key: persisted tier id wins, otherwise category+price+currency. */
export function seatTierKey(seat: Seat): string {
  if (seat.pricingTierId) {
    return seat.pricingTierId;
  }
  return `${seat.categoryName || 'Standard'}::${seat.price}::${seat.currency || 'USD'}`;
}

/** Check if a seat or its section supports the given pricing tier legend key. */
export function seatSupportsTier(seat: Seat, legendKey: string): boolean {
  if (seat.pricingTierId && seat.pricingTierId === legendKey) {
    return true;
  }
  const defaultKey = `${seat.categoryName || 'Standard'}::${seat.price}::${seat.currency || 'USD'}`;
  if (defaultKey === legendKey) {
    return true;
  }
  const secKey = `${seat.sectionId}::${seat.categoryName || 'Standard'}::${seat.price}::${seat.currency || 'USD'}`;
  if (secKey === legendKey) {
    return true;
  }
  if (seat.pricingTiers && seat.pricingTiers.length > 0) {
    return seat.pricingTiers.some((tier) => {
      if (tier.id && tier.id === legendKey) {
        return true;
      }
      const k = `${tier.categoryName || 'Standard'}::${tier.price}::${tier.currency || 'USD'}`;
      if (k === legendKey) {
        return true;
      }
      const sk = `${seat.sectionId}::${tier.categoryName || 'Standard'}::${tier.price}::${tier.currency || 'USD'}`;
      return sk === legendKey;
    });
  }
  return false;
}

const GRID_UNIT = 44;

/**
 * Read-only element gate for the customer canvas (TASK-P11-012 FIX C).
 *
 * A saved STAGE always carries finite geometry with positive dimensions, so it
 * passes straight through to the shared renderer. Elements the backend emits
 * with `geometry: null` (legacy rows) or degenerate/unknown payloads are
 * dropped instead of rendering as invisible zero-size rects at the origin —
 * which would also pollute the auto-fit bounds — and unknown runtime types
 * are dropped so customers never see designer validation chrome.
 */
export function isCustomerRenderableElement(
  element: EventSeatMapLayoutElement,
): element is EventSeatMapLayoutElement & { geometry: EventSeatMapElementGeometry } {
  const type = (element?.type || '').toUpperCase();
  if (!isValidLayoutElementType(type)) {
    return false;
  }
  const g = element?.geometry;
  const width = g?.width ?? (g as any)?.w;
  const height = g?.height ?? (g as any)?.h;
  return (
    !!g &&
    Number.isFinite(g.x) &&
    Number.isFinite(g.y) &&
    Number.isFinite(width) &&
    Number.isFinite(height) &&
    width > 0 &&
    height > 0
  );
}

@Component({
  selector: 'app-seat-map',
  standalone: true,
  imports: [CommonModule, LayoutCanvasComponent, CurrencyFormatPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './seat-map.component.html',
  styleUrl: './seat-map.component.scss',
})
export class SeatMapComponent {
  private readonly snackBar = inject(MatSnackBar);
  private readonly hostRef = inject(ElementRef<HTMLElement>);

  readonly seats = input.required<Seat[]>();
  readonly sectionsData = input<SeatMapSectionResponse[]>([]);
  readonly layoutElements = input<EventSeatMapLayoutElement[]>([]);
  readonly maxSeats = input(10);
  readonly selectedSeatIds = input<Set<string>>(new Set());
  readonly conflictingSeatIds = input<Set<string>>(new Set());
  /** Admin preview: read-only draft rendering without prices/availability. */
  readonly previewMode = input(false);
  readonly seatToggled = output<Seat>();

  readonly highlightedCategoryKey = signal<string | null>(null);
  readonly liveAnnouncement = signal<string>('');
  readonly activeSeatId = signal<string | null>(null);

  readonly seatById = computed(() => {
    const map = new Map<string, Seat>();
    for (const seat of this.seats()) {
      map.set(seat.id, seat);
    }
    return map;
  });

  /**
   * Focusable seats: active, non-disabled inventory. Rotation is visual-only,
   * so keyboard order and arrow navigation keep using gridX/gridY.
   */
  readonly bookableSeats = computed(() => {
    return this.seats().filter((seat) => seat.isActive && seat.status !== 'DISABLED');
  });

  readonly effectiveActiveSeatId = computed<string | null>(() => {
    const current = this.activeSeatId();
    const available = this.bookableSeats();
    if (current && available.some((seat) => seat.id === current)) {
      return current;
    }
    const firstAvailable = available.find((seat) => seat.status === 'AVAILABLE') ?? available[0];
    return firstAvailable?.id ?? null;
  });

  /**
   * Unified-hall canvas sections (ADR-015): every active section with shared
   * venue-canvas geometry. Seats join by stable sectionId; seat coordinates
   * prefer continuous positions with the exact 44-unit grid fallback.
   */
  readonly canvasSections = computed<VenueSectionLayout[]>(() => {
    const seats = this.seats();
    const seatsBySection = new Map<string, Seat[]>();
    for (const seat of seats) {
      const group = seatsBySection.get(seat.sectionId);
      if (group) {
        group.push(seat);
      } else {
        seatsBySection.set(seat.sectionId, [seat]);
      }
    }

    const shells = this.sectionShells(seatsBySection);
    return shells.map((shell) => ({
      sectionId: shell.sectionId,
      name: shell.name,
      rowCount: shell.rowCount,
      colCount: shell.colCount,
      isActive: true,
      positionX: shell.positionX,
      positionY: shell.positionY,
      width: shell.width,
      height: shell.height,
      rotationDeg: shell.rotationDeg,
      zIndex: shell.zIndex,
      shapeMetadata: shell.shapeMetadata,
      seats: (seatsBySection.get(shell.key) ?? []).map((seat) => this.toCanvasSeat(seat)),
    }));
  });

  readonly canvasElements = computed<VenueLayoutElement[]>(() => {
    const rawElements = (this.layoutElements() ?? [])
      .filter(isCustomerRenderableElement)
      .map((element) => {
        const type = (element.type || '').toUpperCase() as LayoutElementType;
        const width = Number(element.geometry.width ?? (element.geometry as any).w);
        const height = Number(element.geometry.height ?? (element.geometry as any).h);
        return {
          elementId: element.elementId,
          type,
          label: element.label ?? (type === 'STAGE' ? 'STAGE' : null),
          geometry: {
            x: Number(element.geometry.x),
            y: Number(element.geometry.y),
            width,
            height,
            rotationDeg: Number.isFinite(element.geometry.rotationDeg)
              ? (element.geometry.rotationDeg ?? 0)
              : 0,
          },
          zIndex: element.zIndex ?? 0,
        };
      });

    // If an explicit STAGE element is present, return the elements
    const hasStage = rawElements.some((e) => e.type === 'STAGE');
    if (hasStage) {
      return rawElements;
    }

    // Synthesize a fallback Stage element positioned above the topmost section
    const sections = this.canvasSections();
    if (sections.length === 0) {
      return rawElements;
    }

    let minX = Infinity;
    let maxX = -Infinity;
    let minY = Infinity;
    for (const sec of sections) {
      minX = Math.min(minX, sec.positionX);
      maxX = Math.max(maxX, sec.positionX + sec.width);
      minY = Math.min(minY, sec.positionY);
    }

    const stageW = Math.min(Math.max(300, (maxX - minX) * 0.6), 500);
    const stageH = 60;
    const stageX = (minX + maxX - stageW) / 2;
    const stageY = Number((minY - stageH - 30).toFixed(1));

    const syntheticStage: VenueLayoutElement = {
      elementId: 'synthetic-stage-element',
      type: 'STAGE',
      label: 'STAGE',
      geometry: {
        x: Number(stageX.toFixed(1)),
        y: stageY,
        width: stageW,
        height: stageH,
        rotationDeg: 0,
      },
      zIndex: -1,
    };

    return [syntheticStage, ...rawElements];
  });

  /**
   * Status-only customer presentation keyed by stable seat ID. Geometry lives
   * in canvasSections; live availability updates only replace these entries.
   */
  readonly customerSeatStates = computed<ReadonlyMap<string, CustomerSeatPresentation>>(() => {
    const states = new Map<string, CustomerSeatPresentation>();
    const highlight = this.highlightedCategoryKey();
    const conflicts = this.conflictingSeatIds();
    const preview = this.previewMode();

    const sectionMap = new Map<string, VenueSectionLayout>();
    const sectionColorMap = new Map<string, string>();
    const sections = this.canvasSections();
    sections.forEach((sec, idx) => {
      if (sec.sectionId) {
        sectionMap.set(sec.sectionId, sec);
        sectionColorMap.set(sec.sectionId, resolveSectionColor(sec, idx));
      }
    });

    for (const seat of this.seats()) {
      const available = seat.isActive && seat.status === 'AVAILABLE';
      const status = seat.isActive ? seat.status : 'DISABLED';
      const category = seat.categoryName || 'Standard';
      const priceText = this.formatPrice(seat.price, seat.currency);
      const sectionName = seat.sectionName ?? 'Section';
      const statusText = status.toLowerCase();

      const sec = sectionMap.get(seat.sectionId);
      const meta = (sec?.shapeMetadata ?? (seat as any).sectionShapeMetadata) as Record<
        string,
        unknown
      > | null;
      let seatVisualColor: string | null = null;
      if (meta && typeof meta['seatColors'] === 'object' && meta['seatColors'] !== null) {
        const seatColors = meta['seatColors'] as Record<string, string>;
        const k1 = `${seat.rowLabel}_${seat.seatNumber}`;
        const k2 = `${seat.gridY}_${seat.gridX}`;
        const k3 = seat.id;
        seatVisualColor = seatColors[k3] || seatColors[k2] || seatColors[k1] || null;
      }
      if (!seatVisualColor && meta && typeof meta['color'] === 'string' && meta['color']) {
        seatVisualColor = meta['color'];
      }
      if (!seatVisualColor && (seat as any).sectionColor) {
        seatVisualColor = (seat as any).sectionColor;
      }
      if (!seatVisualColor && seat.sectionId && sectionColorMap.has(seat.sectionId)) {
        seatVisualColor = sectionColorMap.get(seat.sectionId)!;
      }
      if (!seatVisualColor) {
        seatVisualColor = resolveSectionColor(null, 0);
      }

      const multiTiers =
        seat.pricingTiers && seat.pricingTiers.length > 1 ? seat.pricingTiers : null;
      let priceLabel = priceText;
      let tooltipTierText = `${category} · ${priceText}`;
      if (multiTiers) {
        const minP = Math.min(...multiTiers.map((t) => t.price));
        const tierNames = multiTiers
          .map((t) => `${t.categoryName || 'Standard'} ${this.formatPrice(t.price, t.currency)}`)
          .join(', ');
        priceLabel = `From ${this.formatPrice(minP, seat.currency)}`;
        tooltipTierText = `${priceLabel} (${tierNames})`;
      }

      const presentation: CustomerSeatPresentation = {
        status,
        color: available ? seatVisualColor : MUTED_SEAT_GRAY,
        ariaLabel: preview
          ? `${sectionName}, row ${seat.rowLabel}, seat ${seat.seatNumber}, ${statusText}`
          : `${sectionName}, row ${seat.rowLabel}, seat ${seat.seatNumber}, ${category}, ${priceLabel}, ${statusText}`,
        tooltip: preview
          ? `${sectionName} · Row ${seat.rowLabel}, Seat ${seat.seatNumber} · ${statusText}`
          : `${sectionName} · Row ${seat.rowLabel}, Seat ${seat.seatNumber} · ${tooltipTierText} · ${statusText}`,
        dimmed: highlight !== null && !seatSupportsTier(seat, highlight),
        conflicted: conflicts.has(seat.id),
      };

      if (seat.id) {
        states.set(seat.id, presentation);
      }
      states.set(`${seat.gridY}_${seat.gridX}`, presentation);
    }
    return states;
  });

  /** Top pricing-category legend: priced tiers with colors and live counts. */
  readonly categoryLegend = computed<PricingLegendTier[]>(() => {
    if (this.previewMode()) {
      return [];
    }
    // Inactive sections are absent from the unified canvas, so their seats
    // must not inflate legend counts either.
    const activeSectionIds = new Set(this.canvasSections().map((section) => section.sectionId));
    const activeSections = (this.sectionsData() ?? []).filter((section) =>
      activeSectionIds.has(section.sectionId),
    );

    // Section color mapping: sectionId -> section color from shapeMetadata / palette
    const sectionColorMap = new Map<string, string>();
    const sections = this.canvasSections();
    sections.forEach((sec, idx) => {
      if (sec.sectionId) {
        sectionColorMap.set(sec.sectionId, resolveSectionColor(sec, idx));
      }
    });

    // Count available & total seats per section
    const secAvailable = new Map<string, number>();
    const secTotal = new Map<string, number>();
    for (const seat of this.seats()) {
      if (!activeSectionIds.has(seat.sectionId)) continue;
      secTotal.set(seat.sectionId, (secTotal.get(seat.sectionId) ?? 0) + 1);
      if (seat.isActive && seat.status === 'AVAILABLE') {
        secAvailable.set(seat.sectionId, (secAvailable.get(seat.sectionId) ?? 0) + 1);
      }
    }

    const groups = new Map<string, PricingLegendTier & { order: number }>();
    let order = 0;

    // First collect all pricing tiers defined on the active sections
    for (let i = 0; i < activeSections.length; i++) {
      const sec = activeSections[i];
      const availCount = secAvailable.get(sec.sectionId) ?? 0;
      const totalCount = secTotal.get(sec.sectionId) ?? 0;
      const secColor = sectionColorMap.get(sec.sectionId) || resolveSectionColor(sec, i);

      for (const tier of sec.pricingTiers ?? []) {
        const price = Number(tier.price ?? 0);
        if (!(price > 0)) continue;
        const catName = tier.categoryName || 'Standard';
        const currency = tier.currency || 'USD';
        const key = tier.id || `${sec.sectionId}::${catName}::${price}::${currency}`;
        const tierColor = secColor;
        let group = groups.get(key);
        if (!group) {
          group = {
            key,
            categoryName: catName,
            price,
            currency,
            color: tierColor,
            availableCount: 0,
            totalCount: 0,
            order: order++,
          };
          groups.set(key, group);
        }
        group.availableCount += availCount;
        group.totalCount += totalCount;
      }
    }

    // Fallback: if sectionsData didn't have pricingTiers, collect from seats() directly
    if (groups.size === 0) {
      for (const seat of this.seats()) {
        if (!(seat.price > 0) || !activeSectionIds.has(seat.sectionId)) {
          continue;
        }
        const key = seatTierKey(seat);
        let tier = groups.get(key);
        if (!tier) {
          const secColor =
            (seat.sectionId && sectionColorMap.get(seat.sectionId)) ||
            (seat as any).sectionColor ||
            resolveSectionColor(null, 0);
          tier = {
            key,
            categoryName: seat.categoryName || 'Standard',
            price: seat.price,
            currency: seat.currency || 'USD',
            color: secColor,
            availableCount: 0,
            totalCount: 0,
            order: order++,
          };
          groups.set(key, tier);
        }
        tier.totalCount += 1;
        if (seat.isActive && seat.status === 'AVAILABLE') {
          tier.availableCount += 1;
        }
      }
    }

    return [...groups.values()].sort((a, b) => b.price - a.price || a.order - b.order);
  });

  /**
   * Grouped pricing tiers by section with section-branded headers,
   * live inventory counts, and individual tier chips.
   */
  readonly sectionLegendGroups = computed<SectionPricingLegendGroup[]>(() => {
    if (this.previewMode()) {
      return [];
    }
    const activeSectionIds = new Set(this.canvasSections().map((section) => section.sectionId));
    const activeSections = (this.sectionsData() ?? []).filter((section) =>
      activeSectionIds.has(section.sectionId),
    );

    const sectionColorMap = new Map<string, string>();
    const sections = this.canvasSections();
    sections.forEach((sec, idx) => {
      if (sec.sectionId) {
        sectionColorMap.set(sec.sectionId, resolveSectionColor(sec, idx));
      }
    });

    const secAvailable = new Map<string, number>();
    const secTotal = new Map<string, number>();
    for (const seat of this.seats()) {
      if (!activeSectionIds.has(seat.sectionId)) continue;
      secTotal.set(seat.sectionId, (secTotal.get(seat.sectionId) ?? 0) + 1);
      if (seat.isActive && seat.status === 'AVAILABLE') {
        secAvailable.set(seat.sectionId, (secAvailable.get(seat.sectionId) ?? 0) + 1);
      }
    }

    const groups: SectionPricingLegendGroup[] = [];

    for (let i = 0; i < activeSections.length; i++) {
      const sec = activeSections[i];
      const secColor = sectionColorMap.get(sec.sectionId) || resolveSectionColor(sec, i);
      const availCount = secAvailable.get(sec.sectionId) ?? 0;
      const totalCount = secTotal.get(sec.sectionId) ?? 0;

      const tiers: PricingLegendTier[] = [];
      const seenTierKeys = new Set<string>();

      for (const tier of sec.pricingTiers ?? []) {
        const price = Number(tier.price ?? 0);
        if (!(price > 0)) continue;
        const catName = tier.categoryName || 'Standard';
        const currency = tier.currency || 'USD';
        const key = tier.id || `${sec.sectionId}::${catName}::${price}::${currency}`;
        if (seenTierKeys.has(key)) continue;
        seenTierKeys.add(key);

        tiers.push({
          key,
          categoryName: catName,
          price,
          currency,
          color: secColor,
          availableCount: availCount,
          totalCount: totalCount,
        });
      }

      tiers.sort((a, b) => b.price - a.price);

      if (tiers.length > 0) {
        groups.push({
          sectionId: sec.sectionId,
          sectionName: sec.name || `Section ${i + 1}`,
          sectionColor: secColor,
          totalSeats: totalCount,
          availableSeats: availCount,
          tiers,
        });
      }
    }

    // Fallback if sectionsData had no pricingTiers: group from seats directly
    if (groups.length === 0) {
      const sectionSeatsMap = new Map<string, Seat[]>();
      for (const seat of this.seats()) {
        if (!activeSectionIds.has(seat.sectionId) || !(seat.price > 0)) continue;
        const list = sectionSeatsMap.get(seat.sectionId) ?? [];
        list.push(seat);
        sectionSeatsMap.set(seat.sectionId, list);
      }

      let idx = 0;
      for (const [secId, secSeats] of sectionSeatsMap.entries()) {
        const first = secSeats[0];
        const secColor = sectionColorMap.get(secId) || resolveSectionColor(null, idx++);
        const availCount = secSeats.filter((s) => s.isActive && s.status === 'AVAILABLE').length;
        const totalCount = secSeats.length;

        const tiersMap = new Map<string, PricingLegendTier>();
        for (const seat of secSeats) {
          const key = seatTierKey(seat);
          if (!tiersMap.has(key)) {
            tiersMap.set(key, {
              key,
              categoryName: seat.categoryName || 'Standard',
              price: seat.price,
              currency: seat.currency || 'USD',
              color: secColor,
              availableCount: 0,
              totalCount: 0,
            });
          }
          const t = tiersMap.get(key)!;
          t.totalCount += 1;
          if (seat.isActive && seat.status === 'AVAILABLE') {
            t.availableCount += 1;
          }
        }

        const tiers = [...tiersMap.values()].sort((a, b) => b.price - a.price);
        groups.push({
          sectionId: secId,
          sectionName: first?.sectionName || `Section ${idx}`,
          sectionColor: secColor,
          totalSeats: totalCount,
          availableSeats: availCount,
          tiers,
        });
      }
    }

    return groups;
  });

  readonly hasLayout = computed(() => this.canvasSections().length > 0);

  isSeatFocused(seatId: string): boolean {
    return this.effectiveActiveSeatId() === seatId;
  }

  onSeatFocusIn(seatId: string): void {
    this.activeSeatId.set(seatId);
  }

  onCanvasSeatSelected(event: CanvasSeatSelectedEvent): void {
    const seatId = event.seat.seatId;
    const seat = seatId ? this.seatById().get(seatId) : undefined;
    if (!seat) {
      return;
    }
    this.activeSeatId.set(seat.id);
    if (this.previewMode()) {
      this.liveAnnouncement.set(
        `Preview of row ${seat.rowLabel}, seat ${seat.seatNumber}. Availability and pricing are not simulated.`,
      );
      return;
    }
    this.handleSeatClick(seat);
  }

  handleSeatClick(seat: Seat): void {
    this.activeSeatId.set(seat.id);
    const selected = this.selectedSeatIds().has(seat.id);
    if (!selected && (!seat.isActive || ['SOLD', 'RESERVED', 'DISABLED'].includes(seat.status))) {
      this.liveAnnouncement.set(
        `Seat in row ${seat.rowLabel}, seat ${seat.seatNumber} is unavailable.`,
      );
      return;
    }
    if (!selected && seat.status === 'HELD') {
      this.liveAnnouncement.set(
        `Seat in row ${seat.rowLabel}, seat ${seat.seatNumber} is currently held.`,
      );
      this.snackBar.open('This seat is currently held by another customer.', 'Close', {
        duration: 3000,
        panelClass: 'snack-warning',
      });
      return;
    }
    if (!selected && this.selectedSeatIds().size >= this.maxSeats()) {
      this.liveAnnouncement.set(`Maximum limit of ${this.maxSeats()} seats reached.`);
      this.snackBar.open(`Maximum ${this.maxSeats()} seats allowed per reservation.`, 'Close', {
        duration: 3500,
        panelClass: 'snack-warning',
      });
      return;
    }

    if (selected) {
      this.liveAnnouncement.set(
        `Deselected seat in row ${seat.rowLabel}, seat ${seat.seatNumber}.`,
      );
    } else {
      this.liveAnnouncement.set(
        `Selected seat in row ${seat.rowLabel}, seat ${seat.seatNumber}, price ${this.formatPrice(seat.price, seat.currency)}.`,
      );
    }

    this.seatToggled.emit(seat);
  }

  toggleLegendCategory(key: string): void {
    const next = this.highlightedCategoryKey() === key ? null : key;
    this.highlightedCategoryKey.set(next);
    if (next === null) {
      this.liveAnnouncement.set('Category highlight cleared.');
      return;
    }
    const tier = this.categoryLegend().find((entry) => entry.key === next);
    this.liveAnnouncement.set(
      tier
        ? `Highlighting ${tier.categoryName} across the venue. ${tier.availableCount} seats available.`
        : 'Category highlighted across the venue.',
    );
  }

  isTierHighlighted(key: string): boolean {
    return this.highlightedCategoryKey() === key;
  }

  isUnavailable(seat: Seat): boolean {
    return !seat.isActive || ['SOLD', 'RESERVED', 'DISABLED'].includes(seat.status);
  }

  @HostListener('focusin', ['$event'])
  onFocusIn(event: FocusEvent): void {
    const target = event.target as HTMLElement | null;
    const seatId = target?.closest?.('.seat-item')?.getAttribute?.('data-seat-id');
    if (seatId) {
      this.activeSeatId.set(seatId);
    }
  }

  /**
   * Arrow-key navigation across the unified hall using gridX/gridY
   * (section rotation stays visual-only).
   */
  @HostListener('keydown', ['$event'])
  onKeyDown(event: KeyboardEvent): void {
    if (
      event.key !== 'ArrowUp' &&
      event.key !== 'ArrowDown' &&
      event.key !== 'ArrowLeft' &&
      event.key !== 'ArrowRight'
    ) {
      return;
    }
    const target = event.target as HTMLElement | null;
    const seatNode = target?.closest?.('.seat-item');
    if (!seatNode || !this.hostRef.nativeElement.contains(seatNode)) {
      return;
    }
    const currentId = seatNode.getAttribute('data-seat-id');
    const current = currentId ? this.seatById().get(currentId) : undefined;
    if (!current) {
      return;
    }
    event.preventDefault();
    const deltaX = event.key === 'ArrowLeft' ? -1 : event.key === 'ArrowRight' ? 1 : 0;
    const deltaY = event.key === 'ArrowUp' ? -1 : event.key === 'ArrowDown' ? 1 : 0;
    const neighbor = this.findGridNeighbor(current, deltaX, deltaY);
    if (neighbor) {
      this.activeSeatId.set(neighbor.id);
      const node = this.hostRef.nativeElement.querySelector(
        `[data-seat-id="${neighbor.id}"]`,
      ) as HTMLElement | null;
      node?.focus?.();
    }
  }

  private findGridNeighbor(current: Seat, deltaX: number, deltaY: number): Seat | null {
    let best: Seat | null = null;
    let bestScore = Number.POSITIVE_INFINITY;
    for (const candidate of this.bookableSeats()) {
      if (candidate.id === current.id) {
        continue;
      }
      const dx = candidate.gridX - current.gridX;
      const dy = candidate.gridY - current.gridY;
      if (deltaX !== 0 && Math.sign(dx) !== deltaX) {
        continue;
      }
      if (deltaY !== 0 && Math.sign(dy) !== deltaY) {
        continue;
      }
      if (deltaX !== 0 && deltaY === 0 && dx === 0) {
        continue;
      }
      if (deltaY !== 0 && deltaX === 0 && dy === 0) {
        continue;
      }
      const score = Math.abs(dx) + Math.abs(dy) * 2 + Math.abs(dx - deltaX) + Math.abs(dy - deltaY);
      if (score < bestScore) {
        bestScore = score;
        best = candidate;
      }
    }
    return best;
  }

  private sectionShells(seatsBySection: Map<string, Seat[]>): {
    key: string;
    sectionId: string;
    name: string;
    rowCount: number;
    colCount: number;
    positionX: number;
    positionY: number;
    width: number;
    height: number;
    rotationDeg: number;
    zIndex: number;
    shapeMetadata: Record<string, unknown>;
  }[] {
    const raw = (this.sectionsData() ?? []).filter((section) => section.isActive !== false);
    if (raw.length > 0) {
      // Stagger vertically only when multiple sections are ALL unpositioned at origin (0, 0)
      const allAtOrigin =
        raw.length > 1 &&
        raw.every(
          (s) =>
            (s.positionX == null || s.positionX === 0) &&
            (s.positionY == null || s.positionY === 0),
        );

      let accumulatedY = 0;
      return raw.map((section, idx) => {
        const width =
          section.width ?? (section.colCount != null ? section.colCount * GRID_UNIT : 0);
        const height =
          section.height ?? (section.rowCount != null ? section.rowCount * GRID_UNIT : 0);
        let posX = section.positionX ?? 0;
        let posY = section.positionY ?? 0;

        if (allAtOrigin) {
          posX = 0;
          posY = accumulatedY;
          accumulatedY += height + GRID_UNIT;
        }

        const rawMeta = (section.shapeMetadata as Record<string, unknown>) ?? {};
        const col = resolveSectionColor(section, idx);
        const shapeMetadata: Record<string, unknown> = {
          ...rawMeta,
          color: (rawMeta && typeof rawMeta['color'] === 'string' && rawMeta['color']) || col,
        };

        return {
          key: section.sectionId,
          sectionId: section.sectionId,
          name: section.name,
          rowCount: section.rowCount,
          colCount: section.colCount,
          positionX: posX,
          positionY: posY,
          width,
          height,
          rotationDeg: section.rotationDeg ?? 0,
          zIndex: section.zIndex ?? 0,
          shapeMetadata,
        };
      });
    }
    // Legacy fallback: derive section shells from flattened seat geometry.
    const entries = [...seatsBySection.entries()];
    const allLegacyAtOrigin =
      entries.length > 1 &&
      entries.every(([_, group]) => {
        const first = group[0];
        return (
          (first?.sectionPositionX == null || first?.sectionPositionX === 0) &&
          (first?.sectionPositionY == null || first?.sectionPositionY === 0)
        );
      });

    let legacyAccumY = 0;
    return entries.map(([sectionId, group], idx) => {
      const first = group[0];
      const maxGridX = Math.max(...group.map((seat) => seat.gridX));
      const maxGridY = Math.max(...group.map((seat) => seat.gridY));
      const width =
        first?.sectionWidth ?? (Number.isFinite(maxGridX) ? (maxGridX + 1) * GRID_UNIT : 0);
      const height =
        first?.sectionHeight ?? (Number.isFinite(maxGridY) ? (maxGridY + 1) * GRID_UNIT : 0);
      let posX = first?.sectionPositionX ?? 0;
      let posY = first?.sectionPositionY ?? 0;
      if (allLegacyAtOrigin) {
        posX = 0;
        posY = legacyAccumY;
        legacyAccumY += height + GRID_UNIT;
      }
      const rawMeta = (first?.sectionShapeMetadata as Record<string, unknown>) ?? {};
      const col =
        first?.sectionColor ||
        resolveSectionColor(
          first?.sectionShapeMetadata ? { shapeMetadata: first.sectionShapeMetadata } : null,
          idx,
        );
      const shapeMetadata: Record<string, unknown> = {
        ...rawMeta,
        color: (rawMeta && typeof rawMeta['color'] === 'string' && rawMeta['color']) || col,
      };
      return {
        key: sectionId,
        sectionId,
        name: first?.sectionName ?? 'Section',
        rowCount: Number.isFinite(maxGridY) ? maxGridY + 1 : 0,
        colCount: Number.isFinite(maxGridX) ? maxGridX + 1 : 0,
        positionX: posX,
        positionY: posY,
        width,
        height,
        rotationDeg: first?.sectionRotationDeg ?? 0,
        zIndex: first?.sectionZIndex ?? 0,
        shapeMetadata,
      };
    });
  }

  private toCanvasSeat(seat: Seat): VenueSectionSeat {
    return {
      seatId: seat.id,
      rowLabel: seat.rowLabel,
      seatNumber: seat.seatNumber,
      gridX: seat.gridX,
      gridY: seat.gridY,
      positionX: seat.positionX ?? (seat.gridX != null ? seat.gridX * GRID_UNIT : 0),
      positionY: seat.positionY ?? (seat.gridY != null ? seat.gridY * GRID_UNIT : 0),
      isActive: seat.isActive,
    };
  }

  private formatPrice(price: number, currency = 'USD'): string {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: currency || 'USD',
    }).format(price);
  }
}
