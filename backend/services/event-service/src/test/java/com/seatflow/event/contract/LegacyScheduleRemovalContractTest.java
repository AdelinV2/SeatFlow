package com.seatflow.event.contract;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.seatflow.event.messaging.event.EventCancelledEvent;
import com.seatflow.event.messaging.event.EventCompletedEvent;
import com.seatflow.event.messaging.event.EventCreatedEvent;
import com.seatflow.event.messaging.event.EventPublishedEvent;
import com.seatflow.event.model.entity.Event;
import com.seatflow.event.web.dto.request.CreateEventRequest;
import com.seatflow.event.web.dto.request.UpdateEventRequest;
import com.seatflow.event.web.dto.response.EventDetailResponse;
import com.seatflow.event.web.dto.response.EventSeatMapResponse;
import com.seatflow.event.web.dto.response.EventSummaryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P12-007 regression gate: legacy event-level schedule ownership must stay removed.
 *
 * <p>Event is catalog identity only; EventSession owns startsAt/endsAt. No booking
 * path may read/write an event-level instant, accept legacy schedule fields, or
 * infer a session from an event.
 */
class LegacyScheduleRemovalContractTest {

    private static boolean hasRecordComponent(Class<?> recordClass, String name) {
        return Arrays.stream(recordClass.getRecordComponents()).anyMatch(c -> c.getName().equals(name));
    }

    @Test
    @DisplayName("Event entity exposes no event-level schedule instant")
    void eventEntityHasNoScheduleField() {
        assertThat(Arrays.stream(Event.class.getDeclaredFields())
                .anyMatch(f -> f.getName().equals("eventDate"))).isFalse();
        assertThatThrownBy(() -> Event.class.getDeclaredMethod("getEventDate"))
                .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    @DisplayName("Create/update requests accept no legacy schedule fields")
    void requestDtosHaveNoScheduleFields() {
        assertThat(hasRecordComponent(CreateEventRequest.class, "eventDate")).isFalse();
        assertThat(hasRecordComponent(CreateEventRequest.class, "startsAt")).isFalse();
        assertThat(hasRecordComponent(UpdateEventRequest.class, "eventDate")).isFalse();
        assertThat(hasRecordComponent(UpdateEventRequest.class, "startsAt")).isFalse();
    }

    @Test
    @DisplayName("Response DTOs carry no event-level instant; summary derives next session")
    void responseDtosHaveNoEventLevelInstant() {
        assertThat(hasRecordComponent(EventDetailResponse.class, "eventDate")).isFalse();
        assertThat(hasRecordComponent(EventSeatMapResponse.class, "eventDate")).isFalse();
        assertThat(hasRecordComponent(EventSummaryResponse.class, "eventDate")).isFalse();
        assertThat(hasRecordComponent(EventSummaryResponse.class, "nextSessionStartsAt")).isTrue();
    }

    @Test
    @DisplayName("Domain events carry no legacy eventDate schedule field")
    void domainEventsHaveNoScheduleField() {
        assertThat(hasRecordComponent(EventCreatedEvent.class, "eventDate")).isFalse();
        assertThat(hasRecordComponent(EventPublishedEvent.class, "eventDate")).isFalse();
        assertThat(hasRecordComponent(EventCancelledEvent.class, "eventDate")).isFalse();
        assertThat(hasRecordComponent(EventCompletedEvent.class, "eventDate")).isFalse();
    }

    @Test
    @DisplayName("Legacy schedule JSON is rejected, never silently ignored")
    void legacyScheduleJsonIsRejected() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        String legacyCreate = """
                {"venueId":"123e4567-e89b-12d3-a456-426614174000","title":"Hamlet",
                 "description":"A play","category":"OTHER","eventDate":"2027-05-01T19:30:00Z"}""";
        assertThatThrownBy(() -> mapper.readValue(legacyCreate, CreateEventRequest.class))
                .hasMessageContaining("eventDate");
        String legacyUpdate = "{\"eventDate\":\"2027-06-01T19:30:00Z\"}";
        assertThatThrownBy(() -> mapper.readValue(legacyUpdate, UpdateEventRequest.class))
                .hasMessageContaining("eventDate");
    }

    @Test
    @DisplayName("V4 migration drops event_date behind the session-parity gate")
    void v4MigrationDropsLegacyColumnBehindGate() throws Exception {
        Path migration = Path.of("src/main/resources/db/migration/V4__remove_legacy_event_schedule.sql");
        assertThat(Files.exists(migration)).isTrue();
        String sql = Files.readString(migration);
        assertThat(sql).contains("DROP COLUMN IF EXISTS event_date");
        assertThat(sql).contains("idx_events_status_date");
        assertThat(sql).contains("idx_events_category_date");
        assertThat(sql).contains("refusing to drop event_date");
        assertThat(sql).contains("idx_event_sessions_status_start");
    }
}
