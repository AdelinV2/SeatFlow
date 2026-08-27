package com.seatflow.notification.client.resend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ResendEmailResponse(
        @JsonProperty("id") String id
) {
}
