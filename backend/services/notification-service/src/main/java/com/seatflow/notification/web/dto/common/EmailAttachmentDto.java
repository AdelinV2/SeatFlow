package com.seatflow.notification.web.dto.common;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "File attachment payload for email transmission")
public record EmailAttachmentDto(
        @Schema(description = "Filename including extension", example = "ticket-SF-TKT-1234.pdf")
        String filename,

        @Schema(description = "MIME content type", example = "application/pdf")
        String contentType,

        @Schema(description = "Raw binary content")
        byte[] content
) {
}
