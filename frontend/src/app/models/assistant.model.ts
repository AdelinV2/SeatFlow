/**
 * Strict typed contracts for the SeatFlow AI assistant (TASK-P15-006).
 *
 * Mirrors the authoritative backend DTOs from ai-service:
 * - AssistantChatRequest / AssistantChatResponse
 * - AssistantCard (EVENT | SESSION | SEAT_SET | RESERVATION_PROPOSAL | INFO)
 *   plus the frontend-only RESERVATION_CREATED confirmed-hold view built from
 *   the 201 ReservationCreatedCard returned by POST /api/ai/proposals/{id}/confirm.
 * - AssistantError / AssistantChatErrorCode
 * - AiFeatureStatusResponse / AiFeatureState
 * - ProposalConfirmationCode (P15-005 confirmation failure codes)
 *
 * Rules enforced here:
 * - No `any` for authoritative data.
 * - Money travels in minor units + ISO-4217 currency on chat cards and is
 *   formatted centrally via {@link minorToMajor} + the shared `sfCurrency` pipe.
 * - Seat adjacency is derived ONLY from the backend `contiguous` boolean.
 * - Hold countdowns use the authoritative `expiresAt`, never `Date.now() + 15m`.
 * - Unknown future card types degrade to a safe unsupported/info rendering.
 */

/** Canonical application-owned conversation states (backend AssistantState). */
export type AssistantState =
  | 'IDLE'
  | 'DISCOVERING'
  | 'PROPOSAL_READY'
  | 'CONFIRMATION_REQUIRED'
  | 'RESERVATION_CREATED'
  | 'ERROR_RECOVERABLE'
  | 'EXPIRED';

/** Stable AI feature states (backend AiFeatureState). */
export type AiFeatureState =
  | 'DISABLED'
  | 'READY'
  | 'MISCONFIGURED'
  | 'RATE_LIMITED'
  | 'PROVIDER_UNAVAILABLE';

/** Stable chat error codes (backend AssistantChatErrorCode). */
export type AssistantChatErrorCode =
  | 'AI_DISABLED'
  | 'AI_MISCONFIGURED'
  | 'AI_RATE_LIMITED'
  | 'AI_PROVIDER_TIMEOUT'
  | 'AI_PROVIDER_UNAVAILABLE'
  | 'AI_MODEL_UNAVAILABLE'
  | 'AI_RESPONSE_INVALID';

/**
 * Canonical P15-005 confirmation failure codes. Not-found/forbidden map to 404
 * (anti-enumeration), expired to 410, stale/conflict outcomes to 409, downstream
 * outage to 503, and ambiguous timeout-after-submit to 202 (retry-safe).
 */
export type ProposalConfirmationCode =
  | 'PROPOSAL_NOT_FOUND'
  | 'PROPOSAL_FORBIDDEN'
  | 'PROPOSAL_EXPIRED'
  | 'PROPOSAL_SUPERSEDED'
  | 'PROPOSAL_ALREADY_CONSUMED'
  | 'STALE_PROPOSAL'
  | 'SEATS_NO_LONGER_AVAILABLE'
  | 'PRICE_CHANGED'
  | 'SESSION_NOT_BOOKABLE'
  | 'RESERVATION_CONFLICT'
  | 'RESERVATION_SERVICE_UNAVAILABLE'
  | 'RESERVATION_RESULT_UNKNOWN_RETRY_SAFE';

/** Known structured card discriminators. */
export type KnownAssistantCardType =
  | 'EVENT'
  | 'SESSION'
  | 'SEAT_SET'
  | 'RESERVATION_PROPOSAL'
  | 'RESERVATION_CREATED'
  | 'INFO';

export interface EventAssistantCard {
  readonly type: 'EVENT';
  readonly eventId: string;
  readonly title: string | null;
  readonly category: string | null;
  readonly venueSummary: string | null;
}

export interface SessionAssistantCard {
  readonly type: 'SESSION';
  readonly eventId: string | null;
  readonly eventSessionId: string;
  readonly startsAt: string | null;
  readonly endsAt: string | null;
  readonly status: string | null;
  readonly bookable: string | null;
}

export interface SeatSetAssistantCard {
  readonly type: 'SEAT_SET';
  readonly eventSessionId: string | null;
  readonly seatIds: readonly string[];
  readonly seatLabels: readonly string[];
  readonly sectionSummary: string | null;
  readonly totalPriceMinor: number | null;
  readonly currency: string | null;
  /** True only for same-section, same-row, consecutive seat numbers. */
  readonly contiguous: boolean;
  readonly reasons: readonly string[];
}

export interface ProposalAssistantCard {
  readonly type: 'RESERVATION_PROPOSAL';
  readonly eventId: string | null;
  readonly eventSessionId: string | null;
  readonly proposalId: string;
  readonly seatIds: readonly string[];
  readonly seatLabels: readonly string[];
  readonly sectionSummary: string | null;
  readonly totalPriceMinor: number | null;
  readonly currency: string | null;
  readonly contiguous: boolean;
  readonly reasons: readonly string[];
  readonly requiresExplicitConfirmation: boolean;
  /** Always false: a proposal is explicitly not a hold. */
  readonly seatsHeld: boolean;
}

