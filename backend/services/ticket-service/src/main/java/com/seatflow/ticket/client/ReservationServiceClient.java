package com.seatflow.ticket.client;

import com.seatflow.ticket.client.dto.ReservationClientResponse;
import java.util.Optional;
import java.util.UUID;

public interface ReservationServiceClient {
    Optional<ReservationClientResponse> getReservationById(UUID reservationId);
}
