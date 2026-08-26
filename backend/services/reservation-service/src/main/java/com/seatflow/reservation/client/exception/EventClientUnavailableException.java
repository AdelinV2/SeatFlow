package com.seatflow.reservation.client.exception;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.BusinessException;

public class EventClientUnavailableException extends BusinessException {

    public EventClientUnavailableException(String message) {
        super(message, ErrorCode.INTERNAL_SERVER_ERROR, 503);
    }

    public EventClientUnavailableException(String message, Throwable cause) {
        super(message, cause, ErrorCode.INTERNAL_SERVER_ERROR, 503);
    }
}
