import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { NavigationStart, Router } from '@angular/router';
import { filter } from 'rxjs';
import { ASSISTANT_STARTER_PROMPTS, AssistantUiMessage } from '../../../models/assistant.model';
import { AssistantStore } from '../../../services/assistant-store.service';
import { AssistantMessageComponent } from '../components/assistant-message/assistant-message.component';

/**
 * SeatFlow Assistant drawer: header (title + availability + Reset + Close),
 * body (messages, cards, activity/error states), composer (input + Send +
 * starter prompts). Desktop right-side drawer; full-height sheet on narrow
 * viewports. Route policy: stays open during authenticated navigation, closes
 * when checkout starts. Escape closes; focus returns to the launcher.
 */
@Component({
  selector: 'app-assistant-drawer',
  standalone: true,
  imports: [FormsModule, AssistantMessageComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './assistant-drawer.component.html',
  styleUrl: './assistant-drawer.component.scss',
  host: {
    '(document:keydown.escape)': 'onEscape()',
  },
})
export class AssistantDrawerComponent {
  readonly store = inject(AssistantStore);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly draft = signal('');
  readonly composer = viewChild<ElementRef<HTMLTextAreaElement>>('composer');
  readonly threadRegion = viewChild<ElementRef<HTMLElement>>('threadRegion');

  readonly starterPrompts = ASSISTANT_STARTER_PROMPTS;

  constructor() {
    effect(() => {
      if (this.store.isOpen()) {
        this.focusComposer();
      }
    });
    this.router.events
      .pipe(
        filter((event): event is NavigationStart => event instanceof NavigationStart),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((event) => {
        if (event.url.startsWith('/checkout')) {
          this.store.isOpen.set(false);
        }
      });
  }

  trackMessage(_index: number, message: AssistantUiMessage): string {
    return message.id;
  }

  focusComposer(): void {
    window.setTimeout(() => this.composer()?.nativeElement.focus(), 0);
  }

  onEscape(): void {
    if (this.store.isOpen()) {
      this.store.closeDrawer();
    }
  }

  onClose(): void {
    this.store.closeDrawer();
  }

  onReset(): void {
    this.store.resetConversation();
  }

  onRetryStatus(): void {
    this.store.retryStatus();
  }

  onBackdropClick(): void {
    this.store.closeDrawer();
  }

  sendStarter(prompt: string): void {
    this.store.send(prompt);
    this.scrollToBottom();
  }

  send(): void {
    const text = this.draft();
    if (!text.trim() || !this.store.canSend()) {
      return;
    }
    this.draft.set('');
    this.store.send(text);
    this.scrollToBottom();
  }

  onComposerKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.send();
    }
  }

  remainingChars(): number {
    return this.store.maxMessageLength - this.draft().length;
  }

  isOverLimit(): boolean {
    return this.draft().length > this.store.maxMessageLength;
  }

  private scrollToBottom(): void {
    window.setTimeout(() => {
      this.threadRegion()?.nativeElement.scrollTo({
        top: this.threadRegion()?.nativeElement.scrollHeight,
        behavior: 'smooth',
      });
    }, 0);
  }
}
