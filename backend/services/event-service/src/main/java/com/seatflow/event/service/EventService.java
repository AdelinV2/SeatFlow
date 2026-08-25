package com.seatflow.event.service;

import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.event.model.enums.EventCategory;
import com.seatflow.event.web.dto.request.CreateEventRequest;
import com.seatflow.event.web.dto.request.UpdateEventRequest;
import com.seatflow.event.web.dto.response.EventDetailResponse;
import com.seatflow.event.web.dto.response.EventSeatMapResponse;
import com.seatflow.event.web.dto.response.EventSummaryResponse;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface EventService {

    EventDetailResponse createEvent(CreateEventRequest request);

    EventDetailResponse updateEvent(UUID eventId, UpdateEventRequest request);

    PagedResult<EventSummaryResponse> findPublishedEvents(EventCategory category, String search, Pageable pageable);

    EventDetailResponse getPublishedEvent(UUID eventId);

    EventDetailResponse getEventForAdministration(UUID eventId);

    EventSeatMapResponse getEventSeatMap(UUID eventId);
}
