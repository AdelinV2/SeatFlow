package com.seatflow.common.domain.exception;

import com.seatflow.common.domain.enums.ErrorCode;

public class ValidationException extends BusinessException {
    public ValidationException(String message, ErrorCode errorCode) {
        super(message, errorCode, 400);
    }

    public ValidationException(String message) {
        super(message, ErrorCode.INVALID_REQUEST, 400);
    }
}
