package com.seatflow.reservation.repository.projection;

import com.seatflow.reservation.model.enums.SeatHoldStatus;

import java.util.UUID;

public interface ActiveSeatHoldProjection {

    UUID getSeatId();

    SeatHoldStatus getStatus();
}
