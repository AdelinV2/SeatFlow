import { EventSession } from '../../models/event.model';

/**
 * Shared customer-bookability predicate for event sessions (TASK-P12-006, REV-004).
 *
 * A session is customer-bookable when it is SCHEDULED, starts strictly in the
 * future, has not ended, and the current time falls inside its ticket sale
 * window (when a window is configured). Sale-window fields are optional: an
 * absent `saleStartsAt` means sales open on publish, and an absent
 * `saleEndsAt` means sales run until the session starts.
 *
 * Canonical rule — used by the session selector (click path) and by deep-link
 * restoration in event-detail and seat-selection. Backend enforcement remains
 * authoritative (the reservation service rejects sessions whose `startsAt` is
 * not after now); this predicate only keeps the UI from offering, mapping, or
 * linking into sessions the server would reject at hold time — including
 * already-started but not-yet-ended showings.
 */
export function isSessionCustomerBookable(session: EventSession, now: Date = new Date()): boolean {
  if (!session || session.status !== 'SCHEDULED') {
    return false;
  }
  const nowMs = now.getTime();
  if (Number.isNaN(nowMs)) {
    return false;
  }
  // The reservation service rejects any session that has already started, so
  // the UI must not offer already-started but not-yet-ended showings either.
  const startsAtMs = new Date(session.startsAt).getTime();
  if (Number.isNaN(startsAtMs) || startsAtMs <= nowMs) {
    return false;
  }
  const endsAtMs = new Date(session.endsAt).getTime();
  if (Number.isNaN(endsAtMs) || endsAtMs <= nowMs) {
    return false;
  }
  if (session.saleStartsAt) {
    const saleStartMs = new Date(session.saleStartsAt).getTime();
    if (Number.isNaN(saleStartMs) || saleStartMs > nowMs) {
      return false;
    }
  }
  if (session.saleEndsAt) {
    const saleEndMs = new Date(session.saleEndsAt).getTime();
    if (Number.isNaN(saleEndMs) || saleEndMs <= nowMs) {
      return false;
    }
  }
  return true;
}
