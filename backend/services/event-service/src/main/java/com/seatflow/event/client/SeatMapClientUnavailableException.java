package com.seatflow.event.client;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.BusinessException;

public class SeatMapClientUnavailableException extends BusinessException {

    public SeatMapClientUnavailableException(String message) {
        super(message, ErrorCode.INTERNAL_SERVER_ERROR, 503);
    }

    public SeatMapClientUnavailableException(String message, Throwable cause) {
        super(message, cause, ErrorCode.INTERNAL_SERVER_ERROR, 503);
    }
}
