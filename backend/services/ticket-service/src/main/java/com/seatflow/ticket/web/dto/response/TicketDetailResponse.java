package com.seatflow.ticket.web.dto.response;

import com.seatflow.ticket.model.enums.TicketStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Detailed ticket response including QR code payload")
public record TicketDetailResponse(
    @Schema(description = "Unique ticket ID") UUID id,
    @Schema(description = "Reservation ID") UUID reservationId,
    @Schema(description = "Payment ID") UUID paymentId,
    @Schema(description = "User ID (null for guests)") UUID userId,
    @Schema(description = "Customer email") String customerEmail,
    @Schema(description = "Attendee name") String attendeeName,
    @Schema(description = "Event ID") UUID eventId,
    @Schema(description = "Seat ID") UUID seatId,
    @Schema(description = "Total gross ticket price") BigDecimal price,
    @Schema(description = "Tax / VAT portion included") BigDecimal taxAmount,
    @Schema(description = "Net ticket base price") BigDecimal netAmount,
    @Schema(description = "Ticket tier / seat type") String ticketType,
    @Schema(description = "Ticket code") String ticketCode,
    @Schema(description = "QR code payload data") String qrCodeData,
    @Schema(description = "Ticket status") TicketStatus status,
    @Schema(description = "Creation timestamp") Instant createdAt,
    @Schema(description = "Update timestamp") Instant updatedAt
) {}
