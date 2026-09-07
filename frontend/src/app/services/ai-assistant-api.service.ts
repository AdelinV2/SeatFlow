import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, throwError } from 'rxjs';
import { UserContextService } from '../core/auth/user-context.service';
import {
  AiFeatureStatus,
  AssistantChatRequest,
  AssistantChatResponse,
  ProposalConfirmationError,
  ReservationCreatedPayload,
} from '../models/assistant.model';

/**
 * Typed same-origin API client for the SeatFlow AI assistant (TASK-P15-006).
 *
 * All calls go through the API gateway on `/api/ai/**` and rely on the shared
 * auth interceptor for JWT propagation — this service never reads or copies
 * tokens manually. Every method requires an authenticated user; logged-out
 * callers receive an error without any HTTP request being issued.
 *
 * Forbidden: provider secrets, provider Authorization headers, direct provider
 * host calls, backend system prompts, or proposal internals beyond the public DTOs.
 */
@Injectable({ providedIn: 'root' })
export class AiAssistantApiService {
  private readonly http = inject(HttpClient);
  private readonly userContext = inject(UserContextService);

  private readonly statusUrl = '/api/ai/status';
  private readonly chatUrl = '/api/ai/chat';
  private readonly conversationsUrl = '/api/ai/conversations';
  private readonly proposalsUrl = '/api/ai/proposals';

  /** GET /api/ai/status — lazy, non-blocking availability probe. */
  getStatus(): Observable<AiFeatureStatus> {
    if (!this.userContext.isAuthenticated()) {
      return throwError(() => new Error('Sign-in is required to use the SeatFlow Assistant.'));
    }
    return this.http.get<AiFeatureStatus>(this.statusUrl);
  }

  /** POST /api/ai/chat — one bounded conversation turn. Never creates a hold. */
  sendMessage(request: AssistantChatRequest): Observable<AssistantChatResponse> {
    if (!this.userContext.isAuthenticated()) {
      return throwError(() => new Error('Sign-in is required to use the SeatFlow Assistant.'));
    }
    const body: AssistantChatRequest = {
      message: request.message,
      ...(request.conversationId ? { conversationId: request.conversationId } : {}),
    };
    return this.http.post<AssistantChatResponse>(this.chatUrl, body);
  }

  /**
   * DELETE /api/ai/conversations/{id} — server-coordinated reset.
   * Never cancels a real reservation hold.
   */
  resetConversation(conversationId: string): Observable<void> {
    if (!this.userContext.isAuthenticated()) {
      return throwError(() => new Error('Sign-in is required to use the SeatFlow Assistant.'));
    }
    return this.http.delete<void>(`${this.conversationsUrl}/${conversationId}`);
  }

  /**
   * POST /api/ai/proposals/{id}/confirm with an EMPTY body.
   * Exact seat/session/price values always come from server-side proposal
   * storage; the client must never send seat IDs or prices here.
   * A 201 returns the authoritative ReservationCreatedPayload; a 202
   * (retry-safe unknown result) returns a ProposalConfirmationError body.
   */
  confirmProposal(proposalId: string): Observable<ReservationCreatedPayload | ProposalConfirmationError> {
    if (!this.userContext.isAuthenticated()) {
      return throwError(() => new Error('Sign-in is required to confirm a reservation.'));
    }
    return this.http.post<ReservationCreatedPayload | ProposalConfirmationError>(
      `${this.proposalsUrl}/${proposalId}/confirm`,
      {},
    );
  }
}
