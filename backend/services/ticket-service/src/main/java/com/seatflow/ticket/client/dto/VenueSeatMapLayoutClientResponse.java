package com.seatflow.ticket.client.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.List;
import java.util.UUID;

public record VenueSeatMapLayoutClientResponse(
    UUID venueId,
    @JsonAlias({"name", "venueName"})
    String venueName,
    List<SectionLayoutDto> sections
) {
    public record SectionLayoutDto(
        UUID sectionId,
        @JsonAlias({"name", "sectionName"})
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
