import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { minorToMajor, ProposalAssistantCard } from '../../../../models/assistant.model';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { AssistantStore } from '../../../../services/assistant-store.service';

/**
 * Renders a RESERVATION_PROPOSAL card: explicitly NOT a hold until the user
 * presses "Confirm reservation". Confirm sends only the proposal ID in the URL
 * with an empty body; typing "yes" in the composer never confirms.
 */
@Component({
  selector: 'app-assistant-proposal-card',
  standalone: true,
  imports: [CurrencyFormatPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <article class="proposal-card" aria-label="Reservation proposal, seats not held">
      <p class="proposal-card__eyebrow">Reservation proposal — not a hold</p>
      <p class="proposal-card__notice">These seats are not held yet.</p>
      @if (card().sectionSummary) {
        <p class="proposal-card__title">{{ card().sectionSummary }}</p>
      }
      <p class="proposal-card__meta">
        {{ card().seatLabels.length }} seat(s): {{ card().seatLabels.join(', ') || '—' }}
      </p>
      @if (card().contiguous) {
        <p class="proposal-card__together">Seats together</p>
      }
      <p class="proposal-card__meta">
        Total: {{ totalMajor() | sfCurrency: currency() }}
      </p>
      @if (status()?.message) {
        <p class="proposal-card__status" role="status">{{ status()?.message }}</p>
      }
      <div class="proposal-card__actions">
        <button
          type="button"
          class="proposal-card__confirm"
          [disabled]="confirmDisabled()"
          (click)="confirm()"
        >
          @if (confirming()) {
            <span>Confirming…</span>
          } @else {
            <span>Confirm reservation</span>
          }
        </button>
        @if (status()?.retrySafe) {
          <button
            type="button"
            class="proposal-card__secondary"
            [disabled]="confirming()"
            (click)="retry()"
          >
            Retry confirmation
          </button>
        }
        <button
          type="button"
          class="proposal-card__secondary"
          [disabled]="confirming()"
          (click)="dismiss()"
        >
          Find different seats
        </button>
      </div>
    </article>
  `,
  styles: [
    `
      .proposal-card {
        border: 2px solid var(--color-warning, #d97706);
        border-radius: 0.9rem;
        padding: 0.8rem 0.9rem;
        background: var(--color-surface-elevated);
        display: flex;
        flex-direction: column;
        gap: 0.35rem;
      }
      .proposal-card__eyebrow {
        font-size: 0.68rem;
        font-weight: 800;
        letter-spacing: 0.08em;
        text-transform: uppercase;
        color: var(--color-text-muted);
        margin: 0;
      }
      .proposal-card__notice {
        font-size: 0.85rem;
        font-weight: 800;
        margin: 0;
        color: var(--color-text-primary);
      }
      .proposal-card__title {
        font-size: 0.9rem;
        font-weight: 700;
        margin: 0;
        color: var(--color-text-primary);
      }
      .proposal-card__meta {
        font-size: 0.8rem;
        margin: 0;
        color: var(--color-text-secondary);
      }
      .proposal-card__together {
        font-size: 0.78rem;
        font-weight: 700;
        margin: 0;
        color: var(--color-text-primary);
      }
      .proposal-card__status {
        font-size: 0.8rem;
        margin: 0;
        color: var(--color-text-secondary);
      }
      .proposal-card__actions {
        display: flex;
        flex-wrap: wrap;
        gap: 0.5rem;
        margin-top: 0.3rem;
      }
      .proposal-card__confirm {
        border-radius: 999px;
        padding: 0.5rem 1rem;
        font-size: 0.82rem;
        font-weight: 800;
        background: var(--color-primary, #4f46e5);
        color: #fff;
        border: none;
        cursor: pointer;
      }
      .proposal-card__confirm:disabled {
        opacity: 0.55;
        cursor: not-allowed;
      }
      .proposal-card__secondary {
        border-radius: 999px;
        padding: 0.5rem 1rem;
        font-size: 0.8rem;
        font-weight: 600;
        background: transparent;
        color: var(--color-text-secondary);
        border: 1px solid var(--color-border);
        cursor: pointer;
      }
    `,
  ],
})
export class AssistantProposalCardComponent {
  private readonly store = inject(AssistantStore);
  readonly card = input.required<ProposalAssistantCard>();

  readonly status = computed(() => this.store.proposalStatus(this.card().proposalId));
  readonly confirming = computed(() => this.store.isConfirming(this.card().proposalId));
  readonly confirmDisabled = computed(() => this.confirming() || (this.status()?.disabled ?? false));
  readonly totalMajor = computed(() => minorToMajor(this.card().totalPriceMinor));
  readonly currency = computed(() => this.card().currency || 'RON');

  confirm(): void {
    this.store.confirmProposal(this.card().proposalId);
  }

  retry(): void {
    this.store.retryConfirm(this.card().proposalId);
  }

  dismiss(): void {
    this.store.dismissProposal(this.card().proposalId);
  }
}
