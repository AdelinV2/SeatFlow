package com.seatflow.notification.client.resend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ResendAttachment(
        @JsonProperty("filename") String filename,
        @JsonProperty("content") String content // Base64 encoded binary
) {
}
