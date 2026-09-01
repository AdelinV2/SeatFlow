package com.seatflow.realtime.service;

import com.seatflow.realtime.dto.SeatStatusUpdateMessage;

public interface RealtimeFanOutPublisher {

    void publish(String sourceEventId, SeatStatusUpdateMessage payload);
}
