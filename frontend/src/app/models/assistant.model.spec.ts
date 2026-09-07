import {
  chatErrorMessage,
  confirmationErrorMessage,
  isAssistantAvailable,
  isKnownCardType,
  isRetrySafeConfirmationCode,
  minorToMajor,
  toMajorAmount,
  toReservationCreatedCard,
} from './assistant.model';

describe('assistant.model helpers', () => {
  it('converts minor units to major units centrally', () => {
    expect(minorToMajor(20000)).toBe(200);
    expect(minorToMajor(null)).toBe(0);
    expect(minorToMajor(undefined)).toBe(0);
  });

  it('normalizes major-unit decimals from confirm payloads', () => {
    expect(toMajorAmount('200.50')).toBeCloseTo(200.5, 2);
    expect(toMajorAmount(100)).toBe(100);
    expect(toMajorAmount(null)).toBe(0);
  });

  it('builds RESERVATION_CREATED views only from 201 payloads', () => {
    const card = toReservationCreatedCard({
      reservationId: 'res-1',
      eventSessionId: 'sess-1',
      seatIds: ['s-1'],
      seatLabels: ['A1'],
      totalAmount: '200.00',
      currency: 'RON',
      reservationStatus: 'PENDING',
      expiresAt: '2026-10-10T18:15:00Z',
      checkoutRoute: '/checkout/res-1',
    });

    expect(card.type).toBe('RESERVATION_CREATED');
    expect(card.expiresAt).toBe('2026-10-10T18:15:00Z');
    expect(card.totalAmountMajor).toBeCloseTo(200, 2);
  });

  it('maps chat error codes to safe messages without provider details', () => {
    expect(chatErrorMessage('AI_RATE_LIMITED')).toContain('temporarily busy');
    expect(chatErrorMessage('AI_RATE_LIMITED')).not.toContain('quota');
    expect(chatErrorMessage('AI_DISABLED')).toContain('unavailable');
    expect(chatErrorMessage('SOMETHING_ELSE')).toContain('try again');
  });

  it('maps confirmation codes without auto-substitution wording', () => {
    expect(confirmationErrorMessage('SEATS_NO_LONGER_AVAILABLE')).toContain('no longer available');
    expect(confirmationErrorMessage('PRICE_CHANGED')).toContain('price changed');
    expect(isRetrySafeConfirmationCode('RESERVATION_RESULT_UNKNOWN_RETRY_SAFE')).toBeTrue();
    expect(isRetrySafeConfirmationCode('PRICE_CHANGED')).toBeFalse();
  });

  it('detects assistant availability from feature status', () => {
    expect(isAssistantAvailable({ enabled: true, state: 'READY', model: 'm' })).toBeTrue();
    expect(isAssistantAvailable({ enabled: true, state: 'RATE_LIMITED', model: 'm' })).toBeFalse();
    expect(isAssistantAvailable({ enabled: false, state: 'DISABLED', model: null })).toBeFalse();
    expect(isAssistantAvailable(null)).toBeFalse();
  });

  it('recognizes known card types and flags future ones as unknown', () => {
    expect(isKnownCardType('RESERVATION_PROPOSAL')).toBeTrue();
    expect(isKnownCardType('RESERVATION_CREATED')).toBeTrue();
    expect(isKnownCardType('FUTURE_CARD')).toBeFalse();
  });
});
