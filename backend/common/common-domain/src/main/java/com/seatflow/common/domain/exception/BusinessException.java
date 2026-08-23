package com.seatflow.common.domain.exception;

import com.seatflow.common.domain.enums.ErrorCode;

public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    private final int httpStatus;

    public BusinessException(String message, ErrorCode errorCode, int httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public BusinessException(String message, Throwable cause, ErrorCode errorCode, int httpStatus) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
