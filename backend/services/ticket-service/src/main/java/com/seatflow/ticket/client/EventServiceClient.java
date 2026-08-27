package com.seatflow.ticket.client;

import com.seatflow.ticket.client.dto.EventClientResponse;
import com.seatflow.ticket.client.dto.EventSeatMapClientResponse;
import java.util.Optional;
import java.util.UUID;

public interface EventServiceClient {
    Optional<EventClientResponse> getEventById(UUID eventId);
    Optional<EventSeatMapClientResponse> getEventSeatMap(UUID eventId);
}
