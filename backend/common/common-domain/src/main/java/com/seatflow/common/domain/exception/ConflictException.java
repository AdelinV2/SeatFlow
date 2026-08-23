package com.seatflow.common.domain.exception;

import com.seatflow.common.domain.enums.ErrorCode;

public class ConflictException extends BusinessException {
    public ConflictException(String message, ErrorCode errorCode) {
        super(message, errorCode, 409);
    }

    public ConflictException(String message) {
        super(message, ErrorCode.CONFLICT, 409);
    }
}
