package com.seatflow.notification.web.dto.response;

import com.seatflow.notification.model.enums.NotificationStatus;
import com.seatflow.notification.model.enums.NotificationTemplateType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Notification dispatch record audit details")
public record NotificationLogResponse(
        @Schema(description = "Notification record UUID", example = "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")
        UUID id,

        @Schema(description = "Recipient email address", example = "customer@example.com")
        String recipientEmail,

        @Schema(description = "Template type used for the notification", example = "TICKET_ISSUED")
        NotificationTemplateType templateType,

        @Schema(description = "Subject line of the email", example = "Your SeatFlow Ticket Confirmation")
        String subject,

        @Schema(description = "Unique idempotency key preventing duplicate dispatch", example = "ticket-issued-123e4567-e89b-12d3-a456-426614174000")
        String idempotencyKey,

        @Schema(description = "Rendered HTML email body")
        String renderedContent,

        @Schema(description = "Current lifecycle status", example = "SENT")
        NotificationStatus status,

        @Schema(description = "Detailed error message if delivery failed", example = "Connection timed out")
        String errorMessage,

        @Schema(description = "Timestamp when the notification was successfully sent")
        Instant sentAt,

        @Schema(description = "Number of retry attempts executed", example = "0")
        Integer retryCount,

        @Schema(description = "Timestamp when the record was initially created")
        Instant createdAt,

        @Schema(description = "Timestamp when the record was last updated")
        Instant updatedAt
) {
}
