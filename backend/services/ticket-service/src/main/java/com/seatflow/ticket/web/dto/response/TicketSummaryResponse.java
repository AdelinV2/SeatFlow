package com.seatflow.ticket.web.dto.response;

import com.seatflow.ticket.model.enums.TicketStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Compact ticket summary response")
public record TicketSummaryResponse(
    @Schema(description = "Unique ticket ID") UUID id,
    @Schema(description = "Ticket code") String ticketCode,
    @Schema(description = "Event ID") UUID eventId,
    @Schema(description = "Seat ID") UUID seatId,
    @Schema(description = "Ticket status") TicketStatus status,
    @Schema(description = "Ticket gross price") BigDecimal price,
    @Schema(description = "Creation timestamp") Instant createdAt
) {}
