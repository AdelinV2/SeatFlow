import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { InfoAssistantCard, UnknownAssistantCard } from '../../../../models/assistant.model';

/** Renders INFO cards and safe fallbacks for unknown future card types. */
@Component({
  selector: 'app-assistant-info-card',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <article class="assistant-card" aria-label="Assistant information">
      @if (title()) {
        <h4 class="assistant-card__title">{{ title() }}</h4>
      }
      @if (message()) {
        <p class="assistant-card__meta">{{ message() }}</p>
      }
      @if (isUnsupported()) {
        <p class="assistant-card__meta">This result type is not supported by your app version yet.</p>
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
        gap: 0.3rem;
      }
      .assistant-card__title {
        font-size: 0.88rem;
        font-weight: 700;
        margin: 0;
        color: var(--color-text-primary);
      }
      .assistant-card__meta {
        font-size: 0.8rem;
        margin: 0;
        color: var(--color-text-secondary);
      }
    `,
  ],
})
export class AssistantInfoCardComponent {
  readonly card = input.required<InfoAssistantCard | UnknownAssistantCard>();

  title(): string | null {
    return this.card().infoTitle ?? null;
  }

  message(): string | null {
    return this.card().infoMessage ?? null;
  }

  isUnsupported(): boolean {
    return this.card().type !== 'INFO';
  }
}
