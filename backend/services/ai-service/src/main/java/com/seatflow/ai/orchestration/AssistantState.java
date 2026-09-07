package com.seatflow.ai.orchestration;

/**
 * Canonical application-owned conversation states for TASK-P15-004.
 *
 * <p>The application derives state from validated orchestration/tool results. The model cannot emit
 * an arbitrary trusted state string; model prose is presentation only.
 */
public enum AssistantState {
    IDLE,
    DISCOVERING,
    PROPOSAL_READY,
    CONFIRMATION_REQUIRED,
    RESERVATION_CREATED,
    ERROR_RECOVERABLE,
    EXPIRED
}
