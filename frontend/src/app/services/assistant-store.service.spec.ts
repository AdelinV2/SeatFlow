import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { UserContextService } from '../core/auth/user-context.service';
import { AssistantChatResponse, ProposalAssistantCard, ReservationCreatedAssistantCard } from '../models/assistant.model';
import { AssistantStore } from './assistant-store.service';

function signedInAs(userContext: UserContextService): void {
  userContext.setUser({
    id: 'user-1',
    email: 'user@seatflow.test',
    name: 'Test User',
    roles: ['ROLE_CUSTOMER'],
  });
}

function chatResponse(overrides: Partial<AssistantChatResponse> = {}): AssistantChatResponse {
  return {
    conversationId: 'conv-1',
    assistantMessage: 'Here are two seats together.',
    state: 'CONFIRMATION_REQUIRED',
    cards: [],
    suggestedActions: [],
    error: null,
    ...overrides,
  };
}

function proposalCard(): ProposalAssistantCard {
  return {
    type: 'RESERVATION_PROPOSAL',
    eventId: 'ev-1',
    eventSessionId: 'sess-1',
    proposalId: 'prop-1',
    seatIds: ['s-1', 's-2'],
    seatLabels: ['A1', 'A2'],
    sectionSummary: 'Stalls • Row A',
    totalPriceMinor: 20000,
    currency: 'RON',
    contiguous: true,
    reasons: ['Together in row A'],
    requiresExplicitConfirmation: true,
    seatsHeld: false,
  };
}