/**
 * Frontend-only confirmed-hold view. Never constructed from model prose;
 * only from the authoritative 201 ReservationCreatedCard of the confirm endpoint.
 */
export interface ReservationCreatedAssistantCard {
  readonly type: 'RESERVATION_CREATED';
  readonly reservationId: string;
  readonly eventSessionId: string | null;
  readonly seatIds: readonly string[];
  readonly seatLabels: readonly string[];
  /** Major units (backend BigDecimal) for direct use with the sfCurrency pipe. */
  readonly totalAmountMajor: number;
  readonly currency: string;
  readonly reservationStatus: string | null;
  /** Authoritative hold expiry (UTC ISO). Drives the countdown. */
  readonly expiresAt: string;
  readonly checkoutRoute: string;
}

export interface InfoAssistantCard {
  readonly type: 'INFO';
  readonly infoTitle: string | null;
  readonly infoMessage: string | null;
}

/**
 * Safe fallback for future/unknown card discriminators. Rendered as a neutral
 * informational notice; never throws, never claims booking state.
 */
export interface UnknownAssistantCard {
  readonly type: string;
  readonly infoTitle?: string | null;
  readonly infoMessage?: string | null;
}

export type AssistantCard =
  | EventAssistantCard
  | SessionAssistantCard
  | SeatSetAssistantCard
  | ProposalAssistantCard
  | ReservationCreatedAssistantCard
  | InfoAssistantCard
  | UnknownAssistantCard;

export interface AssistantChatRequest {
  readonly conversationId?: string | null;
  readonly message: string;
}

export interface AssistantError {
  readonly code: AssistantChatErrorCode;
  readonly message: string;
}

export interface AssistantChatResponse {
  readonly conversationId: string;
  readonly assistantMessage: string;
  readonly state: AssistantState;
  readonly cards: readonly AssistantCard[];
  readonly suggestedActions: readonly string[];
  readonly error?: AssistantError | null;
}

export interface AiFeatureStatus {
  readonly enabled: boolean;
  readonly state: AiFeatureState;
  readonly model: string | null;
}

/** Authoritative 201 payload of POST /api/ai/proposals/{id}/confirm. */
export interface ReservationCreatedPayload {
  readonly reservationId: string;
  readonly eventSessionId: string | null;
  readonly seatIds: readonly string[];
  readonly seatLabels: readonly string[];
  readonly totalAmount: number | string;
  readonly currency: string;
  readonly reservationStatus: string | null;
  readonly expiresAt: string;
  readonly checkoutRoute: string;
}

/** Machine-readable confirmation failure body. */
export interface ProposalConfirmationError {
  readonly code: ProposalConfirmationCode | string;
  readonly message: string;
}

/** Presentation-level chat thread entry (Angular memory only, never persisted). */
export type AssistantMessageRole = 'user' | 'assistant' | 'system';

export interface AssistantUiMessage {
  readonly id: string;
  readonly role: AssistantMessageRole;
  readonly text: string;
  readonly cards: readonly AssistantCard[];
  readonly state?: AssistantState | null;
  readonly errorCode?: string | null;
  readonly errorMessage?: string | null;
  readonly createdAt: string;
}

/** Per-proposal terminal UI status after a confirm attempt. */
export interface ProposalCardStatus {
  readonly disabled: boolean;
  readonly code: string | null;
  readonly message: string | null;
  readonly retrySafe: boolean;
}

/** Backend 2000-character input bound; mirrored client-side. */
export const ASSISTANT_MAX_MESSAGE_LENGTH = 2000;

/** Starter prompts are ordinary chat messages (never confirmation actions). */
export const ASSISTANT_STARTER_PROMPTS: readonly string[] = [
  'Find events this weekend.',
  'Find two seats together for Hamlet under 250 RON.',
  'Show the best seats close to the stage.',
];

/** Non-blocking activity states; carry no tool payloads. */
export const ASSISTANT_ACTIVITY_STATES: readonly string[] = [
  'Searching events…',
  'Checking sessions…',
  'Checking live seat availability…',
  'Comparing seat options…',
];

/** Converts backend minor units to major units for the sfCurrency pipe. */
export function minorToMajor(totalPriceMinor: number | null | undefined): number {
  if (totalPriceMinor === null || totalPriceMinor === undefined || !Number.isFinite(totalPriceMinor)) {
    return 0;
  }
  return totalPriceMinor / 100;
}

/** Normalizes a major-unit decimal (string or number) for the sfCurrency pipe. */
export function toMajorAmount(value: number | string | null | undefined): number {
  if (value === null || value === undefined) {
    return 0;
  }
  const numeric = typeof value === 'string' ? Number(value) : value;
  return Number.isFinite(numeric) ? numeric : 0;
}

