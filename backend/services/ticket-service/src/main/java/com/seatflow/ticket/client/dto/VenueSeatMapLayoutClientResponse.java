package com.seatflow.ticket.client.dto;

import java.util.List;
import java.util.UUID;

public record VenueSeatMapLayoutClientResponse(
    UUID venueId,
    String venueName,
    List<SectionLayoutDto> sections
) {
    public record SectionLayoutDto(
        UUID sectionId,
        String sectionName,
        List<SeatLayoutDto> seats
    ) {}

    public record SeatLayoutDto(
        UUID seatId,
        String rowLabel,
        Integer seatNumber,
        Integer gridX,
        Integer gridY,
        Boolean isActive
    ) {}
}