describe('AssistantStore', () => {
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

  it('sends a chat turn and threads the conversation id', () => {
    store.send('Find events this weekend.');

    const req = httpMock.expectOne('/api/ai/chat');
    expect(req.request.method).toBe('POST');
    req.flush(chatResponse());

    expect(store.conversationId()).toBe('conv-1');
    expect(store.messages().length).toBe(2);
    expect(store.messages()[0].role).toBe('user');
    expect(store.messages()[1].role).toBe('assistant');
    expect(store.isSending()).toBeFalse();
  });

  it('blocks 2001-char input client-side without HTTP', () => {
    store.send('x'.repeat(2001));

    httpMock.expectNone('/api/ai/chat');
    expect(store.messages().length).toBe(0);
    expect(store.lastError()).toContain('2000');
  });

  it('accepts exactly 2000 chars and prevents duplicate in-flight sends', () => {
    store.send('x'.repeat(2000));
    store.send('second message while busy');

    const reqs = httpMock.match('/api/ai/chat');
    expect(reqs.length).toBe(1);
    reqs[0].flush(chatResponse());
    expect(store.messages().length).toBe(2);
  });

  it('typing "yes" only sends a chat turn and never calls confirm', () => {
    store.send('yes, confirm it');

    const req = httpMock.expectOne('/api/ai/chat');
    req.flush(chatResponse({ cards: [proposalCard()] }));
    httpMock.expectNone('/api/ai/proposals/prop-1/confirm');
    expect(store.messages()[1].cards.length).toBe(1);
  });

  it('confirm appends a RESERVATION_CREATED card from the 201 payload only', () => {
    expect(store.messages().length).toBe(0);

    store.confirmProposal('prop-1');
    expect(store.isConfirming('prop-1')).toBeTrue();

    const req = httpMock.expectOne('/api/ai/proposals/prop-1/confirm');
    expect(req.request.body).toEqual({});
    req.flush({
      reservationId: 'res-9',
      eventSessionId: 'sess-1',
      seatIds: ['s-1'],
      seatLabels: ['A1'],
      totalAmount: 100,
      currency: 'RON',
      reservationStatus: 'PENDING',
      expiresAt: '2026-10-10T18:15:00Z',
      checkoutRoute: '/checkout/res-9',
    });

    expect(store.isConfirming('prop-1')).toBeFalse();
    const created = store.messages().at(-1);
    expect(created?.cards[0].type).toBe('RESERVATION_CREATED');
    expect(store.assistantState()).toBe('RESERVATION_CREATED');
    const card = created?.cards[0] as ReservationCreatedAssistantCard;
    expect(card.expiresAt).toBe('2026-10-10T18:15:00Z');
    expect(card.checkoutRoute).toBe('/checkout/res-9');
  });

  it('disables the proposal card on stale/expired/price-changed failures without substitution', () => {
    store.confirmProposal('prop-1');

    const req = httpMock.expectOne('/api/ai/proposals/prop-1/confirm');
    req.flush(
      { code: 'SEATS_NO_LONGER_AVAILABLE', message: 'Gone.' },
      { status: 409, statusText: 'Conflict' },
    );

    const status = store.proposalStatus('prop-1');
    expect(status?.disabled).toBeTrue();
    expect(status?.retrySafe).toBeFalse();
    expect(store.lastError()).toContain('no longer available');
    expect(store.messages().at(-1)?.cards.length).toBe(0);
  });

  it('marks 202 retry-safe outcomes as retryable without claiming success', () => {
    store.confirmProposal('prop-1');

    const req = httpMock.expectOne('/api/ai/proposals/prop-1/confirm');
    req.flush(
      { code: 'RESERVATION_RESULT_UNKNOWN_RETRY_SAFE', message: 'Unknown.' },
      { status: 202, statusText: 'Accepted' },
    );

    expect(store.proposalStatus('prop-1')?.retrySafe).toBeTrue();
    expect(store.lastError()).toContain('safe to retry');
  });

  it('maps rate-limit/provider failures to safe messages without a duplicate error bubble', () => {
    store.send('Hello');

    const req = httpMock.expectOne('/api/ai/chat');
    req.flush(
      chatResponse({
        assistantMessage: '',
        state: 'ERROR_RECOVERABLE',
        error: { code: 'AI_RATE_LIMITED', message: '' },
      }),
    );

    // The thread message already surfaces the failure: no separate error bubble.
    expect(store.lastError()).toBeNull();
    const last = store.messages().at(-1);
    expect(last?.text).toContain('temporarily busy');
    expect(last?.text).not.toContain('quota');
  });

  it('reset calls DELETE then clears UI and never cancels a reservation', () => {
    store.send('Hello');
    httpMock.expectOne('/api/ai/chat').flush(chatResponse());
    expect(store.messages().length).toBe(2);

    store.resetConversation();
    httpMock.expectOne('/api/ai/conversations/conv-1').flush(null);

    expect(store.messages().length).toBe(0);
    expect(store.conversationId()).toBeNull();
    httpMock.expectNone('/api/reservations/res-9/cancel');
  });

  it('reset reconciles a missing conversation by clearing local UI', () => {
    store.send('Hello');
    httpMock.expectOne('/api/ai/chat').flush(chatResponse());

    store.resetConversation();
    httpMock
      .expectOne('/api/ai/conversations/conv-1')
      .flush({ message: 'gone' }, { status: 404, statusText: 'Not Found' });

    expect(store.messages().length).toBe(0);
    expect(store.conversationId()).toBeNull();
  });

  it('sign-out clears thread state immediately', () => {
    store.send('Hello');
    httpMock.expectOne('/api/ai/chat').flush(chatResponse());
    store.isOpen.set(true);

    userContext.clearUser();
    TestBed.flushEffects();

    expect(store.messages().length).toBe(0);
    expect(store.conversationId()).toBeNull();
    expect(store.isOpen()).toBeFalse();
  });

  it('logged-out send is rejected without HTTP', () => {
    userContext.clearUser();

    store.send('Hello');

    httpMock.expectNone('/api/ai/chat');
    expect(store.lastError()).toContain('Sign-in');
  });

  it('REV-001: late chat 200 after sign-out leaves the cleared thread empty', () => {
    store.send('Hello');
    const req = httpMock.expectOne('/api/ai/chat');

    userContext.clearUser();
    TestBed.flushEffects();
    expect(store.messages().length).toBe(0);

    req.flush(chatResponse());

    expect(store.messages().length).toBe(0);
    expect(store.conversationId()).toBeNull();
    expect(store.isSending()).toBeFalse();
  });

  it('REV-001: late confirm 201 after sign-out appends no hold card', () => {
    store.confirmProposal('prop-1');
    const req = httpMock.expectOne('/api/ai/proposals/prop-1/confirm');

    userContext.clearUser();
    TestBed.flushEffects();

    req.flush({
      reservationId: 'res-9',
      eventSessionId: 'sess-1',
      seatIds: ['s-1'],
      seatLabels: ['A1'],
      totalAmount: 100,
      currency: 'RON',
      reservationStatus: 'PENDING',
      expiresAt: '2026-10-10T18:15:00Z',
      checkoutRoute: '/checkout/res-9',
    });

    expect(store.messages().length).toBe(0);
    expect(store.assistantState()).toBe('IDLE');
    expect(store.isConfirming('prop-1')).toBeFalse();
  });

  it('REV-003: bodyless 2xx confirm routes to the generic failure path without throwing', () => {
    store.confirmProposal('prop-1');

    const req = httpMock.expectOne('/api/ai/proposals/prop-1/confirm');
    expect(() => req.flush(null, { status: 202, statusText: 'Accepted' })).not.toThrow();

    expect(store.lastError()).toBeTruthy();
    expect(store.isConfirming('prop-1')).toBeFalse();
    const status = store.proposalStatus('prop-1');
    expect(status?.disabled).toBeTrue();
    expect(status?.retrySafe).toBeFalse();
  });
});
