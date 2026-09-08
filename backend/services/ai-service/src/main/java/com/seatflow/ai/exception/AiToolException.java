package com.seatflow.ai.exception;

import com.seatflow.common.domain.enums.ErrorCode;

/**
 * Typed AI tool failure carrying a stable {@link AiToolError} category.
 *
 * <p>Extends the shared {@code BusinessException} so the common-observability
 * {@code GlobalExceptionHandler} maps tool failures to the standard error envelope if they ever
 * reach an HTTP boundary. The raw downstream body is never included in the message.
 */
public class AiToolException extends com.seatflow.common.domain.exception.BusinessException {

    private final AiToolError error;

    public AiToolException(AiToolError error, String message) {
        super(message, toErrorCode(error), toHttpStatus(error));
        this.error = error;
    }

    public AiToolException(AiToolError error, String message, Throwable cause) {
        super(message, cause, toErrorCode(error), toHttpStatus(error));
        this.error = error;
    }

    public AiToolError getError() {
        return error;
    }

    private static ErrorCode toErrorCode(AiToolError error) {
        return switch (error) {
            case INVALID_TOOL_ARGUMENT -> ErrorCode.INVALID_REQUEST;
            case UNAUTHENTICATED -> ErrorCode.UNAUTHORIZED;
            case FORBIDDEN -> ErrorCode.FORBIDDEN;
            case NOT_FOUND -> ErrorCode.RESOURCE_NOT_FOUND;
            case DOWNSTREAM_TIMEOUT, DOWNSTREAM_UNAVAILABLE, UNEXPECTED_TOOL_FAILURE ->
                    ErrorCode.INTERNAL_SERVER_ERROR;
        };
    }

    private static int toHttpStatus(AiToolError error) {
        return switch (error) {
            case INVALID_TOOL_ARGUMENT -> 400;
            case UNAUTHENTICATED -> 401;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case DOWNSTREAM_TIMEOUT, DOWNSTREAM_UNAVAILABLE -> 503;
            case UNEXPECTED_TOOL_FAILURE -> 500;
        };
    }
}
