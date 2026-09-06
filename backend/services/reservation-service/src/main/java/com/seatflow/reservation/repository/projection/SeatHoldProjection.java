package com.seatflow.reservation.repository.projection;

import com.seatflow.reservation.model.enums.SeatHoldStatus;

import java.math.BigDecimal;
import java.util.UUID;

public interface SeatHoldProjection {

    UUID getId();

    UUID getEventSessionId();

    UUID getSeatId();

    SeatHoldStatus getStatus();

    BigDecimal getPrice();
}
