package com.seatflow.event.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Aggregated seat map for an event, combining venue layout with event pricing")
public record EventSeatMapResponse(

    @Schema(description = "Event UUID") UUID eventId,
    @Schema(description = "Owning venue UUID") UUID venueId,
    @Schema(description = "Event title") String eventTitle,
    @Schema(description = "Event status") String status,
    @Schema(description = "UTC start time of the event") Instant eventDate,
    @Schema(description = "Venue name") String venueName,
    @Schema(description = "Total venue capacity") Integer venueCapacity,
    @Schema(description = "Total configured seats across all sections") Long totalConfiguredSeats,
    @Schema(description = "Sections with seat grids and pricing") List<SeatMapSectionResponse> sections

) {
    public EventSeatMapResponse(UUID eventId, UUID venueId, String eventTitle, Instant eventDate,
                                String venueName, Integer venueCapacity, Long totalConfiguredSeats,
                                List<SeatMapSectionResponse> sections) {
        this(eventId, venueId, eventTitle, "PUBLISHED", eventDate, venueName, venueCapacity, totalConfiguredSeats, sections);
    }
}
