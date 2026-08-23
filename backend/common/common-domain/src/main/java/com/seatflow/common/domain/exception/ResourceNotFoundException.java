package com.seatflow.common.domain.exception;

import com.seatflow.common.domain.enums.ErrorCode;

public class ResourceNotFoundException extends BusinessException {
    public ResourceNotFoundException(String message) {
        super(message, ErrorCode.RESOURCE_NOT_FOUND, 404);
    }

    public ResourceNotFoundException(String resourceName, Object identifier) {
        super(String.format("%s not found with identifier: %s", resourceName, identifier), ErrorCode.RESOURCE_NOT_FOUND, 404);
    }
}
