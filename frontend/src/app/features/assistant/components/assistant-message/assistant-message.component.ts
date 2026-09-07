import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { AssistantUiMessage } from '../../../../models/assistant.model';
import { MarkdownFormatPipe } from '../../../../shared/pipes/markdown-format.pipe';
import { AssistantEventCardComponent } from '../assistant-event-card/assistant-event-card.component';
import { AssistantSessionCardComponent } from '../assistant-session-card/assistant-session-card.component';
import { AssistantSeatCardComponent } from '../assistant-seat-card/assistant-seat-card.component';
import { AssistantProposalCardComponent } from '../assistant-proposal-card/assistant-proposal-card.component';
import { AssistantReservationCardComponent } from '../assistant-reservation-card/assistant-reservation-card.component';
import { AssistantInfoCardComponent } from '../assistant-info-card/assistant-info-card.component';

/**
 * Renders one thread entry: sanitized markdown/plain text plus structured cards.
 * Assistant text uses the shared sanitized `sfMarkdown` pipe only — never raw
 * innerHTML, tool JSON, reasoning, or stack traces (backend never sends them).
 */
@Component({
  selector: 'app-assistant-message',
  standalone: true,
  imports: [
    MarkdownFormatPipe,
    AssistantEventCardComponent,
    AssistantSessionCardComponent,
    AssistantSeatCardComponent,
    AssistantProposalCardComponent,
    AssistantReservationCardComponent,
    AssistantInfoCardComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="message" [class.message--user]="message().role === 'user'">
      <div
        class="message__bubble"
        [attr.aria-label]="message().role === 'user' ? 'Your message' : 'Assistant message'"
      >
        @if (message().text) {
          <div class="message__text" [innerHTML]="message().text | sfMarkdown: message().text"></div>
        }
        @if (message().cards.length > 0) {
          <div class="message__cards">
            @for (card of message().cards; track trackCard($index, card)) {
              @switch (card.type) {
                @case ('EVENT') {
                  <app-assistant-event-card [card]="$any(card)" />
                }
                @case ('SESSION') {
                  <app-assistant-session-card [card]="$any(card)" />
                }
                @case ('SEAT_SET') {
                  <app-assistant-seat-card [card]="$any(card)" />
                }
                @case ('RESERVATION_PROPOSAL') {
                  <app-assistant-proposal-card [card]="$any(card)" />
                }
                @case ('RESERVATION_CREATED') {
                  <app-assistant-reservation-card [card]="$any(card)" />
                }
                @default {
                  <app-assistant-info-card [card]="$any(card)" />
                }
              }
            }
          </div>
        }
      </div>
    </div>
  `,
  styles: [
    `
      .message {
        display: flex;
        justify-content: flex-start;
      }
      .message--user {
        justify-content: flex-end;
      }
      .message__bubble {
        max-width: 100%;
        width: 100%;
        border-radius: 0.9rem;
        padding: 0.6rem 0.75rem;
        background: var(--color-canvas, transparent);
      }
      .message--user .message__bubble {
        background: var(--color-primary-soft, rgba(79, 70, 229, 0.1));
      }
      .message__cards {
        display: flex;
        flex-direction: column;
        gap: 0.6rem;
        margin-top: 0.5rem;
      }
    `,
  ],
})
export class AssistantMessageComponent {
  readonly message = input.required<AssistantUiMessage>();

  trackCard(index: number, card: AssistantUiMessage['cards'][number]): string {
    return `${card.type}-${index}`;
  }
}
