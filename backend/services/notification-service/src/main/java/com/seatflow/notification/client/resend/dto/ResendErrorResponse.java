package com.seatflow.notification.client.resend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ResendErrorResponse(
        @JsonProperty("message") String message,
        @JsonProperty("name") String name,
        @JsonProperty("statusCode") Integer statusCode
) {
}
