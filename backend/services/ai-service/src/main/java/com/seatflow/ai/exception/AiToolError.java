package com.seatflow.ai.exception;

/**
 * Stable machine-readable taxonomy for AI tool failures (TASK-P15-002 section 9).
 *
 * <p>Downstream HTTP/timeout failures are mapped into this small set so the model receives a short
 * safe message plus a stable category instead of raw HTTP bodies or exception class names.
 */
public enum AiToolError {
    INVALID_TOOL_ARGUMENT,
    UNAUTHENTICATED,
    FORBIDDEN,
    NOT_FOUND,
    DOWNSTREAM_TIMEOUT,
    DOWNSTREAM_UNAVAILABLE,
    UNEXPECTED_TOOL_FAILURE
}
