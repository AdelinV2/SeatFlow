import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { UserContextService } from '../../../../core/auth/user-context.service';
import { ProposalAssistantCard, SeatSetAssistantCard } from '../../../../models/assistant.model';
import { AssistantStore } from '../../../../services/assistant-store.service';
import { AssistantProposalCardComponent } from './assistant-proposal-card.component';
import { AssistantSeatCardComponent } from '../assistant-seat-card/assistant-seat-card.component';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';

function seatCard(contiguous: boolean): SeatSetAssistantCard {
  return {
    type: 'SEAT_SET',
    eventSessionId: 'sess-1',
    seatIds: ['s-1', 's-2'],
    seatLabels: ['A1', 'A2'],
    sectionSummary: 'Stalls • Row A',
    totalPriceMinor: 20000,
    currency: 'RON',
    contiguous,
    reasons: ['Close to the stage'],
  };
}

function proposal(): ProposalAssistantCard {
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

describe('Assistant seat/proposal cards', () => {
  let store: AssistantStore;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AssistantSeatCardComponent, AssistantProposalCardComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        UserContextService,
      ],
    }).compileComponents();
    store = TestBed.inject(AssistantStore);
    TestBed.inject(UserContextService).setUser({
      id: 'user-1',
      email: 'user@seatflow.test',
      name: 'Test User',
      roles: ['ROLE_CUSTOMER'],
    });
  });

  it('shows "Seats together" only when backend contiguous is true', () => {
    const together: ComponentFixture<AssistantSeatCardComponent> = TestBed.createComponent(
      AssistantSeatCardComponent,
    );
    together.componentRef.setInput('card', seatCard(true));
    together.detectChanges();
    expect(together.nativeElement.textContent).toContain('Seats together');

    const apart: ComponentFixture<AssistantSeatCardComponent> = TestBed.createComponent(
      AssistantSeatCardComponent,
    );
    apart.componentRef.setInput('card', seatCard(false));
    apart.detectChanges();
    expect(apart.nativeElement.textContent).not.toContain('Seats together');
  });

  it('proposal card states seats are not held and confirms via the store', () => {
    const fixture: ComponentFixture<AssistantProposalCardComponent> =
      TestBed.createComponent(AssistantProposalCardComponent);
    fixture.componentRef.setInput('card', proposal());
    fixture.detectChanges();

    const text: string = fixture.nativeElement.textContent ?? '';
    expect(text).toContain('These seats are not held yet.');

    const confirm = spyOn(store, 'confirmProposal');
    const button = fixture.nativeElement.querySelector(
      '.proposal-card__confirm',
    ) as HTMLButtonElement;
    button.click();

    expect(confirm).toHaveBeenCalledOnceWith('prop-1');
  });

  it('confirm button is disabled while the request is in flight', () => {
    store.confirmingProposals.set(['prop-1']);
    const fixture: ComponentFixture<AssistantProposalCardComponent> =
      TestBed.createComponent(AssistantProposalCardComponent);
    fixture.componentRef.setInput('card', proposal());
    fixture.detectChanges();

    const button = fixture.nativeElement.querySelector(
      '.proposal-card__confirm',
    ) as HTMLButtonElement;
    expect(button.disabled).toBeTrue();
    expect(fixture.nativeElement.textContent).toContain('Confirming…');
  });

  it('dismiss action marks the proposal without confirming', () => {
    const fixture: ComponentFixture<AssistantProposalCardComponent> =
      TestBed.createComponent(AssistantProposalCardComponent);
    fixture.componentRef.setInput('card', proposal());
    fixture.detectChanges();

    const confirm = spyOn(store, 'confirmProposal');
    const dismiss = fixture.nativeElement.querySelector(
      '.proposal-card__secondary',
    ) as HTMLButtonElement;
    expect(dismiss.textContent).toContain('Find different seats');
    dismiss.click();

    expect(confirm).not.toHaveBeenCalled();
    expect(store.proposalStatus('prop-1')?.disabled).toBeTrue();
  });

  it('REV-002: retry-safe failure exposes exactly one enabled "Retry confirmation" button', () => {
    const httpMock = TestBed.inject(HttpTestingController);
    store.confirmProposal('prop-1');
    httpMock
      .expectOne('/api/ai/proposals/prop-1/confirm')
      .flush(
        { code: 'RESERVATION_RESULT_UNKNOWN_RETRY_SAFE', message: 'Unknown.' },
        { status: 202, statusText: 'Accepted' },
      );

    const fixture: ComponentFixture<AssistantProposalCardComponent> =
      TestBed.createComponent(AssistantProposalCardComponent);
    fixture.componentRef.setInput('card', proposal());
    fixture.detectChanges();

    const buttons = Array.from(
      fixture.nativeElement.querySelectorAll('button'),
    ) as HTMLButtonElement[];
    const retryButtons = buttons.filter(
      (button) => (button.textContent ?? '').trim() === 'Retry confirmation',
    );
    expect(retryButtons.length).toBe(1);
    expect(retryButtons[0].disabled).toBeFalse();
  });

  it('router is not needed for card unit assertions', () => {
    expect(TestBed.inject(Router)).toBeTruthy();
  });
});
