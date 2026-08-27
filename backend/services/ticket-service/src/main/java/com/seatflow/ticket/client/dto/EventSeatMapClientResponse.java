package com.seatflow.ticket.client.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EventSeatMapClientResponse(
    UUID eventId,
    UUID venueId,
    String eventTitle,
    Instant eventDate,
    String venueName,
    Integer venueCapacity,
    Long totalConfiguredSeats,
    List<SeatMapSectionClientDto> sections
) {
    public record SeatMapSectionClientDto(
        UUID sectionId,
        String name,
        Integer rowCount,
        Integer colCount,
        List<SeatMapSeatClientDto> seats
    ) {}

    public record SeatMapSeatClientDto(
        UUID seatId,
        String rowLabel,
        Integer seatNumber,
        Integer gridX,
        Integer gridY,
        @JsonAlias({"status", "isActive"})
        String status,
        @JsonAlias({"isActive", "status"})
        Boolean isActive
    ) {}
}
