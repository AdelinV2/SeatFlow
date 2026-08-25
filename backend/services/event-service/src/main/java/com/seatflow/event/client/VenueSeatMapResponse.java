package com.seatflow.event.client;

import java.util.List;
import java.util.UUID;

public record VenueSeatMapResponse(
        UUID venueId,
        String venueName,
        Integer capacity,
        List<VenueSectionResponse> sections
) {}
