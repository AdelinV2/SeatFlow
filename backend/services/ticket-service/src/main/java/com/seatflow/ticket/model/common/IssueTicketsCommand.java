package com.seatflow.ticket.model.common;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record IssueTicketsCommand(
    UUID paymentId,
    UUID reservationId,
    UUID userId,              // Null for guest purchasers (ADR-001)
    String customerEmail,
    String attendeeName,
    UUID eventId,
    List<SeatTicketItem> seats,
    String currency
) {
    public record SeatTicketItem(
        UUID seatId,
        BigDecimal price,     // Gross ticket price
        BigDecimal taxAmount, // Tax / VAT portion (ADR-004)
        BigDecimal netAmount  // Net base price (ADR-004)
    ) {}
}
