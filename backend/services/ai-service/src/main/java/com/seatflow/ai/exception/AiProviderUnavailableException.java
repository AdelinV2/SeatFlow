package com.seatflow.ai.exception;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.BusinessException;

/**
 * Bounded application-level failure for AI provider calls (TASK-P15-001).
 *
 * <p>Never carries raw provider bodies, auth headers, key fragments, quota metadata, or stack
 * traces to the browser. Use HTTP 503 for misconfiguration/unavailability and 429 for rate
 * limiting (fail fast, no retry storm).
 */
public class AiProviderUnavailableException extends BusinessException {

    public AiProviderUnavailableException(String safeMessage) {
        super(safeMessage, ErrorCode.INTERNAL_SERVER_ERROR, 503);
    }

    public AiProviderUnavailableException(String safeMessage, int httpStatus) {
        super(safeMessage, ErrorCode.INTERNAL_SERVER_ERROR, httpStatus);
    }

    public AiProviderUnavailableException(String safeMessage, Throwable cause) {
        super(safeMessage, cause, ErrorCode.INTERNAL_SERVER_ERROR, 503);
    }
}
