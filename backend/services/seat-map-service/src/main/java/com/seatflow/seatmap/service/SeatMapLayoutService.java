package com.seatflow.seatmap.service;

import com.seatflow.seatmap.web.dto.response.VenueSeatMapLayoutResponse;

import java.util.UUID;

public interface SeatMapLayoutService {

    /**
     * Retrieve the complete venue seat map layout with all sections and their active seats.
     * Used by the interactive seat map UI and downstream event-service.
     * Throws ResourceNotFoundException if venue does not exist.
     */
    VenueSeatMapLayoutResponse getVenueLayout(UUID venueId);
}
