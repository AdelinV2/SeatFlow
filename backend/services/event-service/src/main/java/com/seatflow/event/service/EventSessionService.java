package com.seatflow.event.service;

import com.seatflow.event.web.dto.request.CreateEventSessionRequest;
import com.seatflow.event.web.dto.request.UpdateEventSessionRequest;
import com.seatflow.event.web.dto.response.EventSessionResponse;
import com.seatflow.event.web.dto.response.SessionBookingContextResponse;

import java.util.List;
import java.util.UUID;

public interface EventSessionService {

    EventSessionResponse createSession(UUID eventId, CreateEventSessionRequest request);

    List<EventSessionResponse> listSessionsForAdmin(UUID eventId);

    List<EventSessionResponse> listSessionsForCustomer(UUID eventId);

    EventSessionResponse updateSession(UUID eventId, UUID sessionId, UpdateEventSessionRequest request);

    void deleteSession(UUID eventId, UUID sessionId);

    SessionBookingContextResponse getBookingContext(UUID sessionId);
}
