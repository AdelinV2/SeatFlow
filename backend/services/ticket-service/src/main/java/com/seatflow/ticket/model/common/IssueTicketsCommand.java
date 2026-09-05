package com.seatflow.ticket.model.common;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record IssueTicketsCommand(
    UUID paymentId,
    UUID reservationId,
    UUID userId,              // Null for guest purchasers (ADR-001)
    String customerEmail,
    String attendeeName,
    UUID eventSessionId,      // Immutable showing identity (P12-004)
    UUID eventId,
    Instant sessionStartsAt,  // Immutable showing snapshot (P12-004)
    Instant sessionEndsAt,    // Immutable showing snapshot (P12-004)
    String sessionTimezone,   // Nullable IANA ZoneId metadata (P12-004)
    List<SeatTicketItem> seats,
    String currency
) {
    public record SeatTicketItem(
        UUID seatId,
        BigDecimal price,     // Gross ticket price
        BigDecimal taxAmount, // Tax / VAT portion (ADR-004)
        BigDecimal netAmount, // Net base price (ADR-004)
        String ticketType
    ) {
        public SeatTicketItem(UUID seatId, BigDecimal price, BigDecimal taxAmount, BigDecimal netAmount) {
            this(seatId, price, taxAmount, netAmount, null);
        }
    }
}
