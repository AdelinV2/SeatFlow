package com.seatflow.event.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
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
    @Schema(description = "Sections with seat grids and pricing") List<SeatMapSectionResponse> sections,
    @Schema(description = "Current venue layout version") Long layoutVersion,
    @Schema(description = "Venue-level non-bookable visual layout elements") List<LayoutElement> layoutElements

) {
    public EventSeatMapResponse(UUID eventId, UUID venueId, String eventTitle, Instant eventDate,
                                String venueName, Integer venueCapacity, Long totalConfiguredSeats,
                                List<SeatMapSectionResponse> sections) {
        this(eventId, venueId, eventTitle, "PUBLISHED", eventDate, venueName, venueCapacity, totalConfiguredSeats,
                sections, 0L, List.of());
    }

    @Schema(description = "Venue-level non-bookable visual layout element")
    public record LayoutElement(
        @Schema(description = "Element UUID") UUID elementId,
        @Schema(description = "Element type") String type,
        @Schema(description = "Display label") String label,
        @Schema(description = "Typed element geometry") Geometry geometry,
        @Schema(description = "Element z-index") Integer zIndex
    ) {
    }

    @Schema(description = "Typed rectangular geometry for layout elements")
    public record Geometry(
        @Schema(description = "Geometry X on venue canvas") BigDecimal x,
        @Schema(description = "Geometry Y on venue canvas") BigDecimal y,
        @Schema(description = "Geometry width") BigDecimal width,
        @Schema(description = "Geometry height") BigDecimal height,
        @Schema(description = "Geometry rotation in degrees") BigDecimal rotationDeg
    ) {
    }
}
