package com.seatflow.common.domain.dto;

public record ValidationError(
        String field,
        String message,
        Object rejectedValue
) {}
