package com.seatflow.reservation.service;

import com.seatflow.reservation.web.dto.request.CreateReservationRequest;
import com.seatflow.reservation.web.dto.response.ReservationResponse;
import com.seatflow.reservation.web.dto.response.SeatAvailabilityResponse;

import java.util.UUID;

public interface ReservationService {

    ReservationResponse createReservation(CreateReservationRequest request, UUID authenticatedUserId);

    ReservationResponse getReservationById(UUID reservationId, UUID authenticatedUserId);

    SeatAvailabilityResponse getSeatAvailability(UUID eventId);

    void cancelReservation(UUID reservationId, UUID authenticatedUserId);
}
