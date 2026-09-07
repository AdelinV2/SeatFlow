package com.seatflow.ai.service;

/**
 * Canonical P15-005 confirmation failure codes (TASK-P15-005 section 10) with their HTTP mapping.
 *
 * <p>Not-found and forbidden both map to {@code 404} (repo anti-enumeration policy: never confirm
 * another user's proposal exists). Expired maps to {@code 410}; superseded/consumed and all
 * stale/revalidation/conflict outcomes map to {@code 409} (fresh proposal required, never silent
 * substitution); downstream outage maps to {@code 503}; ambiguous timeout-after-submit maps to
 * {@code 202} (retry-safe with the same idempotency key, never claim success/failure).
 */
public enum ProposalConfirmationCode {
    PROPOSAL_NOT_FOUND(404),
    PROPOSAL_FORBIDDEN(404),
    PROPOSAL_EXPIRED(410),
    PROPOSAL_SUPERSEDED(409),
    PROPOSAL_ALREADY_CONSUMED(409),
    STALE_PROPOSAL(409),
    SEATS_NO_LONGER_AVAILABLE(409),
    PRICE_CHANGED(409),
    SESSION_NOT_BOOKABLE(409),
    RESERVATION_CONFLICT(409),
    RESERVATION_SERVICE_UNAVAILABLE(503),
    RESERVATION_RESULT_UNKNOWN_RETRY_SAFE(202);

    private final int httpStatus;

    ProposalConfirmationCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
