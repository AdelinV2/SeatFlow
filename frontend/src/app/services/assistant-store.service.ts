import { HttpErrorResponse } from '@angular/common/http';
import { computed, effect, inject, Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';
import { UserContextService } from '../core/auth/user-context.service';
import {
  AiFeatureStatus,
  ASSISTANT_MAX_MESSAGE_LENGTH,
  AssistantChatResponse,
  AssistantState,
  AssistantUiMessage,
  chatErrorMessage,
  confirmationErrorMessage,
  isAssistantAvailable,
  isRetrySafeConfirmationCode,
  ProposalCardStatus,
  ProposalConfirmationError,
  ReservationCreatedPayload,
  toReservationCreatedCard,
} from '../models/assistant.model';
import { AiAssistantApiService } from './ai-assistant-api.service';

let assistantMessageSequence = 0;

function nextMessageId(): string {
  assistantMessageSequence += 1;
  return `assistant-msg-${Date.now()}-${assistantMessageSequence}`;
}

function isConfirmationError(
  payload: ReservationCreatedPayload | ProposalConfirmationError | null | undefined,
): payload is ProposalConfirmationError {
  return (
    payload !== null &&
    payload !== undefined &&
    typeof payload === 'object' &&
    'code' in payload &&
    typeof payload.code === 'string' &&
    payload.code.length > 0
  );
}

/**
 * In-memory signals store for the AI assistant thread (TASK-P15-006).
 *
 * - Thread + conversationId live in Angular memory for the SPA lifetime only;
 *   nothing is persisted to localStorage, so a refresh starts a new conversation.
 * - Reset calls DELETE first and clears UI only after success or safe
 *   404/410 reconciliation; it never touches real reservations.
 * - Sign-out clears all local assistant state immediately via a reactive effect.
 * - Typing "yes"/"confirm"/"book it" only ever sends a normal chat turn; only
 *   the explicit confirm button calls the confirmation endpoint.
 */
@Injectable({ providedIn: 'root' })
export class AssistantStore {
  private readonly api = inject(AiAssistantApiService);
  private readonly userContext = inject(UserContextService);
  private readonly router = inject(Router);

  readonly isOpen = signal(false);
  readonly conversationId = signal<string | null>(null);
  readonly messages = signal<readonly AssistantUiMessage[]>([]);
  readonly assistantState = signal<AssistantState>('IDLE');
  readonly isSending = signal(false);
  readonly isResetting = signal(false);
  readonly confirmingProposals = signal<readonly string[]>([]);
  readonly proposalStatuses = signal<Readonly<Record<string, ProposalCardStatus>>>({});
  readonly featureStatus = signal<AiFeatureStatus | null>(null);
  readonly statusLoading = signal(false);
  readonly statusLoaded = signal(false);
  readonly lastError = signal<string | null>(null);
  readonly activityMessage = signal<string | null>(null);

  /**
   * Monotonic thread generation (REV-001). Incremented on every local thread
   * clear; in-flight chat/confirm handlers capture it at subscribe time and
   * drop stale responses silently when it changed (sign-out/reset race).
   */
  private threadGeneration = 0;

  readonly isAuthenticated = this.userContext.isAuthenticated;
  readonly isAvailable = computed(() => isAssistantAvailable(this.featureStatus()));
  readonly hasMessages = computed(() => this.messages().length > 0);
  readonly canSend = computed(() => this.isAuthenticated() && !this.isSending());
  readonly maxMessageLength = ASSISTANT_MAX_MESSAGE_LENGTH;

  constructor() {
    effect(() => {
      if (!this.userContext.isAuthenticated()) {
        this.clearLocalState();
      }
    });
  }

  openDrawer(): void {
    if (!this.userContext.isAuthenticated()) {
      void this.router.navigate(['/auth/login']);
      return;
    }
    this.isOpen.set(true);
    this.ensureStatusLoaded();
  }

  closeDrawer(returnFocus = true): void {
    this.isOpen.set(false);
    if (returnFocus && typeof document !== 'undefined') {
      document.getElementById('assistant-launcher')?.focus();
    }
  }

  toggleDrawer(): void {
    if (this.isOpen()) {
      this.closeDrawer();
    } else {
      this.openDrawer();
    }
  }

  /** Lazy status probe on first open; never blocks app bootstrap. */
  ensureStatusLoaded(): void {
    if (this.statusLoaded() || this.statusLoading() || !this.userContext.isAuthenticated()) {
      return;
    }
    this.statusLoading.set(true);
    this.api.getStatus().subscribe({
      next: (status) => {
        this.featureStatus.set(status);
        this.statusLoading.set(false);
        this.statusLoaded.set(true);
      },
      error: () => {
        this.statusLoading.set(false);
        this.statusLoaded.set(true);
      },
    });
  }

  /** Bounded single-shot retry for the lazy status probe (REV-004, no polling). */
  retryStatus(): void {
    if (this.statusLoading() || !this.userContext.isAuthenticated()) {
      return;
    }
    this.statusLoaded.set(false);
    this.ensureStatusLoaded();
  }

  send(rawText: string): void {
    const message = rawText.trim();
    if (!message || this.isSending()) {
      return;
    }
    if (!this.userContext.isAuthenticated()) {
      this.lastError.set('Sign-in is required to use the SeatFlow Assistant.');
      return;
    }
    if (message.length > ASSISTANT_MAX_MESSAGE_LENGTH) {
      this.lastError.set(
        `Messages are limited to ${ASSISTANT_MAX_MESSAGE_LENGTH} characters. Please shorten your message.`,
      );
      return;
    }

    this.lastError.set(null);
    this.isSending.set(true);
    this.activityMessage.set('Checking live seat availability…');
    this.appendMessage({ role: 'user', text: message, cards: [] });

    const conversationId = this.conversationId();
    const generation = this.threadGeneration;
    this.api
      .sendMessage({
        message,
        ...(conversationId ? { conversationId } : {}),
      })
      .subscribe({
        next: (response) => {
          if (generation !== this.threadGeneration || !this.userContext.isAuthenticated()) {
            return;
          }
          this.applyChatResponse(response);
        },
        error: (error: HttpErrorResponse) => {
          if (generation !== this.threadGeneration || !this.userContext.isAuthenticated()) {
            return;
          }
          this.isSending.set(false);
          this.activityMessage.set(null);
          const code =
            (error.error as { code?: string } | null)?.code ??
            (error.error as { error?: { code?: string } } | null)?.error?.code;
          const friendly = code ? chatErrorMessage(code) : 'The assistant could not complete that request. Please try again.';
          this.lastError.set(friendly);
          this.appendMessage({
            role: 'assistant',
            text: friendly,
            cards: [],
            errorCode: code ?? `HTTP_${error.status}`,
            errorMessage: friendly,
          });
        },
      });
  }

  confirmProposal(proposalId: string): void {
    if (!proposalId || this.isConfirming(proposalId)) {
      return;
    }
    if (!this.userContext.isAuthenticated()) {
      this.lastError.set('Sign-in is required to confirm a reservation.');
      return;
    }
    this.lastError.set(null);
    this.confirmingProposals.update((current) =>
      current.includes(proposalId) ? current : [...current, proposalId],
    );

    const generation = this.threadGeneration;
    this.api.confirmProposal(proposalId).subscribe({
      next: (payload) => {
        if (generation !== this.threadGeneration || !this.userContext.isAuthenticated()) {
          return;
        }
        this.confirmingProposals.update((current) => current.filter((id) => id !== proposalId));
        if (!payload || typeof payload !== 'object' || !('reservationId' in payload)) {
          if (isConfirmationError(payload)) {
            this.applyConfirmationFailure(proposalId, payload.code, payload.message);
            return;
          }
          this.applyConfirmationFailure(proposalId, 'CONFIRMATION_FAILED', null);
          return;
        }
        if (isConfirmationError(payload)) {
          this.applyConfirmationFailure(proposalId, payload.code, payload.message);
          return;
        }
        this.markProposalDisabled(proposalId, 'CONFIRMED', 'Reservation confirmed. This proposal was used.', false);
        const card = toReservationCreatedCard(payload);
        this.appendMessage({
          role: 'assistant',
          text: 'Your seats are now held. Continue to checkout before the hold expires.',
          cards: [card],
          state: 'RESERVATION_CREATED',
        });
        this.assistantState.set('RESERVATION_CREATED');
      },
      error: (error: HttpErrorResponse) => {
        if (generation !== this.threadGeneration || !this.userContext.isAuthenticated()) {
          return;
        }
        this.confirmingProposals.update((current) => current.filter((id) => id !== proposalId));
        const body = error.error as ProposalConfirmationError | null;
        const code = body?.code ?? `HTTP_${error.status}`;
        this.applyConfirmationFailure(proposalId, code, body?.message ?? null);
      },
    });
  }

  retryConfirm(proposalId: string): void {
    const status = this.proposalStatuses()[proposalId];
    if (!status?.retrySafe) {
      return;
    }
    this.proposalStatuses.update((current) => {
      const next = { ...current };
      delete next[proposalId];
      return next;
    });
    this.confirmProposal(proposalId);
  }

  dismissProposal(proposalId: string): void {
    this.markProposalDisabled(proposalId, 'DISMISSED', 'You dismissed this proposal. Ask for fresh options any time.', false);
  }

  resetConversation(): void {
    const conversationId = this.conversationId();
    if (this.isResetting() || !this.userContext.isAuthenticated()) {
      return;
    }
    if (!conversationId) {
      this.clearThread();
      return;
    }
    this.isResetting.set(true);
    const generation = this.threadGeneration;
    this.api.resetConversation(conversationId).subscribe({
      next: () => {
        this.isResetting.set(false);
        if (generation !== this.threadGeneration || !this.userContext.isAuthenticated()) {
          return;
        }
        this.clearThread();
      },
      error: (error: HttpErrorResponse) => {
        this.isResetting.set(false);
        if (generation !== this.threadGeneration || !this.userContext.isAuthenticated()) {
          return;
        }
        if (error.status === 404 || error.status === 410) {
          this.clearThread();
          return;
        }
        this.lastError.set('Could not reset the conversation. Please try again.');
      },
    });
  }

  goToCheckout(reservationId: string): void {
    this.isOpen.set(false);
    void this.router.navigate(['/checkout', reservationId]);
  }

  clearLocalState(): void {
    this.isOpen.set(false);
    this.clearThread();
    this.featureStatus.set(null);
    this.statusLoaded.set(false);
    this.statusLoading.set(false);
  }

  isConfirming(proposalId: string): boolean {
    return this.confirmingProposals().includes(proposalId);
  }

  proposalStatus(proposalId: string): ProposalCardStatus | null {
    return this.proposalStatuses()[proposalId] ?? null;
  }

  private clearThread(): void {
    this.threadGeneration += 1;
    this.conversationId.set(null);
    this.messages.set([]);
    this.assistantState.set('IDLE');
    this.proposalStatuses.set({});
    this.confirmingProposals.set([]);
    this.isSending.set(false);
    this.activityMessage.set(null);
    this.lastError.set(null);
  }

  private appendMessage(init: {
    role: 'user' | 'assistant' | 'system';
    text: string;
    cards: AssistantUiMessage['cards'];
    state?: AssistantState | null;
    errorCode?: string | null;
    errorMessage?: string | null;
  }): void {
    const entry: AssistantUiMessage = {
      id: nextMessageId(),
      role: init.role,
      text: init.text,
      cards: init.cards,
      state: init.state ?? null,
      errorCode: init.errorCode ?? null,
      errorMessage: init.errorMessage ?? null,
      createdAt: new Date().toISOString(),
    };
    this.messages.update((current) => [...current, entry]);
  }

  private applyChatResponse(response: AssistantChatResponse): void {
    this.isSending.set(false);
    this.activityMessage.set(null);
    if (response.conversationId) {
      this.conversationId.set(response.conversationId);
    }
    this.assistantState.set(response.state);
    const errorText = response.error
      ? response.error.message || chatErrorMessage(response.error.code)
      : null;
    if (errorText) {
      this.lastError.set(errorText);
    }
    this.appendMessage({
      role: 'assistant',
      text: response.error ? errorText ?? '' : response.assistantMessage,
      cards: response.cards ?? [],
      state: response.state,
      errorCode: response.error?.code ?? null,
      errorMessage: errorText,
    });
  }

  private applyConfirmationFailure(proposalId: string, code: string, message: string | null): void {
    const friendly = confirmationErrorMessage(code);
    this.lastError.set(friendly);
    this.markProposalDisabled(proposalId, code, friendly, isRetrySafeConfirmationCode(code));
    this.appendMessage({
      role: 'assistant',
      text: friendly,
      cards: [],
      errorCode: code,
      errorMessage: message ?? friendly,
    });
  }

  private markProposalDisabled(proposalId: string, code: string, message: string, retrySafe: boolean): void {
    this.proposalStatuses.update((current) => ({
      ...current,
      [proposalId]: { disabled: true, code, message, retrySafe },
    }));
  }
}
