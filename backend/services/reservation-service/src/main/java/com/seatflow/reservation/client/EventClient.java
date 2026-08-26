package com.seatflow.reservation.client;

import com.seatflow.reservation.client.dto.EventPricingDetails;

import java.util.Set;
import java.util.UUID;

public interface EventClient {

    EventPricingDetails getEventSeatPricing(UUID eventId, Set<UUID> requestedSeatIds);
}
