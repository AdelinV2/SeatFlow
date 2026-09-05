package com.seatflow.event.repository;

import com.seatflow.event.model.entity.Event;
import com.seatflow.event.model.entity.EventSession;
import com.seatflow.event.model.enums.EventCategory;
import com.seatflow.event.model.enums.EventSessionStatus;
import com.seatflow.event.model.enums.EventStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
class EventRepositoryCompletionTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_event_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EventSessionRepository eventSessionRepository;

    private Event event(EventStatus status, Instant eventDate) {
        return Event.builder()
                .venueId(UUID.randomUUID())
                .title("Show " + UUID.randomUUID())
                .description("desc")
                .category(EventCategory.CONCERT)
                .eventDate(eventDate)
                .status(status)
                .build();
    }

    private void session(Event event, EventSessionStatus status, Instant startsAt, Instant endsAt) {
        eventSessionRepository.saveAndFlush(EventSession.builder()
                .event(event)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .status(status)
                .legacyBackfill(false)
                .build());
    }

    @Test
    void findPublishedCompletableReturnsEventWhenAllSessionsEnded() {
        Instant now = Instant.now();
        Event past = eventRepository.saveAndFlush(event(EventStatus.PUBLISHED, now.plusSeconds(86400)));
        session(past, EventSessionStatus.SCHEDULED, now.minusSeconds(7200), now.minusSeconds(3600));

        List<Event> result = eventRepository.findPublishedCompletableForUpdate(now, PageRequest.of(0, 50));

        assertThat(result).extracting(Event::getId).containsExactly(past.getId());
    }

    @Test
    void findPublishedCompletableExcludesEventWhileLaterSessionRemains() {
        Instant now = Instant.now();
        Event multi = eventRepository.saveAndFlush(event(EventStatus.PUBLISHED, now.minusSeconds(3600)));
        session(multi, EventSessionStatus.SCHEDULED, now.minusSeconds(7200), now.minusSeconds(3600));
        session(multi, EventSessionStatus.SCHEDULED, now.plusSeconds(86400), now.plusSeconds(86400 + 7200));

        List<Event> result = eventRepository.findPublishedCompletableForUpdate(now, PageRequest.of(0, 50));

        assertThat(result).isEmpty();
    }

    @Test
    void findPublishedCompletableIgnoresCancelledSessions() {
        Instant now = Instant.now();
        Event cancelledOnly = eventRepository.saveAndFlush(event(EventStatus.PUBLISHED, now.minusSeconds(3600)));
        session(cancelledOnly, EventSessionStatus.CANCELLED, now.plusSeconds(86400), now.plusSeconds(86400 + 7200));

        List<Event> result = eventRepository.findPublishedCompletableForUpdate(now, PageRequest.of(0, 50));

        assertThat(result).extracting(Event::getId).containsExactly(cancelledOnly.getId());
    }

    @Test
    void findPublishedCompletableFallsBackToLegacyDateForSessionlessEvents() {
        Instant now = Instant.now();
        Event legacyPast = eventRepository.saveAndFlush(event(EventStatus.PUBLISHED, now.minusSeconds(3600)));
        eventRepository.saveAndFlush(event(EventStatus.PUBLISHED, now.plusSeconds(3600)));
        eventRepository.saveAndFlush(event(EventStatus.COMPLETED, now.minusSeconds(3600)));
        eventRepository.saveAndFlush(event(EventStatus.DRAFT, now.minusSeconds(3600)));

        List<Event> result = eventRepository.findPublishedCompletableForUpdate(now, PageRequest.of(0, 50));

        assertThat(result).extracting(Event::getId).containsExactly(legacyPast.getId());
    }

    @Test
    void findPublishedCompletableRespectsBatchSize() {
        Instant now = Instant.now();
        for (int i = 0; i < 3; i++) {
            Event past = eventRepository.saveAndFlush(event(EventStatus.PUBLISHED, now.minusSeconds(3600 + i)));
            session(past, EventSessionStatus.SCHEDULED, now.minusSeconds(7200 + i), now.minusSeconds(3600 + i));
        }

        List<Event> result = eventRepository.findPublishedCompletableForUpdate(now, PageRequest.of(0, 2));

        assertThat(result).hasSize(2);
    }
}
