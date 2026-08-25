package com.seatflow.event.client;

import java.util.UUID;

public interface VenueValidationPort {

    boolean venueExists(UUID venueId);

    boolean sectionBelongsToVenue(UUID venueId, UUID sectionId);
}
