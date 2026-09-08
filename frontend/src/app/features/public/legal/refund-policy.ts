/**
 * Narrowly-scoped public refund-policy display constants (TASK-P16-003).
 *
 * This module owns only the customer-facing wording fragments for the 24-hour
 * cutoff and full-reservation scope so `/legal/refunds` copy cannot drift into
 * a contradictory value without a test failure. It intentionally does NOT
 * encode Terms/Privacy documents, DTO schemas, Kafka topics, Stripe keys, or
 * any server-side enforcement logic. The server remains the authority;
 * see ADR-012 and the Phase 13 refund overview.
 */
export const REFUND_CUTOFF_HOURS = 24;

export const REFUND_CUTOFF_LABEL = '24 hours';

export const REFUND_SCOPE_LABEL = 'full reservation';

/**
 * Canonical one-line summary used by public refund copy and its tests.
 * Any change to the cutoff or scope wording must update the focused unit test
 * in `refund-policy.spec.ts` first.
 */
export function refundCutoffSummary(): string {
  return (
    `A confirmed, paid reservation is eligible for a ${REFUND_SCOPE_LABEL} refund ` +
    `only when at least ${REFUND_CUTOFF_LABEL} remain before the event session starts.`
  );
}

/**
 * Exact-boundary statement: exactly 24:00:00 remaining is eligible, anything
 * below is not eligible under the current policy.
 */
export function refundBoundaryStatement(): string {
  return (
    `Exactly ${REFUND_CUTOFF_LABEL} (24:00:00) remaining is eligible; ` +
    `anything below ${REFUND_CUTOFF_LABEL} is not eligible.`
  );
}
