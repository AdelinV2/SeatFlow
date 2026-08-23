package com.seatflow.common.observability.handler;

import com.seatflow.common.domain.dto.ApiErrorResponse;
import com.seatflow.common.domain.dto.ValidationError;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ConflictException;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.common.observability.context.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @AfterEach
    void tearDown() {
        CorrelationContext.clear();
    }

    @Test
    void shouldMapResourceNotFoundTo404() {
        when(request.getRequestURI()).thenReturn("/api/reservations/1");
        ResponseEntity<ApiErrorResponse> response = handler.handleBusinessException(
                new ResourceNotFoundException("Reservation", "1"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        ApiErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.getCode());
        assertThat(body.status()).isEqualTo(404);
        assertThat(body.message()).contains("Reservation not found");
        assertThat(body.correlationId()).isEqualTo("N/A");
        assertThat(body.validationErrors()).isEmpty();
    }

    @Test
    void shouldMapConflictTo409() {
        when(request.getRequestURI()).thenReturn("/api/reservations2");
        ResponseEntity<ApiErrorResponse> response = handler.handleBusinessException(
                new ConflictException("Seat already held", ErrorCode.SEAT_ALREADY_RESERVED), request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().errorCode()).isEqualTo(ErrorCode.SEAT_ALREADY_RESERVED.getCode());
    }

    @Test
    void shouldMapValidationTo400() {
        when(request.getRequestURI()).thenReturn("/api/x");
        ResponseEntity<ApiErrorResponse> response = handler.handleBusinessException(
                new ValidationException("Too many seats", ErrorCode.MAX_SEATS_EXCEEDED), request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().errorCode()).isEqualTo(ErrorCode.MAX_SEATS_EXCEEDED.getCode());
    }

    @Test
    void shouldMapAccessDeniedTo403() {
        when(request.getRequestURI()).thenReturn("/api/admin");
        ResponseEntity<ApiErrorResponse> response = handler.handleAccessDenied(
                new AccessDeniedException("denied"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody().errorCode()).isEqualTo(ErrorCode.FORBIDDEN.getCode());
        assertThat(response.getBody().message()).contains("Access denied");
    }

    @Test
    void shouldMapUnhandledExceptionTo500WithoutLeakingDetails() {
        when(request.getRequestURI()).thenReturn("/api/x");
        RuntimeException cause = new RuntimeException("DB connection refused: secrets...");
        ResponseEntity<ApiErrorResponse> response = handler.handleUnhandledException(cause, request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        ApiErrorResponse body = response.getBody();
        assertThat(body.errorCode()).isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR.getCode());
        assertThat(body.message()).doesNotContain("DB connection refused");
        assertThat(body.message()).contains("correlation ID");
    }

    @Test
    void shouldMapMethodArgumentNotValidTo400WithFieldErrors() {
        when(request.getRequestURI()).thenReturn("/api/reservations");
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult br = mock(BindingResult.class);
        FieldError fieldError = new FieldError("reservation", "seatIds", "must not be empty");
        when(ex.getBindingResult()).thenReturn(br);
        when(br.getFieldErrors()).thenReturn(List.of(fieldError));
        when(ex.getMessage()).thenReturn("validation failed");

        ResponseEntity<ApiErrorResponse> response = handler.handleValidationException(ex, request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        ApiErrorResponse body = response.getBody();
        assertThat(body.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST.getCode());
        assertThat(body.validationErrors()).hasSize(1);
        ValidationError ve = body.validationErrors().get(0);
        assertThat(ve.field()).isEqualTo("seatIds");
        assertThat(ve.message()).isEqualTo("must not be empty");
    }
}
