import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { UserContextService } from '../../../../core/auth/user-context.service';
import { HoldCountdownComponent } from '../../../../shared/components/hold-countdown/hold-countdown.component';
import {
  AssistantUiMessage,
  EventAssistantCard,
  InfoAssistantCard,
  ReservationCreatedAssistantCard,
  SessionAssistantCard,
  UnknownAssistantCard,
} from '../../../../models/assistant.model';
import { AssistantStore } from '../../../../services/assistant-store.service';
import { AssistantMessageComponent } from './assistant-message.component';
import { AssistantEventCardComponent } from '../assistant-event-card/assistant-event-card.component';
import { AssistantSessionCardComponent } from '../assistant-session-card/assistant-session-card.component';
import { AssistantReservationCardComponent } from '../assistant-reservation-card/assistant-reservation-card.component';
import { AssistantInfoCardComponent } from '../assistant-info-card/assistant-info-card.component';

function userMessage(text: string): AssistantUiMessage {
  return {
    id: 'm-user',
    role: 'user',
    text,
    cards: [],
    state: null,
    errorCode: null,
    errorMessage: null,
    createdAt: new Date().toISOString(),
  };
}

describe('AssistantMessageComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [
        AssistantMessageComponent,
        AssistantEventCardComponent,
        AssistantSessionCardComponent,
        AssistantReservationCardComponent,
        AssistantInfoCardComponent,
      ],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        UserContextService,
      ],
    }).compileComponents();
    TestBed.inject(UserContextService).setUser({
      id: 'user-1',
      email: 'user@seatflow.test',
      name: 'Test User',
      roles: ['ROLE_CUSTOMER'],
    });
  });

  it('does not execute raw HTML/script from assistant text', () => {
    const fixture: ComponentFixture<AssistantMessageComponent> =
      TestBed.createComponent(AssistantMessageComponent);
    fixture.componentRef.setInput(
      'message',
      userMessage('<script>alert("xss")</script><img src=x onerror=alert(1)>'),
    );
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('script')).toBeNull();
    expect(element.querySelector('img')).toBeNull();
    expect(element.textContent).toContain('alert');
  });

  it('renders an unknown future card type with a graceful fallback', () => {
    const unknown: UnknownAssistantCard = { type: 'FUTURE_CARD', infoTitle: null, infoMessage: null };
    const message: AssistantUiMessage = {
      ...userMessage('here is a card'),
      role: 'assistant',
      cards: [unknown],
    };
    const fixture: ComponentFixture<AssistantMessageComponent> =
      TestBed.createComponent(AssistantMessageComponent);
    fixture.componentRef.setInput('message', message);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('not supported');
  });

  it('renders INFO cards as safe text', () => {
    const info: InfoAssistantCard = {
      type: 'INFO',
      infoTitle: 'Heads up',
      infoMessage: 'Nothing found for that query.',
    };
    const fixture: ComponentFixture<AssistantMessageComponent> =
      TestBed.createComponent(AssistantMessageComponent);
    fixture.componentRef.setInput('message', {
      ...userMessage('note'),
      role: 'assistant',
      cards: [info],
    });
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Heads up');
    expect(text).toContain('Nothing found');
  });

  it('event card navigates to the existing event detail route', () => {
    const router = TestBed.inject(Router);
    spyOn(router, 'navigate');
    const card: EventAssistantCard = {
      type: 'EVENT',
      eventId: 'ev-7',
      title: 'Hamlet',
      category: 'Theatre',
      venueSummary: 'Grand Hall',
    };
    const fixture: ComponentFixture<AssistantEventCardComponent> =
      TestBed.createComponent(AssistantEventCardComponent);
    fixture.componentRef.setInput('card', card);
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('.assistant-card__action') as HTMLButtonElement).click();
    expect(router.navigate).toHaveBeenCalledWith(['/events', 'ev-7']);
  });

  it('session card shows exact status and navigates into the seat flow', () => {
    const router = TestBed.inject(Router);
    spyOn(router, 'navigate');
    const card: SessionAssistantCard = {
      type: 'SESSION',
      eventId: 'ev-7',
      eventSessionId: 'sess-2',
      startsAt: '2026-10-10T18:00:00Z',
      endsAt: '2026-10-10T20:00:00Z',
      status: 'ON_SALE',
      bookable: 'BOOKABLE',
    };
    const fixture: ComponentFixture<AssistantSessionCardComponent> =
      TestBed.createComponent(AssistantSessionCardComponent);
    fixture.componentRef.setInput('card', card);
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('ON_SALE');
    expect(text).toContain('Bookable');
    (fixture.nativeElement.querySelector('.assistant-card__action') as HTMLButtonElement).click();
    expect(router.navigate).toHaveBeenCalledWith(['/events', 'ev-7', 'seats']);
  });

  it('reservation card uses backend expiresAt and routes to checkout', () => {
    const store = TestBed.inject(AssistantStore);
    spyOn(store, 'goToCheckout');
    const card: ReservationCreatedAssistantCard = {
      type: 'RESERVATION_CREATED',
      reservationId: 'res-9',
      eventSessionId: 'sess-1',
      seatIds: ['s-1'],
      seatLabels: ['A1'],
      totalAmountMajor: 100,
      currency: 'RON',
      reservationStatus: 'PENDING',
      expiresAt: '2026-10-10T18:15:00Z',
      checkoutRoute: '/checkout/res-9',
    };
    const fixture: ComponentFixture<AssistantReservationCardComponent> =
      TestBed.createComponent(AssistantReservationCardComponent);
    fixture.componentRef.setInput('card', card);
    fixture.detectChanges();

    const countdown = fixture.debugElement.query(By.directive(HoldCountdownComponent));
    expect(countdown).not.toBeNull();
    expect(countdown.componentInstance.expiresAt()).toBe('2026-10-10T18:15:00Z');

    (fixture.nativeElement.querySelector('.reservation-card__action') as HTMLButtonElement).click();
    expect(store.goToCheckout).toHaveBeenCalledOnceWith('res-9');
  });
});
