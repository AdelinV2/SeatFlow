package com.seatflow.event.mapper;

import com.seatflow.event.model.entity.Event;
import com.seatflow.event.model.entity.EventSession;
import com.seatflow.event.model.enums.EventCategory;
import com.seatflow.event.model.enums.EventSessionStatus;
import com.seatflow.event.model.enums.EventStatus;
import com.seatflow.event.web.dto.request.CreateEventSessionRequest;
import com.seatflow.event.web.dto.response.EventSessionResponse;
import com.seatflow.event.web.dto.response.SessionBookingContextResponse;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventSessionMapperTest {

    private final EventSessionMapper mapper = Mappers.getMapper(EventSessionMapper.class);

    private Event buildEvent() {
        return Event.builder()
                .id(UUID.randomUUID())
                .venueId(UUID.randomUUID())
                .title("Hamlet")
                .description("A play")
                .category(EventCategory.THEATRE)
                .status(EventStatus.PUBLISHED)
                .build();
    }

    private EventSession buildSession(Event event) {
        return EventSession.builder()
                .id(UUID.randomUUID())
                .event(event)
                .startsAt(Instant.parse("2027-05-01T19:30:00Z"))
                .endsAt(Instant.parse("2027-05-01T21:30:00Z"))
                .saleStartsAt(Instant.parse("2027-04-01T09:00:00Z"))
                .status(EventSessionStatus.SCHEDULED)
                .timezone("Europe/Berlin")
                .legacyBackfill(false)
                .build();
    }

    @Test
    void toResponse_mapsParentEventId() {
        Event event = buildEvent();
        EventSession session = buildSession(event);

        EventSessionResponse response = mapper.toResponse(session);

        assertThat(response.id()).isEqualTo(session.getId());
        assertThat(response.eventId()).isEqualTo(event.getId());
        assertThat(response.startsAt()).isEqualTo(session.getStartsAt());
        assertThat(response.endsAt()).isEqualTo(session.getEndsAt());
        assertThat(response.status()).isEqualTo(EventSessionStatus.SCHEDULED);
        assertThat(response.timezone()).isEqualTo("Europe/Berlin");
    }

    @Test
    void toBookingContext_derivesParentEventServerSide() {
        Event event = buildEvent();
        EventSession session = buildSession(event);

        SessionBookingContextResponse context = mapper.toBookingContext(session);

        assertThat(context.eventSessionId()).isEqualTo(session.getId());
        assertThat(context.eventId()).isEqualTo(event.getId());
        assertThat(context.eventStatus()).isEqualTo(EventStatus.PUBLISHED);
        assertThat(context.sessionStatus()).isEqualTo(EventSessionStatus.SCHEDULED);
        assertThat(context.startsAt()).isEqualTo(session.getStartsAt());
        assertThat(context.endsAt()).isEqualTo(session.getEndsAt());
        assertThat(context.venueId()).isEqualTo(event.getVenueId());
    }

    @Test
    void toEntity_leavesServerManagedFieldsUnset() {
        CreateEventSessionRequest request = new CreateEventSessionRequest(
                Instant.parse("2027-05-01T19:30:00Z"), Instant.parse("2027-05-01T21:30:00Z"),
                null, null, null);

        EventSession session = mapper.toEntity(request);

        assertThat(session.getId()).isNull();
        assertThat(session.getEvent()).isNull();
        assertThat(session.getStatus()).isNull();
        assertThat(session.getLegacyBackfill()).isFalse();
        assertThat(session.getStartsAt()).isEqualTo(request.startsAt());
        assertThat(session.getEndsAt()).isEqualTo(request.endsAt());
    }
}
