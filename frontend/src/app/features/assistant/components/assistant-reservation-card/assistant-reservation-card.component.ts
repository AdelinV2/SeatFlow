import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { ReservationCreatedAssistantCard } from '../../../../models/assistant.model';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { AssistantStore } from '../../../../services/assistant-store.service';
import { HoldCountdownComponent } from '../../../../shared/components/hold-countdown/hold-countdown.component';

/**
 * Renders the RESERVATION_CREATED confirmed-hold view.
 * Built ONLY from the authoritative 201 confirm payload: hold state, seats,
 * total, countdown from `expiresAt`, and checkout navigation.
 */
@Component({
  selector: 'app-assistant-reservation-card',
  standalone: true,
  imports: [CurrencyFormatPipe, HoldCountdownComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <article class="reservation-card" aria-label="Reservation hold confirmed">
      <p class="reservation-card__eyebrow">Reservation hold confirmed</p>
      <p class="reservation-card__meta">
        {{ card().seatLabels.length }} seat(s) held: {{ card().seatLabels.join(', ') || '—' }}
      </p>
      <p class="reservation-card__meta">
        Total: {{ card().totalAmountMajor | sfCurrency: card().currency }}
      </p>
      <app-hold-countdown [expiresAt]="card().expiresAt" />
      <button type="button" class="reservation-card__action" (click)="goToCheckout()">
        Continue to checkout
      </button>
    </article>
  `,
  styles: [
    `
      .reservation-card {
        border: 2px solid var(--color-success, #059669);
        border-radius: 0.9rem;
        padding: 0.8rem 0.9rem;
        background: var(--color-surface-elevated);
        display: flex;
        flex-direction: column;
        gap: 0.4rem;
      }
      .reservation-card__eyebrow {
        font-size: 0.68rem;
        font-weight: 800;
        letter-spacing: 0.08em;
        text-transform: uppercase;
        color: var(--color-text-muted);
        margin: 0;
      }
      .reservation-card__meta {
        font-size: 0.82rem;
        margin: 0;
        color: var(--color-text-secondary);
      }
      .reservation-card__action {
        align-self: flex-start;
        margin-top: 0.3rem;
        border-radius: 999px;
        padding: 0.5rem 1rem;
        font-size: 0.82rem;
        font-weight: 800;
        background: var(--color-success, #059669);
        color: #fff;
        border: none;
        cursor: pointer;
      }
    `,
  ],
})
export class AssistantReservationCardComponent {
  private readonly store = inject(AssistantStore);
  readonly card = input.required<ReservationCreatedAssistantCard>();

  goToCheckout(): void {
    this.store.goToCheckout(this.card().reservationId);
  }
}