/** Type guard for the known card discriminators. */
export function isKnownCardType(type: string): type is KnownAssistantCardType {
  return (
    type === 'EVENT' ||
    type === 'SESSION' ||
    type === 'SEAT_SET' ||
    type === 'RESERVATION_PROPOSAL' ||
    type === 'RESERVATION_CREATED' ||
    type === 'INFO'
  );
}

/** Builds the frontend RESERVATION_CREATED view exclusively from a 201 payload. */
export function toReservationCreatedCard(payload: ReservationCreatedPayload): ReservationCreatedAssistantCard {
  return {
    type: 'RESERVATION_CREATED',
    reservationId: payload.reservationId,
    eventSessionId: payload.eventSessionId,
    seatIds: payload.seatIds ?? [],
    seatLabels: payload.seatLabels ?? [],
    totalAmountMajor: toMajorAmount(payload.totalAmount),
    currency: payload.currency,
    reservationStatus: payload.reservationStatus,
    expiresAt: payload.expiresAt,
    checkoutRoute: payload.checkoutRoute,
  };
}

const CHAT_ERROR_MESSAGES: Record<AssistantChatErrorCode, string> = {
  AI_DISABLED: 'The SeatFlow Assistant is currently unavailable. You can continue browsing events and booking as usual.',
  AI_MISCONFIGURED:
    'The SeatFlow Assistant is currently unavailable due to a configuration issue. You can continue browsing events and booking as usual.',
  AI_RATE_LIMITED:
    'The assistant is temporarily busy. Please wait a moment and try again. The rest of SeatFlow works normally.',
  AI_PROVIDER_TIMEOUT:
    'The assistant took too long to respond. You can try again. The rest of SeatFlow works normally.',
  AI_PROVIDER_UNAVAILABLE:
    'The assistant service is temporarily unreachable. You can try again shortly. The rest of SeatFlow works normally.',
  AI_MODEL_UNAVAILABLE:
    'The assistant is temporarily unavailable. You can continue browsing events and booking as usual.',
  AI_RESPONSE_INVALID:
    'The assistant returned an unreadable answer. Please try rephrasing your request.',
};

/** User-safe message for chat error codes; never leaks provider payloads. */
export function chatErrorMessage(code: string | null | undefined): string {
  if (code !== null && code !== undefined && code in CHAT_ERROR_MESSAGES) {
    return CHAT_ERROR_MESSAGES[code as AssistantChatErrorCode];
  }
  return 'The assistant could not complete that request. Please try again.';
}

const CONFIRMATION_ERROR_MESSAGES: Record<string, string> = {
  PROPOSAL_EXPIRED: 'This proposal has expired. Ask the assistant for fresh seat options.',
  PROPOSAL_SUPERSEDED: 'This proposal was replaced by a newer one. Please use the latest proposal.',
  PROPOSAL_ALREADY_CONSUMED: 'This proposal was already used. Ask the assistant for fresh seat options.',
  PROPOSAL_NOT_FOUND: 'This proposal is no longer available. Ask the assistant for fresh seat options.',
  PROPOSAL_FORBIDDEN: 'This proposal is no longer available. Ask the assistant for fresh seat options.',
  STALE_PROPOSAL: 'These seats may have changed. Ask the assistant for fresh options — no other seats were booked for you.',
  SEATS_NO_LONGER_AVAILABLE:
    'These seats are no longer available. Ask the assistant for fresh options — no other seats were booked for you.',
  PRICE_CHANGED: 'The price changed since this proposal. Please request a new proposal before confirming.',
  SESSION_NOT_BOOKABLE: 'This session can no longer be booked. Ask the assistant for other sessions.',
  RESERVATION_CONFLICT:
    'This reservation could not be created due to a conflict. No other seats were booked for you — ask for fresh options.',
  RESERVATION_SERVICE_UNAVAILABLE:
    'The booking service is temporarily unreachable. Your seats were not held — you can try again shortly.',
  RESERVATION_RESULT_UNKNOWN_RETRY_SAFE:
    'The result is uncertain after a timeout. It is safe to retry this confirmation — no duplicate hold will be created.',
};

/** User-safe message for confirmation failure codes; never auto-substitutes seats. */
export function confirmationErrorMessage(code: string | null | undefined): string {
  if (code !== null && code !== undefined && code in CONFIRMATION_ERROR_MESSAGES) {
    return CONFIRMATION_ERROR_MESSAGES[code];
  }
  return 'This confirmation could not be completed. Ask the assistant for fresh seat options.';
}

/** Whether a confirmation failure is safe to retry with the same proposal ID. */
export function isRetrySafeConfirmationCode(code: string | null | undefined): boolean {
  return code === 'RESERVATION_RESULT_UNKNOWN_RETRY_SAFE';
}

/** Whether the AI feature flag/state means the assistant is usable. */
export function isAssistantAvailable(status: AiFeatureStatus | null): boolean {
  return status !== null && status.enabled && status.state === 'READY';
}
