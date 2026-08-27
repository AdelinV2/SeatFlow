package com.seatflow.realtime.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Real-time state of a physical venue seat")
public enum SeatStatus {
    AVAILABLE,
    HELD,
    SOLD
}
