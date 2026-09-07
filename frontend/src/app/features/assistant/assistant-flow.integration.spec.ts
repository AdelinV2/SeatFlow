import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { UserContextService } from '../../core/auth/user-context.service';
import { ReservationCreatedAssistantCard } from '../../models/assistant.model';
import { AssistantStore } from '../../services/assistant-store.service';

@Component({
  selector: 'app-assistant-flow-stub',
  standalone: true,
  template: '<p>stub</p>',
})
class StubComponent {}

/**
 * Mocked end-to-end assistant journey:
 * chat -> seat proposal -> explicit confirm -> reservation-created card
 * -> checkout navigation. No Groq, no real reservation service.
 */
describe('Assistant proposal-to-checkout flow', () => {
  let store: AssistantStore;
  let httpMock: HttpTestingController;
  let userContext: UserContextService;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'checkout/:reservationId', component: StubComponent }]),
      ],
    });
    store = TestBed.inject(AssistantStore);
    httpMock = TestBed.inject(HttpTestingController);
    userContext = TestBed.inject(UserContextService);
    router = TestBed.inject(Router);
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
    TestBed.flushEffects();
  });

  it('chat -> proposal -> confirm -> reservation-created -> checkout', async () => {
    store.send('Find two seats together for Hamlet under 250 RON.');

    const chat = httpMock.expectOne('/api/ai/chat');
    expect(chat.request.method).toBe('POST');
    chat.flush({
      conversationId: 'conv-flow',
      assistantMessage: 'I found two seats together. These seats are not held yet.',
      state: 'CONFIRMATION_REQUIRED',
      cards: [
        {
          type: 'RESERVATION_PROPOSAL',
          eventId: 'ev-1',
          eventSessionId: 'sess-1',
          proposalId: 'prop-flow',
          seatIds: ['s-1', 's-2'],
          seatLabels: ['A1', 'A2'],
          sectionSummary: 'Stalls • Row A',
          totalPriceMinor: 20000,
          currency: 'RON',
          contiguous: true,
          reasons: ['Together in row A'],
          requiresExplicitConfirmation: true,
          seatsHeld: false,
        },
      ],
      suggestedActions: [],
      error: null,
    });

    expect(store.conversationId()).toBe('conv-flow');
    expect(store.messages()[1].cards[0].type).toBe('RESERVATION_PROPOSAL');
    expect(store.assistantState()).toBe('CONFIRMATION_REQUIRED');

    store.confirmProposal('prop-flow');

    const confirm = httpMock.expectOne('/api/ai/proposals/prop-flow/confirm');
    expect(confirm.request.method).toBe('POST');
    expect(confirm.request.body).toEqual({});
    confirm.flush({
      reservationId: 'res-flow',
      eventSessionId: 'sess-1',
      seatIds: ['s-1', 's-2'],
      seatLabels: ['A1', 'A2'],
      totalAmount: 200,
      currency: 'RON',
      reservationStatus: 'PENDING',
      expiresAt: '2026-10-10T18:15:00Z',
      checkoutRoute: '/checkout/res-flow',
    });

    const created = store.messages().at(-1);
    expect(created?.cards[0].type).toBe('RESERVATION_CREATED');
    const card = created?.cards[0] as ReservationCreatedAssistantCard;
    expect(card.expiresAt).toBe('2026-10-10T18:15:00Z');
    expect(card.checkoutRoute).toBe('/checkout/res-flow');

    store.goToCheckout(card.reservationId);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(router.url).toBe('/checkout/res-flow');
  });
});
