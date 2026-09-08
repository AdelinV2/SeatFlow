package com.seatflow.ai.proposal;

/**
 * Lifecycle states for a secure server-side reservation proposal (TASK-P15-005 section 5).
 *
 * <p>{@code ACTIVE} is the only state that may proceed to live revalidation and a Reservation
 * Service write. {@code CONSUMED} means a hold was authoritatively created; {@code SUPERSEDED}
 * means a newer proposal for the same conversation replaced it or the conversation was reset;
 * {@code EXPIRED} means the 5-minute proposal TTL elapsed. The TTL is not a seat hold.
 */
public enum ProposalStatus {
    ACTIVE,
    CONSUMED,
    SUPERSEDED,
    EXPIRED
}
