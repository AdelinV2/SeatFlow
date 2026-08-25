package com.seatflow.common.observability.handler;

import com.seatflow.common.domain.dto.ApiErrorResponse;
import com.seatflow.common.domain.dto.ValidationError;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.BusinessException;
import com.seatflow.common.observability.context.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiErrorResponse> handleBusinessException(BusinessException ex, HttpServletRequest request) {
        log.warn("Business exception occurred: errorCode={}, message={}, uri={}", ex.getErrorCode(), ex.getMessage(), request.getRequestURI());
        ApiErrorResponse response = new ApiErrorResponse(
            Instant.now(),
            ex.getHttpStatus(),
            HttpStatus.valueOf(ex.getHttpStatus()).getReasonPhrase(),
            ex.getErrorCode().getCode(),
            ex.getMessage(),
            request.getRequestURI(),
            getCorrelationId(),
            List.of()
        );
        return ResponseEntity.status(ex.getHttpStatus()).body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationException(MethodArgumentNotValidException ex, HttpServletRequest request) {
        log.warn("Validation error on request [{}]: {}", request.getRequestURI(), ex.getMessage());
        List<ValidationError> errors = ex.getBindingResult().getFieldErrors().stream()
            .map(this::mapFieldError)
            .toList();

        ApiErrorResponse response = ApiErrorResponse.withValidation(
            HttpStatus.BAD_REQUEST.value(),
            HttpStatus.BAD_REQUEST.getReasonPhrase(),
            ErrorCode.INVALID_REQUEST.getCode(),
            "Validation failed for one or more fields",
            request.getRequestURI(),
            getCorrelationId(),
            errors
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        log.warn("Constraint violation on request [{}]: {}", request.getRequestURI(), ex.getMessage());
        List<ValidationError> errors = ex.getConstraintViolations().stream()
            .map(cv -> new ValidationError(cv.getPropertyPath().toString(), cv.getMessage(), cv.getInvalidValue()))
            .toList();

        ApiErrorResponse response = ApiErrorResponse.withValidation(
            HttpStatus.BAD_REQUEST.value(),
            HttpStatus.BAD_REQUEST.getReasonPhrase(),
            ErrorCode.INVALID_REQUEST.getCode(),
            "Constraint violation occurred",
            request.getRequestURI(),
            getCorrelationId(),
            errors
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleHandlerMethodValidation(HandlerMethodValidationException ex,
                                                                         HttpServletRequest request) {
        log.warn("Handler method validation failed on request [{}]: {}", request.getRequestURI(), ex.getMessage());
        List<ValidationError> errors = ex.getParameterValidationResults().stream()
                .map(result -> {
                    String field = result.getMethodParameter().getParameterName();
                    String message = result.getResolvableErrors().stream()
                            .map(MessageSourceResolvable::getDefaultMessage)
                            .collect(Collectors.joining("; "));
                    return new ValidationError(field, message, result.getArgument());
                })
                .toList();
        ApiErrorResponse response = ApiErrorResponse.withValidation(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                ErrorCode.INVALID_REQUEST.getCode(),
                "Validation failed for one or more parameters",
                request.getRequestURI(),
                getCorrelationId(),
                errors
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.warn("Access denied on request [{}]: {}", request.getRequestURI(), ex.getMessage());
        ApiErrorResponse response = ApiErrorResponse.of(
            HttpStatus.FORBIDDEN.value(),
            HttpStatus.FORBIDDEN.getReasonPhrase(),
            ErrorCode.FORBIDDEN.getCode(),
            "Access denied: insufficient permissions",
            request.getRequestURI(),
            getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnhandledException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled internal server error on request [{}]", request.getRequestURI(), ex);
        ApiErrorResponse response = ApiErrorResponse.of(
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
            ErrorCode.INTERNAL_SERVER_ERROR.getCode(),
            "An unexpected internal error occurred. Please reference the correlation ID when contacting support.",
            request.getRequestURI(),
            getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    private ValidationError mapFieldError(FieldError fieldError) {
        return new ValidationError(
            fieldError.getField(),
            fieldError.getDefaultMessage(),
            fieldError.getRejectedValue()
        );
    }

    private String getCorrelationId() {
        return CorrelationContext.getCorrelationId().orElse("N/A");
    }
}
