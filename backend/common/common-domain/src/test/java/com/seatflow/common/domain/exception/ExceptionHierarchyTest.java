package com.seatflow.common.domain.exception;

import com.seatflow.common.domain.enums.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExceptionHierarchyTest {

    @Test
    void businessExceptionShouldCarryErrorCodeAndHttpStatus() {
        BusinessException ex = new BusinessException("boom", ErrorCode.INTERNAL_SERVER_ERROR, 500);

        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR);
        assertThat(ex.getHttpStatus()).isEqualTo(500);
        assertThat(ex.getMessage()).isEqualTo("boom");
    }

    @Test
    void businessExceptionShouldPreserveCause() {
        Throwable cause = new IllegalStateException("root");
        BusinessException ex = new BusinessException("boom", cause, ErrorCode.INTERNAL_SERVER_ERROR, 500);

        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void resourceNotFoundExceptionShouldMapTo404() {
        ResourceNotFoundException ex = new ResourceNotFoundException("Reservation not found");

        assertThat(ex).isInstanceOf(BusinessException.class);
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(ex.getHttpStatus()).isEqualTo(404);
    }

    @Test
    void resourceNotFoundExceptionWithIdentifierShouldFormatMessage() {
        ResourceNotFoundException ex = new ResourceNotFoundException("Reservation", "abc-123");

        assertThat(ex.getMessage()).isEqualTo("Reservation not found with identifier: abc-123");
        assertThat(ex.getHttpStatus()).isEqualTo(404);
    }

    @Test
    void conflictExceptionWithErrorCodeShouldUseProvidedCodeAnd409() {
        ConflictException ex = new ConflictException("Seat already held", ErrorCode.SEAT_ALREADY_RESERVED);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SEAT_ALREADY_RESERVED);
        assertThat(ex.getHttpStatus()).isEqualTo(409);
    }

    @Test
    void conflictExceptionWithoutErrorCodeShouldDefaultToConflict() {
        ConflictException ex = new ConflictException("generic conflict");

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(ex.getHttpStatus()).isEqualTo(409);
    }

    @Test
    void validationExceptionWithErrorCodeShouldUseProvidedCodeAnd400() {
        ValidationException ex = new ValidationException("Too many seats", ErrorCode.MAX_SEATS_EXCEEDED);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MAX_SEATS_EXCEEDED);
        assertThat(ex.getHttpStatus()).isEqualTo(400);
    }

    @Test
    void validationExceptionWithoutErrorCodeShouldDefaultToInvalidRequest() {
        ValidationException ex = new ValidationException("invalid");

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(ex.getHttpStatus()).isEqualTo(400);
    }
}
