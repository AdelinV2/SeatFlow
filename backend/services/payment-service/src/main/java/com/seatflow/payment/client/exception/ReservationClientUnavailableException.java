package com.seatflow.payment.client.exception;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.BusinessException;

/**
 * Raised when the synchronous call to {@code reservation-service} fails and no fallback can be
 * served. Mapped by the shared {@code GlobalExceptionHandler} to HTTP 503.
 */
public class ReservationClientUnavailableException extends BusinessException {

    public ReservationClientUnavailableException(String message) {
        super(message, ErrorCode.INTERNAL_SERVER_ERROR, 503);
    }

    public ReservationClientUnavailableException(String message, Throwable cause) {
        super(message, cause, ErrorCode.INTERNAL_SERVER_ERROR, 503);
    }
}
