import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { AdminEventApiService } from '../../../../services/admin-event-api.service';
import { AdminVenueApiService } from '../../../../services/admin-venue-api.service';
import { EventDetail } from '../../../../models/event.model';
import { PricingTierConfig } from '../../../../models/admin-event.model';
import { VenueLayout, VenueSectionLayout } from '../../../../models/venue.model';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { SkeletonLoaderComponent } from '../../../../shared/components/skeleton-loader/skeleton-loader.component';

export interface CustomerTierItem {
  id: string;
  categoryName: string;
  price: number;
}

export interface SectionPricingRow {
  sectionId: string;
  name: string;
  rowCount: number;
  colCount: number;
  totalSeats: number;
  tiers: CustomerTierItem[];
}

export interface CustomerCategoryTemplate {
  name: string;
  defaultPrice: number;
  label: string;
  badgeClass: string;
}

export interface CurrencyOption {
  code: string;
  name: string;
}

@Component({
  selector: 'app-admin-pricing-manager',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule, CurrencyFormatPipe, SkeletonLoaderComponent],
  templateUrl: './admin-pricing-manager.component.html',
  styleUrl: './admin-pricing-manager.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminPricingManagerComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  private readonly adminEventApi = inject(AdminEventApiService);
  private readonly adminVenueApi = inject(AdminVenueApiService);

  readonly eventId = signal<string | null>(null);
  readonly event = signal<EventDetail | null>(null);
  readonly venueLayout = signal<VenueLayout | null>(null);
  readonly sectionTiers = signal<Map<string, CustomerTierItem[]>>(new Map());
  readonly uniformPriceInput = signal<number>(20);

  readonly isLoading = signal<boolean>(true);
  readonly isSaving = signal<boolean>(false);
  readonly isPublishing = signal<boolean>(false);
  readonly errorMessage = signal<string | null>(null);
  readonly currency = signal<string>('USD');

  readonly currencyOptions: CurrencyOption[] = [
    { code: 'USD', name: 'US Dollar' },
    { code: 'EUR', name: 'Euro' },
    { code: 'GBP', name: 'British Pound' },
    { code: 'RON', name: 'Romanian Leu' },
    { code: 'CHF', name: 'Swiss Franc' },
    { code: 'CAD', name: 'Canadian Dollar' },
    { code: 'AUD', name: 'Australian Dollar' },
    { code: 'JPY', name: 'Japanese Yen' },
  ];

  readonly categoryTemplates: CustomerCategoryTemplate[] = [
    { name: 'Standard', defaultPrice: 20.0, label: 'Standard Ticket', badgeClass: 'bg-indigo-500/10 text-indigo-600 border-indigo-500/20' },
    { name: 'Student', defaultPrice: 12.0, label: 'Student Discount', badgeClass: 'bg-emerald-500/10 text-emerald-600 border-emerald-500/20' },
    { name: 'Pensioner', defaultPrice: 15.0, label: 'Pensioner / Senior', badgeClass: 'bg-amber-500/10 text-amber-600 border-amber-500/20' },
    { name: 'VIP', defaultPrice: 35.0, label: 'VIP Premium', badgeClass: 'bg-purple-500/10 text-purple-600 border-purple-500/20' },
    { name: 'Child', defaultPrice: 10.0, label: 'Child (Under 12)', badgeClass: 'bg-cyan-500/10 text-cyan-600 border-cyan-500/20' },
  ];

  readonly isLocked = computed(() => {
    const status = this.event()?.status;
    return status === 'CANCELLED' || status === 'COMPLETED';
  });

  readonly sections = computed<SectionPricingRow[]>(() => {
    const layout = this.venueLayout();
    if (!layout || !layout.sections) return [];

    const tiersMap = this.sectionTiers();
    return layout.sections.map((sec: VenueSectionLayout) => {
      const activeSeats = sec.seats
        ? sec.seats.filter((s) => s.isActive).length
        : sec.rowCount * sec.colCount;

      const currentTiers = tiersMap.get(sec.sectionId) || [
        { id: `tier-${sec.sectionId}-standard`, categoryName: 'Standard', price: 20.0 },
      ];

      return {
        sectionId: sec.sectionId,
        name: sec.name,
        rowCount: sec.rowCount,
        colCount: sec.colCount,
        totalSeats: activeSeats,
        tiers: currentTiers,
      };
    });
  });

  readonly allTiersFlat = computed<CustomerTierItem[]>(() => {
    const list = this.sections();
    return list.flatMap((s) => s.tiers);
  });

  readonly priceStats = computed(() => {
    const all = this.allTiersFlat();
    if (all.length === 0) {
      return { min: 0, max: 0, avg: 0, totalCapacity: 0, tierCount: 0 };
    }

    const prices = all.map((t) => t.price);
    const min = Math.min(...prices);
    const max = Math.max(...prices);
    const sum = prices.reduce((acc, p) => acc + p, 0);
    const avg = prices.length > 0 ? sum / prices.length : 0;
    const totalCapacity = this.sections().reduce((acc, s) => acc + s.totalSeats, 0);

    return { min, max, avg, totalCapacity, tierCount: all.length };
  });

  readonly isFormValid = computed(() => {
    const rows = this.sections();
    if (rows.length === 0) return false;

    for (const row of rows) {
      if (!row.tiers || row.tiers.length === 0) return false;
      const seenNames = new Set<string>();
      for (const tier of row.tiers) {
        if (!tier.categoryName || !tier.categoryName.trim()) return false;
        if (typeof tier.price !== 'number' || isNaN(tier.price) || tier.price < 0) return false;
        const normalized = tier.categoryName.trim().toLowerCase();
        if (seenNames.has(normalized)) return false; // Duplicate category name in same section
        seenNames.add(normalized);
      }
    }
    return true;
  });

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.eventId.set(id);
      this.loadEventAndVenue(id);
    } else {
      this.errorMessage.set('Event ID is required.');
      this.isLoading.set(false);
    }
  }

  private loadEventAndVenue(eventId: string): void {
    this.isLoading.set(true);
    this.adminEventApi.getEventById(eventId).subscribe({
      next: (event) => {
        this.event.set(event);

        // Pre-populate multi-tier customer prices from existing event pricing tiers
        const tiersMap = new Map<string, CustomerTierItem[]>();
        if (event.pricingTiers && event.pricingTiers.length > 0) {
          for (const tier of event.pricingTiers) {
            const list = tiersMap.get(tier.sectionId) || [];
            list.push({
              id: tier.id || `tier-${tier.sectionId}-${tier.categoryName || 'Standard'}-${Math.random()}`,
              categoryName: tier.categoryName || 'Standard',
              price: tier.price,
            });
            tiersMap.set(tier.sectionId, list);
            if (tier.currency) {
              this.currency.set(tier.currency);
            }
          }
        }

        // Load venue layout to get all sections
        this.adminVenueApi.getVenueLayout(event.venueId).subscribe({
          next: (layout) => {
            this.venueLayout.set(layout);

            // If some sections don't have tiers yet, create default Standard tier at $20
            if (layout.sections) {
              for (const sec of layout.sections) {
                if (!tiersMap.has(sec.sectionId) || tiersMap.get(sec.sectionId)!.length === 0) {
                  tiersMap.set(sec.sectionId, [
                    { id: `tier-${sec.sectionId}-standard`, categoryName: 'Standard', price: 20.0 },
                  ]);
                }
              }
            }
            this.sectionTiers.set(tiersMap);
            this.isLoading.set(false);
          },
          error: (err) => {
            this.errorMessage.set(err?.error?.message || 'Failed to load venue sections layout.');
            this.isLoading.set(false);
          },
        });
      },
      error: (err) => {
        this.errorMessage.set(err?.error?.message || 'Failed to load event details.');
        this.isLoading.set(false);
      },
    });
  }

  onTierPriceChange(sectionId: string, tierId: string, value: number | string): void {
    if (this.isLocked()) return;
    const num = typeof value === 'string' ? parseFloat(value) : value;
    const cleanNum = isNaN(num) ? 0 : Math.max(0, num);

    this.sectionTiers.update((curr) => {
      const updated = new Map(curr);
      const list = updated.get(sectionId) ? [...updated.get(sectionId)!] : [];
      const index = list.findIndex((t) => t.id === tierId);
      if (index !== -1) {
        list[index] = { ...list[index], price: cleanNum };
        updated.set(sectionId, list);
      }
      return updated;
    });
  }

  onTierCategoryNameChange(sectionId: string, tierId: string, name: string): void {
    if (this.isLocked()) return;
    this.sectionTiers.update((curr) => {
      const updated = new Map(curr);
      const list = updated.get(sectionId) ? [...updated.get(sectionId)!] : [];
      const index = list.findIndex((t) => t.id === tierId);
      if (index !== -1) {
        list[index] = { ...list[index], categoryName: name };
        updated.set(sectionId, list);
      }
      return updated;
    });
  }

  hasTemplateTier(sectionId: string, template: CustomerCategoryTemplate): boolean {
    const tiers = this.sectionTiers().get(sectionId) || [];
    return tiers.some((tier) => this.isTemplateCategoryName(tier.categoryName, template.name));
  }

  allSectionsHaveTemplate(template: CustomerCategoryTemplate): boolean {
    const sections = this.venueLayout()?.sections;
    return !!sections?.length && sections.every((section) => this.hasTemplateTier(section.sectionId, template));
  }

  addTierToSection(sectionId: string, template?: CustomerCategoryTemplate): void {
    if (this.isLocked()) return;
    if (template && this.hasTemplateTier(sectionId, template)) return;

    const catName = template ? template.name : 'Custom Tier';
    const price = template ? template.defaultPrice : 20.0;

    this.sectionTiers.update((curr) => {
      const updated = new Map(curr);
      const list = updated.get(sectionId) ? [...updated.get(sectionId)!] : [];

      list.push({
        id: this.createTierId(sectionId),
        categoryName: template ? catName : this.getUniqueTierName(list, catName),
        price,
      });
      updated.set(sectionId, list);
      return updated;
    });
  }

  removeTierFromSection(sectionId: string, tierId: string): void {
    if (this.isLocked()) return;
    this.sectionTiers.update((curr) => {
      const updated = new Map(curr);
      const list = updated.get(sectionId) ? [...updated.get(sectionId)!] : [];
      if (list.length <= 1) {
        this.snackBar.open('Each section must have at least one pricing tier.', 'Close', { duration: 3000 });
        return curr;
      }
      const filtered = list.filter((t) => t.id !== tierId);
      updated.set(sectionId, filtered);
      return updated;
    });
  }

  // Bulk Actions across all venue sections
  applyUniformStandardPrice(): void {
    if (this.isLocked()) return;
    const price = Math.max(0, this.uniformPriceInput() || 20);
    const layout = this.venueLayout();
    if (!layout?.sections) return;

    this.sectionTiers.update((curr) => {
      const updated = new Map(curr);
      for (const sec of layout.sections) {
        const list = updated.get(sec.sectionId) ? [...updated.get(sec.sectionId)!] : [];
        const stdTier = list.find((t) => t.categoryName.toLowerCase() === 'standard');
        if (stdTier) {
          stdTier.price = price;
        } else if (list.length > 0) {
          list[0].price = price;
        } else {
          list.push({ id: `tier-${sec.sectionId}-std`, categoryName: 'Standard', price });
        }
        updated.set(sec.sectionId, list);
      }
      return updated;
    });

    this.snackBar.open(`Applied ${this.currency()} ${price.toFixed(2)} standard price across all sections.`, 'Close', {
      duration: 3000,
    });
  }

  addTemplateToAllSections(template: CustomerCategoryTemplate): void {
    if (this.isLocked()) return;
    const layout = this.venueLayout();
    if (!layout?.sections) return;
    let addedCount = 0;

    this.sectionTiers.update((curr) => {
      const updated = new Map(curr);
      for (const sec of layout.sections) {
        const list = updated.get(sec.sectionId) ? [...updated.get(sec.sectionId)!] : [];
        if (list.some((tier) => this.isTemplateCategoryName(tier.categoryName, template.name))) {
          continue;
        }

        list.push({
          id: this.createTierId(sec.sectionId),
          categoryName: template.name,
          price: template.defaultPrice,
        });
        updated.set(sec.sectionId, list);
        addedCount++;
      }
      return updated;
    });

    const message = addedCount > 0
      ? `Added "${template.name}" tier (${this.currency()} ${template.defaultPrice.toFixed(2)}) to ${addedCount === 1 ? '1 section' : `${addedCount} sections`}.`
      : `All sections already have a "${template.name}" tier.`;
    this.snackBar.open(message, 'Close', {
      duration: 3000,
    });
  }

  private isTemplateCategoryName(categoryName: string, templateName: string): boolean {
    const normalizedCategory = categoryName.trim().toLowerCase();
    const normalizedTemplate = templateName.trim().toLowerCase();
    if (normalizedCategory === normalizedTemplate) return true;

    const generatedSuffix = normalizedCategory.slice(normalizedTemplate.length).trim();
    return normalizedCategory.startsWith(`${normalizedTemplate} `) && /^\d+$/.test(generatedSuffix);
  }

  private getUniqueTierName(tiers: CustomerTierItem[], baseName: string): string {
    let finalName = baseName;
    let counter = 2;
    while (tiers.some((tier) => tier.categoryName.toLowerCase() === finalName.toLowerCase())) {
      finalName = `${baseName} ${counter++}`;
    }
    return finalName;
  }

  private createTierId(sectionId: string): string {
    return `tier-${sectionId}-${Date.now()}-${Math.random().toString(36).substring(2, 6)}`;
  }

  savePricing(andPublish = false): void {
    if (this.isLocked() || !this.isFormValid() || !this.eventId()) return;

    this.isSaving.set(true);
    this.errorMessage.set(null);

    const pricingTiers: PricingTierConfig[] = [];
    for (const sec of this.sections()) {
      for (const tier of sec.tiers) {
        pricingTiers.push({
          sectionId: sec.sectionId,
          categoryName: tier.categoryName.trim(),
          price: Number(tier.price.toFixed(2)),
          currency: this.currency(),
        });
      }
    }

    this.adminEventApi.configurePricing(this.eventId()!, { pricingTiers }).subscribe({
      next: () => {
        this.isSaving.set(false);
        this.snackBar.open('Section pricing matrix updated successfully!', 'Close', {
          duration: 4000,
        });

        // Update local event pricing tiers
        this.event.update((curr) => {
          if (!curr) return null;
          return {
            ...curr,
            pricingTiers: pricingTiers.map((t) => ({
              sectionId: t.sectionId,
              categoryName: t.categoryName,
              price: t.price,
              currency: t.currency,
            })),
          };
        });

        if (andPublish) {
          this.publishEvent();
        }
      },
      error: (err) => {
        this.isSaving.set(false);
        this.errorMessage.set(err?.error?.message || 'Failed to update section pricing.');
      },
    });
  }

  publishEvent(): void {
    if (!this.eventId() || this.isLocked()) return;

    this.isPublishing.set(true);
    this.adminEventApi.updateEvent(this.eventId()!, { status: 'PUBLISHED' }).subscribe({
      next: (updated) => {
        this.isPublishing.set(false);
        this.event.set(updated);
        this.snackBar.open(`Event "${updated.title}" is now published and live!`, 'Close', {
          duration: 4000,
        });
        this.router.navigate(['/admin/events']);
      },
      error: (err) => {
        this.isPublishing.set(false);
        this.snackBar.open(
          err?.error?.message || 'Failed to publish event.',
          'Close',
          { duration: 4000 }
        );
      },
    });
  }
}
