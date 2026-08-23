package com.seatflow.common.domain.dto;

import java.time.Instant;
import java.util.List;

public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String errorCode,
        String message,
        String path,
        String correlationId,
        List<ValidationError> validationErrors
) {
    public static ApiErrorResponse of(int status, String error, String errorCode, String message, String path, String correlationId) {
        return new ApiErrorResponse(Instant.now(), status, error, errorCode, message, path, correlationId, List.of());
    }

    public static ApiErrorResponse withValidation(int status, String error, String errorCode, String message, String path, String correlationId, List<ValidationError> errors) {
        return new ApiErrorResponse(Instant.now(), status, error, errorCode, message, path, correlationId, errors != null ? errors : List.of());
    }
}
