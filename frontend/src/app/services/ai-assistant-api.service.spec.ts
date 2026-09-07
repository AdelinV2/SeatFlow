import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { UserContextService } from '../core/auth/user-context.service';
import { AiAssistantApiService } from './ai-assistant-api.service';

describe('AiAssistantApiService', () => {
  let service: AiAssistantApiService;
  let httpMock: HttpTestingController;
  let userContext: UserContextService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), UserContextService],
    });
    service = TestBed.inject(AiAssistantApiService);
    httpMock = TestBed.inject(HttpTestingController);
    userContext = TestBed.inject(UserContextService);
    userContext.setUser({
      id: 'user-1',
      email: 'user@seatflow.test',
      name: 'Test User',
      roles: ['ROLE_CUSTOMER'],
    });
  });

  afterEach(() => {
    httpMock.verify();
    userContext.clearUser();
  });

  it('fetches feature status via GET /api/ai/status', () => {
    service.getStatus().subscribe((status) => {
      expect(status.enabled).toBeTrue();
      expect(status.state).toBe('READY');
    });

    const req = httpMock.expectOne('/api/ai/status');
    expect(req.request.method).toBe('GET');
    req.flush({ enabled: true, state: 'READY', model: 'openai/gpt-oss-20b' });
  });

  it('sends a chat turn via POST /api/ai/chat with conversation id', () => {
    service
      .sendMessage({ conversationId: 'conv-1', message: 'Find events this weekend.' })
      .subscribe((res) => expect(res.conversationId).toBe('conv-1'));

    const req = httpMock.expectOne('/api/ai/chat');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      message: 'Find events this weekend.',
      conversationId: 'conv-1',
    });
    req.flush({
      conversationId: 'conv-1',
      assistantMessage: 'Here are some events.',
      state: 'DISCOVERING',
      cards: [],
      suggestedActions: [],
      error: null,
    });
  });

  it('omits conversationId on the first turn', () => {
    service.sendMessage({ message: 'Hello' }).subscribe();

    const req = httpMock.expectOne('/api/ai/chat');
    expect(req.request.body).toEqual({ message: 'Hello' });
    req.flush({
      conversationId: 'conv-new',
      assistantMessage: 'Hi!',
      state: 'IDLE',
      cards: [],
      suggestedActions: [],
      error: null,
    });
  });

  it('resets a conversation via DELETE /api/ai/conversations/{id}', () => {
    service.resetConversation('conv-1').subscribe();

    const req = httpMock.expectOne('/api/ai/conversations/conv-1');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('confirms a proposal via POST with an EMPTY body (no seats/prices)', () => {
    service.confirmProposal('prop-1').subscribe();

    const req = httpMock.expectOne('/api/ai/proposals/prop-1/confirm');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({});
    expect(JSON.stringify(req.request.body)).not.toContain('seat');
    expect(JSON.stringify(req.request.body)).not.toContain('price');
    req.flush({
      reservationId: 'res-1',
      eventSessionId: 'sess-1',
      seatIds: [],
      seatLabels: ['A1'],
      totalAmount: 100,
      currency: 'RON',
      reservationStatus: 'PENDING',
      expiresAt: '2026-10-10T18:15:00Z',
      checkoutRoute: '/checkout/res-1',
    });
  });

  it('blocks chat for logged-out users without issuing HTTP', () => {
    userContext.clearUser();

    let failure: string | null = null;
    service.sendMessage({ message: 'Hello' }).subscribe({
      error: (error: Error) => {
        failure = error.message;
      },
    });

    expect(failure).toContain('Sign-in');
    httpMock.expectNone('/api/ai/chat');
  });

  it('blocks confirm for logged-out users without issuing HTTP', () => {
    userContext.clearUser();

    let failure: string | null = null;
    service.confirmProposal('prop-1').subscribe({
      error: (error: Error) => {
        failure = error.message;
      },
    });

    expect(failure).toContain('Sign-in');
    httpMock.expectNone('/api/ai/proposals/prop-1/confirm');
  });

  it('blocks status and reset for logged-out users without issuing HTTP', () => {
    userContext.clearUser();

    service.getStatus().subscribe({ error: () => undefined });
    service.resetConversation('conv-1').subscribe({ error: () => undefined });

    expect(httpMock.match('/api/ai/status').length).toBe(0);
    expect(httpMock.match('/api/ai/conversations/conv-1').length).toBe(0);
  });
});
