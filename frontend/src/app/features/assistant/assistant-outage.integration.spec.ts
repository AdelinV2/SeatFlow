import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { UserContextService } from '../../core/auth/user-context.service';
import { chatErrorMessage, confirmationErrorMessage } from '../../models/assistant.model';
import { AssistantStore } from '../../services/assistant-store.service';

function signedInAs(userContext: UserContextService): void {
  userContext.setUser({
    id: 'user-1',
    email: 'user@seatflow.test',
    name: 'Test User',
    roles: ['ROLE_CUSTOMER'],
  });
}

/**
 * Provider-outage and abuse-guard UX (TASK-P15-007 sections 5/11):
 * the assistant degrades to safe disabled/retry messaging while normal
 * browsing state stays usable. No raw tool JSON, reasoning, credentials,
 * or stack traces ever reach the thread.
 */
describe('Assistant outage and rate-limit UX', () => {
  let store: AssistantStore;
  let httpMock: HttpTestingController;
  let userContext: UserContextService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    store = TestBed.inject(AssistantStore);
    httpMock = TestBed.inject(HttpTestingController);
    userContext = TestBed.inject(UserContextService);
    signedInAs(userContext);
  });

  afterEach(() => {
    httpMock.verify();
    userContext.clearUser();
    TestBed.flushEffects();
  });

  function flushChatFailure(status: number, body: object): void {
    const req = httpMock.expectOne('/api/ai/chat');
    req.flush(body, { status, statusText: status === 429 ? 'Too Many Requests' : 'Service Unavailable' });
  }

  function threadText(): string {
    return store.messages().map((m) => `${m.text} ${m.errorCode ?? ''} ${m.errorMessage ?? ''}`).join('\n');
  }

  it('provider 503 maps to a safe retry message and the thread stays usable', () => {
    store.send('Find Hamlet seats.');

    flushChatFailure(503, {
      code: 'AI_PROVIDER_UNAVAILABLE',
      message: 'AI provider is temporarily unavailable. Core booking remains available.',
    });

    expect(store.isSending()).toBeFalse();
    // The thread message already surfaces the failure: no separate error bubble.
    expect(store.lastError()).toBeNull();
    expect(threadText()).toContain(chatErrorMessage('AI_PROVIDER_UNAVAILABLE'));
    expect(threadText()).not.toContain('gsk_');
    expect(threadText()).not.toContain('reasoning');
    expect(threadText()).not.toContain('tool_calls');
    expect(threadText()).not.toContain('stackTrace');

    // Retry after the outage is a fresh turn, not a stuck state.
    store.send('Try again.');
    const retry = httpMock.expectOne('/api/ai/chat');
    expect(retry.request.method).toBe('POST');
    retry.flush({
      conversationId: 'conv-1',
      assistantMessage: 'Back online.',
      state: 'IDLE',
      cards: [],
      suggestedActions: [],
      error: null,
    });
    expect(store.isSending()).toBeFalse();
  });

  it('local 429 maps to the busy message without leaking quota internals', () => {
    store.send('Find Hamlet seats.');

    flushChatFailure(429, {
      errorCode: 'AI_RATE_LIMITED',
      status: 429,
      error: 'Too Many Requests',
      message: 'AI request limit reached. Please try again shortly. Core booking remains available.',
    });

    expect(store.lastError()).toBeNull();
    expect(threadText()).toContain(chatErrorMessage('AI_RATE_LIMITED'));
    expect(threadText()).not.toContain('replenishRate');
    expect(threadText()).not.toContain('burstCapacity');
    expect(threadText()).not.toContain('X-RateLimit');
  });

  it('unknown error shapes degrade to the generic message, never raw JSON', () => {
    store.send('Find Hamlet seats.');

    const req = httpMock.expectOne('/api/ai/chat');
    req.flush(
      { unexpected: 'shape', nested: { stack: 'java.lang.RuntimeException: boom' } },
      { status: 500, statusText: 'Server Error' },
    );

    const text = threadText();
    expect(text).not.toContain('RuntimeException');
    expect(text).not.toContain('unexpected');
    expect(store.isSending()).toBeFalse();
  });

  it('stale proposal confirm disables the card without substitution', () => {
    store.confirmProposal('prop-stale');

    const req = httpMock.expectOne('/api/ai/proposals/prop-stale/confirm');
    expect(req.request.body).toEqual({});
    req.flush(
      { code: 'SEATS_NO_LONGER_AVAILABLE', message: 'One or more seats are no longer available.' },
      { status: 409, statusText: 'Conflict' },
    );

    const status = store.proposalStatus('prop-stale');
    expect(status?.disabled).toBeTrue();
    expect(status?.retrySafe).toBeFalse();
    expect(store.lastError()).toBe(confirmationErrorMessage('SEATS_NO_LONGER_AVAILABLE'));
  });

  it('202 retry-safe confirm stays retryable and retry resends the empty body', () => {
    store.confirmProposal('prop-unknown');

    const first = httpMock.expectOne('/api/ai/proposals/prop-unknown/confirm');
    first.flush(
      {
        code: 'RESERVATION_RESULT_UNKNOWN_RETRY_SAFE',
        message: 'The reservation result is unknown after a timeout.',
      },
      { status: 202, statusText: 'Accepted' },
    );

    expect(store.proposalStatus('prop-unknown')?.retrySafe).toBeTrue();

    store.retryConfirm('prop-unknown');
    const retry = httpMock.expectOne('/api/ai/proposals/prop-unknown/confirm');
    expect(retry.request.body).toEqual({});
    retry.flush({
      reservationId: 'res-1',
      eventSessionId: 'sess-1',
      seatIds: ['s-1'],
      seatLabels: ['A1'],
      totalAmount: 150,
      currency: 'RON',
      reservationStatus: 'PENDING',
      expiresAt: '2026-10-10T18:15:00Z',
      checkoutRoute: '/checkout/res-1',
    });
    expect(store.messages().at(-1)?.cards[0].type).toBe('RESERVATION_CREATED');
  });

  it('chat request bodies carry only message + conversation id (no seats, prices, or tokens)', () => {
    store.send('Find two seats together.');

    const req = httpMock.expectOne('/api/ai/chat');
    expect(Object.keys(req.request.body as object).sort()).toEqual(['message']);
    expect(JSON.stringify(req.request.body)).not.toContain('gsk_');
    expect(JSON.stringify(req.request.body)).not.toContain('Bearer');
    req.flush({
      conversationId: 'conv-9',
      assistantMessage: 'ok',
      state: 'IDLE',
      cards: [],
      suggestedActions: [],
      error: null,
    });

    store.send('Follow-up.');
    const second = httpMock.expectOne('/api/ai/chat');
    expect(Object.keys(second.request.body as object).sort()).toEqual(['conversationId', 'message']);
    second.flush({
      conversationId: 'conv-9',
      assistantMessage: 'ok',
      state: 'IDLE',
      cards: [],
      suggestedActions: [],
      error: null,
    });
  });

  it('disabled feature state reports unavailable while browsing state stays intact', () => {
    store.ensureStatusLoaded();
    const statusReq = httpMock.expectOne('/api/ai/status');
    statusReq.flush({ enabled: false, state: 'DISABLED', model: null });

    expect(store.statusLoaded()).toBeTrue();
    expect(store.isAvailable()).toBeFalse();
    // Browsing is unaffected: the store holds no error and accepts no AI coupling.
    expect(store.lastError()).toBeNull();
    expect(store.assistantState()).toBe('IDLE');
  });

  it('every provider error code has a safe frontend message (no raw fallback)', () => {
    for (const code of [
      'AI_DISABLED',
      'AI_MISCONFIGURED',
      'AI_RATE_LIMITED',
      'AI_PROVIDER_TIMEOUT',
      'AI_PROVIDER_UNAVAILABLE',
      'AI_MODEL_UNAVAILABLE',
      'AI_RESPONSE_INVALID',
    ]) {
      const message = chatErrorMessage(code);
      expect(message).toBeTruthy();
      expect(message).not.toContain('gsk_');
      expect(message).not.toContain('Groq');
      expect(message).not.toContain('stack');
    }
  });
});
