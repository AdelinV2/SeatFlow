package com.seatflow.payment.client;

import com.seatflow.payment.client.dto.ReservationClientResponse;

import java.util.UUID;

public interface ReservationServiceClient {

    ReservationClientResponse getReservation(UUID reservationId);
}
