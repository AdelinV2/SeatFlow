package com.seatflow.notification.client.resend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResendEmailRequest(
        @JsonProperty("from") String from,
        @JsonProperty("to") List<String> to,
        @JsonProperty("subject") String subject,
        @JsonProperty("html") String html,
        @JsonProperty("attachments") List<ResendAttachment> attachments
) {
    public ResendEmailRequest {
        if (to == null) {
            to = List.of();
        }
        if (attachments == null) {
            attachments = List.of();
        }
    }
}
