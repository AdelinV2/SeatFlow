package com.seatflow.ticket.client;

import com.seatflow.ticket.client.dto.VenueClientResponse;
import com.seatflow.ticket.client.dto.VenueSeatMapLayoutClientResponse;
import java.util.Optional;
import java.util.UUID;

public interface SeatMapServiceClient {
    Optional<VenueClientResponse> getVenueById(UUID venueId);
    Optional<VenueSeatMapLayoutClientResponse> getVenueLayout(UUID venueId);
}
