import { EventSession } from '../../models/event.model';
import { isSessionCustomerBookable } from './session-booking-eligibility';

describe('isSessionCustomerBookable', () => {
  const base: EventSession = {
    id: 'sess-1',
    eventId: 'ev-1',
    startsAt: '2027-06-01T18:00:00Z',
    endsAt: '2027-06-01T20:00:00Z',
    status: 'SCHEDULED',
    timezone: 'UTC',
  };
  const now = new Date('2026-09-05T12:00:00Z');

  it('accepts a scheduled session with an open sale window', () => {
    expect(
      isSessionCustomerBookable(
        { ...base, saleStartsAt: '2026-01-01T00:00:00Z', saleEndsAt: '2027-05-01T00:00:00Z' },
        now,
      ),
    ).toBeTrue();
  });

  it('accepts a scheduled session without any sale window', () => {
    expect(isSessionCustomerBookable({ ...base }, now)).toBeTrue();
  });

  it('rejects a session whose sale window has not opened yet', () => {
    expect(
      isSessionCustomerBookable({ ...base, saleStartsAt: '2027-01-01T00:00:00Z' }, now),
    ).toBeFalse();
  });

  it('rejects a session whose sale window has closed', () => {
    expect(
      isSessionCustomerBookable({ ...base, saleEndsAt: '2026-01-01T00:00:00Z' }, now),
    ).toBeFalse();
  });

  it('rejects cancelled and completed sessions', () => {
    expect(isSessionCustomerBookable({ ...base, status: 'CANCELLED' }, now)).toBeFalse();
    expect(isSessionCustomerBookable({ ...base, status: 'COMPLETED' }, now)).toBeFalse();
  });

  it('rejects sessions that have already ended', () => {
    expect(
      isSessionCustomerBookable(
        { ...base, startsAt: '2020-01-01T18:00:00Z', endsAt: '2020-01-01T20:00:00Z' },
        now,
      ),
    ).toBeFalse();
  });

  it('rejects an already-started but not-yet-ended session (REV-004 FIX-2)', () => {
    expect(
      isSessionCustomerBookable(
        {
          ...base,
          startsAt: new Date(now.getTime() - 30 * 60 * 1000).toISOString(),
          endsAt: new Date(now.getTime() + 90 * 60 * 1000).toISOString(),
        },
        now,
      ),
    ).toBeFalse();
  });

  it('rejects a session starting exactly now', () => {
    expect(
      isSessionCustomerBookable({ ...base, startsAt: now.toISOString() }, now),
    ).toBeFalse();
  });
});
