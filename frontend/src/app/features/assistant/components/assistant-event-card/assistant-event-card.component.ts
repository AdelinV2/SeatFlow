import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { Router } from '@angular/router';
import { EventAssistantCard } from '../../../../models/assistant.model';

/** Renders an authoritative EVENT card with navigation to Event Detail. */
@Component({
  selector: 'app-assistant-event-card',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <article class="assistant-card" aria-label="Event suggestion">
      <p class="assistant-card__eyebrow">Event suggestion</p>
      <h4 class="assistant-card__title">{{ card().title || 'Untitled event' }}</h4>
      @if (card().category) {
        <p class="assistant-card__meta">Category: {{ card().category }}</p>
      }
      @if (card().venueSummary) {
        <p class="assistant-card__meta">{{ card().venueSummary }}</p>
      }
      <button type="button" class="assistant-card__action" (click)="openEvent()">
        View event details
      </button>
    </article>
  `,
  styles: [
    `
      .assistant-card {
        border: 1px solid var(--color-border);
        border-radius: 0.9rem;
        padding: 0.8rem 0.9rem;
        background: var(--color-surface-elevated);
        display: flex;
        flex-direction: column;
        gap: 0.35rem;
      }
      .assistant-card__eyebrow {
        font-size: 0.68rem;
        font-weight: 800;
        letter-spacing: 0.08em;
        text-transform: uppercase;
        color: var(--color-text-muted);
        margin: 0;
      }
      .assistant-card__title {
        font-size: 0.95rem;
        font-weight: 700;
        margin: 0;
        color: var(--color-text-primary);
      }
      .assistant-card__meta {
        font-size: 0.8rem;
        margin: 0;
        color: var(--color-text-secondary);
      }
      .assistant-card__action {
        align-self: flex-start;
        margin-top: 0.3rem;
        border-radius: 999px;
        padding: 0.45rem 0.9rem;
        font-size: 0.8rem;
        font-weight: 700;
        background: var(--color-primary, #4f46e5);
        color: #fff;
        border: none;
        cursor: pointer;
      }
    `,
  ],
})
export class AssistantEventCardComponent {
  private readonly router = inject(Router);
  readonly card = input.required<EventAssistantCard>();

  openEvent(): void {
    void this.router.navigate(['/events', this.card().eventId]);
  }
}
