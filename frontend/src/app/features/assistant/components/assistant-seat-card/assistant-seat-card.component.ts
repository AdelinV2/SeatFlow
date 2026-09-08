import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { minorToMajor, SeatSetAssistantCard } from '../../../../models/assistant.model';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';

/**
 * Renders an authoritative SEAT_SET suggestion card.
 * "Seats together" appears ONLY when backend `contiguous === true`.
 */
@Component({
  selector: 'app-assistant-seat-card',
  standalone: true,
  imports: [CurrencyFormatPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <article class="assistant-card" aria-label="Seat suggestion">
      <p class="assistant-card__eyebrow">Seat suggestion</p>
      @if (card().sectionSummary) {
        <p class="assistant-card__title">{{ card().sectionSummary }}</p>
      }
      <p class="assistant-card__meta">
        {{ card().seatLabels.length }} seat(s): {{ card().seatLabels.join(', ') || '—' }}
      </p>
      @if (card().contiguous) {
        <p class="assistant-card__together">Seats together</p>
      }
      <p class="assistant-card__meta">
        Total: {{ totalMajor() | sfCurrency: currency() }}
      </p>
      @if (card().reasons.length > 0) {
        <ul class="assistant-card__reasons">
          @for (reason of card().reasons; track reason) {
            <li>{{ reason }}</li>
          }
        </ul>
      }
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
        font-size: 0.9rem;
        font-weight: 700;
        margin: 0;
        color: var(--color-text-primary);
      }
      .assistant-card__meta {
        font-size: 0.8rem;
        margin: 0;
        color: var(--color-text-secondary);
      }
      .assistant-card__together {
        font-size: 0.78rem;
        font-weight: 700;
        margin: 0;
        color: var(--color-text-primary);
      }
      .assistant-card__reasons {
        margin: 0.2rem 0 0;
        padding-left: 1.1rem;
        font-size: 0.78rem;
        color: var(--color-text-secondary);
      }
    `,
  ],
})
export class AssistantSeatCardComponent {
  readonly card = input.required<SeatSetAssistantCard>();

  readonly totalMajor = computed(() => minorToMajor(this.card().totalPriceMinor));
  readonly currency = computed(() => this.card().currency || 'RON');
}
