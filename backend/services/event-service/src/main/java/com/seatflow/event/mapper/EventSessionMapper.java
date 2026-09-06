package com.seatflow.event.mapper;

import com.seatflow.event.model.entity.EventSession;
import com.seatflow.event.web.dto.request.CreateEventSessionRequest;
import com.seatflow.event.web.dto.response.EventSessionResponse;
import com.seatflow.event.web.dto.response.SessionBookingContextResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface EventSessionMapper {

    @Mapping(target = "eventId", source = "event.id")
    EventSessionResponse toResponse(EventSession session);

    @Mapping(target = "eventSessionId", source = "id")
    @Mapping(target = "eventId", source = "event.id")
    @Mapping(target = "eventStatus", source = "event.status")
    @Mapping(target = "sessionStatus", source = "status")
    @Mapping(target = "venueId", source = "event.venueId")
    SessionBookingContextResponse toBookingContext(EventSession session);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "event", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "legacyBackfill", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    EventSession toEntity(CreateEventSessionRequest request);
}
