package com.seatflow.ticket.web.dto.response;

import com.seatflow.ticket.model.enums.ValidationResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Gate scanner verification outcome")
public record ValidationResultResponse(
    @Schema(description = "Whether the ticket is valid for entry") boolean valid,
    @Schema(description = "Ticket ID") UUID ticketId,
    @Schema(description = "Ticket verification code") String ticketCode,
    @Schema(description = "Validation result status") ValidationResult result,
    @Schema(description = "Event title") String eventTitle,
    @Schema(description = "Event date") Instant eventDate,
    @Schema(description = "Attendee name") String attendeeName,
    @Schema(description = "Venue section name") String section,
    @Schema(description = "Seat row label") String rowNumber,
    @Schema(description = "Seat number") Integer seatNumber,
    @Schema(description = "Scan timestamp") Instant scannedAt,
    @Schema(description = "Descriptive outcome message") String message
) {}
