package com.seatflow.event.client;

import java.util.UUID;

public interface SeatMapClient extends VenueValidationPort {

    VenueSeatMapResponse getVenueSeatMap(UUID venueId);
}
