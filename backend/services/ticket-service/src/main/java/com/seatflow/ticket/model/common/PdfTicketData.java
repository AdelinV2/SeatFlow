package com.seatflow.ticket.model.common;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PdfTicketData(
    UUID ticketId,
    String ticketCode,
    String status,
    String eventTitle,
    String eventCategory,
    Instant eventDate,
    String venueName,
    String venueCity,
    String sectionName,
    String rowLabel,
    Integer seatNumber,
    String attendeeName,
    String customerEmail,
    BigDecimal price,       // Gross total paid
    BigDecimal taxAmount,   // Tax / VAT portion
    BigDecimal netAmount,   // Net base price
    String currency,
    String ticketType,      // Standard, VIP, Student
    byte[] qrCodeImagePng   // Embedded QR code image bytes
) {
    public PdfTicketData(
            UUID ticketId,
            String ticketCode,
            String status,
            String eventTitle,
            String eventCategory,
            Instant eventDate,
            String venueName,
            String venueCity,
            String sectionName,
            String rowLabel,
            Integer seatNumber,
            String attendeeName,
            String customerEmail,
            BigDecimal price,
            BigDecimal taxAmount,
            BigDecimal netAmount,
            String currency,
            byte[] qrCodeImagePng
    ) {
        this(ticketId, ticketCode, status, eventTitle, eventCategory, eventDate,
                venueName, venueCity, sectionName, rowLabel, seatNumber, attendeeName,
                customerEmail, price, taxAmount, netAmount, currency, "Standard", qrCodeImagePng);
    }
}
