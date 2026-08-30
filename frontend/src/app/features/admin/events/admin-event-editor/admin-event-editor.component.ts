import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnInit,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { AdminEventApiService } from '../../../../services/admin-event-api.service';
import { AdminVenueApiService } from '../../../../services/admin-venue-api.service';
import { EventCategory, EventStatus } from '../../../../models/event.model';
import { VenueSummary } from '../../../../models/venue.model';
import { BannerGalleryPickerComponent } from '../banner-gallery-picker/banner-gallery-picker.component';
import { SkeletonLoaderComponent } from '../../../../shared/components/skeleton-loader/skeleton-loader.component';

@Component({
  selector: 'app-admin-event-editor',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    RouterModule,
    BannerGalleryPickerComponent,
    SkeletonLoaderComponent,
  ],
  templateUrl: './admin-event-editor.component.html',
  styleUrl: './admin-event-editor.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminEventEditorComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  private readonly adminEventApi = inject(AdminEventApiService);
  private readonly adminVenueApi = inject(AdminVenueApiService);

  readonly descTextarea = viewChild<ElementRef<HTMLTextAreaElement>>('descTextarea');

  readonly isEditMode = signal<boolean>(false);
  readonly eventId = signal<string | null>(null);
  readonly isLoading = signal<boolean>(false);
  readonly isSaving = signal<boolean>(false);
  readonly errorMessage = signal<string | null>(null);
  readonly venues = signal<VenueSummary[]>([]);
  readonly eventStatus = signal<EventStatus>('DRAFT');
  readonly bannerUrl = signal<string>('');
  readonly descriptionTab = signal<'EDIT' | 'PREVIEW'>('EDIT');

  readonly categories: { value: EventCategory; label: string }[] = [
    { value: 'CONCERT', label: 'Concert & Live Music' },
    { value: 'THEATRE', label: 'Theatre & Performing Arts' },
    { value: 'SPORTS', label: 'Sports & Arena Match' },
    { value: 'FESTIVAL', label: 'Festival & Open Air' },
    { value: 'COMEDY', label: 'Comedy & Stand-up' },
    { value: 'SYMPHONY', label: 'Symphony & Classical' },
    { value: 'OTHER', label: 'Other Special Event' },
  ];

  readonly isLocked = computed(
    () => this.eventStatus() === 'CANCELLED' || this.eventStatus() === 'COMPLETED'
  );

  readonly selectedVenue = computed(() => {
    const vId = this.eventForm.controls.venueId.value;
    return this.venues().find((v) => v.id === vId) || null;
  });

  readonly currentDescription = signal<string>('');

  readonly wordCount = computed(() => {
    const text = this.currentDescription().trim();
    if (!text) return 0;
    return text.split(/\s+/).filter(Boolean).length;
  });

  readonly formattedDescriptionPreview = computed(() => {
    const raw = this.currentDescription() || '';
    if (!raw.trim()) {
      return '<p class="text-[var(--color-text-muted)] italic">No description entered yet.</p>';
    }
    return this.renderSimpleMarkdown(raw);
  });

  readonly eventForm = this.fb.group({
    title: ['', [Validators.required, Validators.minLength(3), Validators.maxLength(150)]],
    category: ['CONCERT' as EventCategory, [Validators.required]],
    venueId: ['', [Validators.required]],
    eventDate: ['', [Validators.required]],
    description: ['', [Validators.required, Validators.minLength(10), Validators.maxLength(2000)]],
    bannerUrl: ['', [Validators.required]],
  });

  ngOnInit(): void {
    this.loadVenues();

    this.eventForm.controls.description.valueChanges.subscribe((val) => {
      this.currentDescription.set(val || '');
    });

    const id = this.route.snapshot.paramMap.get('id');
    if (id && id !== 'new') {
      this.isEditMode.set(true);
      this.eventId.set(id);
      this.loadEventDetails(id);
    }
  }

  private loadVenues(): void {
    this.adminVenueApi.getVenues({ size: 100 }).subscribe({
      next: (res) => {
        this.venues.set(res.content || []);
      },
      error: () => {
        this.venues.set([]);
      },
    });
  }

  private loadEventDetails(id: string): void {
    this.isLoading.set(true);
    this.adminEventApi.getEventById(id).subscribe({
      next: (event) => {
        this.eventStatus.set(event.status);
        this.bannerUrl.set(event.bannerUrl || '');
        this.currentDescription.set(event.description || '');

        const localDateString = this.formatDateForInput(event.eventDate);

        this.eventForm.patchValue({
          title: event.title,
          category: event.category,
          venueId: event.venueId,
          eventDate: localDateString,
          description: event.description,
          bannerUrl: event.bannerUrl,
        });

        if (this.isLocked()) {
          this.eventForm.disable();
        }

        this.isLoading.set(false);
      },
      error: (err) => {
        this.errorMessage.set(err?.error?.message || 'Failed to load event details.');
        this.isLoading.set(false);
      },
    });
  }

  private formatDateForInput(isoString: string): string {
    if (!isoString) return '';
    try {
      const d = new Date(isoString);
      if (isNaN(d.getTime())) return '';
      const pad = (n: number) => n.toString().padStart(2, '0');
      const year = d.getFullYear();
      const month = pad(d.getMonth() + 1);
      const day = pad(d.getDate());
      const hours = pad(d.getHours());
      const mins = pad(d.getMinutes());
      return `${year}-${month}-${day}T${hours}:${mins}`;
    } catch {
      return '';
    }
  }

  onBannerSelected(url: string): void {
    this.bannerUrl.set(url);
    this.eventForm.patchValue({ bannerUrl: url });
    this.eventForm.controls.bannerUrl.markAsDirty();
  }

  // Markdown formatting helpers
  insertFormatting(prefix: string, suffix = ''): void {
    if (this.isLocked()) return;
    const textarea = this.descTextarea()?.nativeElement;
    const current = this.eventForm.controls.description.value || '';

    if (!textarea) {
      this.eventForm.patchValue({ description: current + prefix + 'text' + suffix });
      return;
    }

    const start = textarea.selectionStart;
    const end = textarea.selectionEnd;
    const selected = current.substring(start, end) || 'text';
    const replacement = prefix + selected + suffix;

    const updated = current.substring(0, start) + replacement + current.substring(end);
    this.eventForm.patchValue({ description: updated });
    this.eventForm.controls.description.markAsDirty();

    setTimeout(() => {
      textarea.focus();
      textarea.setSelectionRange(start + prefix.length, start + prefix.length + selected.length);
    }, 0);
  }

  insertTemplate(type: 'SCHEDULE' | 'LINEUP' | 'NOTICES'): void {
    if (this.isLocked()) return;
    let template = '';
    if (type === 'SCHEDULE') {
      template = `\n\n### 🕒 Schedule & Door Times\n- **18:00** — Doors Open & Welcome\n- **19:15** — Opening Act\n- **20:00** — Main Performance\n- **22:00** — Event Concludes`;
    } else if (type === 'LINEUP') {
      template = `\n\n### 🎵 Artist Lineup & Performers\n- **Headliner:** Feature Orchestra & Guest Vocalist\n- **Conductor:** International Maestro\n- **Special Guests:** Symphony Brass Quintet`;
    } else if (type === 'NOTICES') {
      template = `\n\n### ℹ️ Important Attendee Notices\n- **Age Policy:** All ages welcome. Under 14 must be accompanied by an adult.\n- **Entry Requirements:** Digital mobile ticket QR code required at turnstiles.\n- **Late Arrivals:** Latecomers will be seated at the first interval.`;
    }

    const current = this.eventForm.controls.description.value || '';
    const updated = current + template;
    this.eventForm.patchValue({ description: updated.trim() });
    this.eventForm.controls.description.markAsDirty();
  }

  private renderSimpleMarkdown(md: string): string {
    const escapeHtml = (text: string) =>
      text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');

    const lines = md.split('\n');
    const out: string[] = [];
    let inList = false;

    for (const rawLine of lines) {
      const line = escapeHtml(rawLine.trim());

      if (line.startsWith('### ')) {
        if (inList) { out.push('</ul>'); inList = false; }
        out.push(`<h3 class="text-sm font-bold text-indigo-500 mt-3 mb-1">${line.substring(4)}</h3>`);
      } else if (line.startsWith('## ')) {
        if (inList) { out.push('</ul>'); inList = false; }
        out.push(`<h2 class="text-base font-extrabold text-[var(--color-text-primary)] mt-4 mb-1.5">${line.substring(3)}</h2>`);
      } else if (line.startsWith('- ') || line.startsWith('* ')) {
        if (!inList) { out.push('<ul class="list-disc list-inside space-y-1 my-1 text-xs text-[var(--color-text-secondary)]">'); inList = true; }
        const formatted = this.applyInlineFormatting(line.substring(2));
        out.push(`<li>${formatted}</li>`);
      } else if (line.startsWith('&gt; ')) {
        if (inList) { out.push('</ul>'); inList = false; }
        out.push(`<blockquote class="border-l-2 border-indigo-500 pl-3 my-2 text-xs italic text-[var(--color-text-muted)]">${line.substring(5)}</blockquote>`);
      } else if (line === '---') {
        if (inList) { out.push('</ul>'); inList = false; }
        out.push('<hr class="border-[var(--color-border)] my-3"/>');
      } else if (line === '') {
        if (inList) { out.push('</ul>'); inList = false; }
        out.push('<div class="h-2"></div>');
      } else {
        if (inList) { out.push('</ul>'); inList = false; }
        out.push(`<p class="text-xs leading-relaxed text-[var(--color-text-secondary)] mb-1">${this.applyInlineFormatting(line)}</p>`);
      }
    }
    if (inList) out.push('</ul>');
    return out.join('');
  }

  private applyInlineFormatting(text: string): string {
    return text
      .replace(/\*\*(.*?)\*\*/g, '<strong class="font-bold text-[var(--color-text-primary)]">$1</strong>')
      .replace(/\*(.*?)\*/g, '<em class="italic">$1</em>')
      .replace(/`([^`]+)`/g, '<code class="px-1.5 py-0.5 rounded bg-[var(--color-canvas)] text-[11px] font-mono text-indigo-400">$1</code>');
  }

  onSubmit(): void {
    if (this.isLocked() || this.eventForm.invalid) {
      this.eventForm.markAllAsTouched();
      return;
    }

    this.isSaving.set(true);
    this.errorMessage.set(null);

    const formVal = this.eventForm.getRawValue();
    const isoDate = new Date(formVal.eventDate!).toISOString();

    if (this.isEditMode() && this.eventId()) {
      const updatePayload = {
        title: formVal.title!,
        description: formVal.description!,
        category: formVal.category!,
        bannerUrl: formVal.bannerUrl!,
        eventDate: isoDate,
      };

      this.adminEventApi.updateEvent(this.eventId()!, updatePayload).subscribe({
        next: (event) => {
          this.isSaving.set(false);
          this.snackBar.open(`Event "${event.title}" updated successfully!`, 'Close', {
            duration: 4000,
          });
          this.router.navigate(['/admin/events']);
        },
        error: (err) => {
          this.isSaving.set(false);
          this.errorMessage.set(err?.error?.message || 'Failed to update event.');
        },
      });
    } else {
      const createPayload = {
        title: formVal.title!,
        description: formVal.description!,
        category: formVal.category!,
        bannerUrl: formVal.bannerUrl!,
        eventDate: isoDate,
        venueId: formVal.venueId!,
      };

      this.adminEventApi.createEvent(createPayload).subscribe({
        next: (created) => {
          this.isSaving.set(false);
          this.snackBar.open(
            `Event "${created.title}" created! Now configure section pricing.`,
            'Close',
            { duration: 5000 }
          );
          this.router.navigate(['/admin/events', created.id, 'pricing']);
        },
        error: (err) => {
          this.isSaving.set(false);
          this.errorMessage.set(err?.error?.message || 'Failed to create event.');
        },
      });
    }
  }
}
