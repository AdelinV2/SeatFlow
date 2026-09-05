package com.seatflow.event.integration;

import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.event.client.SeatMapClient;
import com.seatflow.event.client.SeatMapVenueLayout;
import com.seatflow.event.client.SeatMapVenueSection;
import com.seatflow.event.client.SeatMapVenueSeat;
import com.seatflow.event.model.entity.Event;
import com.seatflow.event.model.entity.EventSession;
import com.seatflow.event.model.enums.EventCategory;
import com.seatflow.event.model.enums.EventSessionStatus;
import com.seatflow.event.model.enums.EventStatus;
import com.seatflow.event.repository.EventRepository;
import com.seatflow.event.repository.EventSessionRepository;
import com.seatflow.event.repository.OutboxEventRepository;
import com.seatflow.event.service.EventService;
import com.seatflow.event.service.EventSessionService;
import com.seatflow.event.web.dto.request.ConfigurePricingRequest;
import com.seatflow.event.web.dto.request.CreateEventRequest;
import com.seatflow.event.web.dto.request.CreateEventSessionRequest;
import com.seatflow.event.web.dto.request.PricingTierItemRequest;
import com.seatflow.event.web.dto.request.UpdateEventRequest;
import com.seatflow.event.web.dto.response.EventDetailResponse;
import com.seatflow.event.web.dto.response.EventSessionResponse;
import com.seatflow.event.web.dto.response.SessionBookingContextResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class EventSessionIntegrationTest {

    private static final UUID SECTION_ID = UUID.randomUUID();
    private static final UUID VENUE_ID = UUID.randomUUID();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_event_session_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("spring.task.scheduling.enabled", () -> "false");
        registry.add("outbox.publisher.fixed-delay-ms", () -> "60000");
    }

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;
    @MockitoBean
    private SeatMapClient seatMapClient;

    @Autowired
    private EventService eventService;
    @Autowired
    private EventSessionService eventSessionService;
    @Autowired
    private com.seatflow.event.service.EventPricingService eventPricingService;
    @Autowired
    private OutboxEventRepository outboxRepository;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private EventSessionRepository eventSessionRepository;

    @BeforeEach
    void setUpMocks() {
        when(seatMapClient.venueExists(any(UUID.class))).thenReturn(true);
        when(seatMapClient.getVenueLayout(any(UUID.class))).thenReturn(new SeatMapVenueLayout(
                VENUE_ID, "Grand Hall", 500, 10L,
                List.of(new SeatMapVenueSection(SECTION_ID, "A", 5, 10,
                        List.of(new SeatMapVenueSeat(UUID.randomUUID(), "R1", 1, 1, 1, true))))));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    @AfterEach
    void cleanUp() {
        eventSessionRepository.deleteAll();
        outboxRepository.deleteAll();
        eventRepository.deleteAll();
    }

    private UUID createDraftEvent() {
        return eventService.createEvent(new CreateEventRequest(
                VENUE_ID, "Session Show", "desc", EventCategory.CONCERT,
                null, Instant.now().plusSeconds(30 * 86400))).id();
    }

    private void configurePricing(UUID eventId) {
        eventPricingService.configurePricing(eventId, new ConfigurePricingRequest(List.of(
                new PricingTierItemRequest(SECTION_ID, "Standard", new BigDecimal("20.00"), "USD"))));
    }

    private CreateEventSessionRequest sessionRequest(Instant startsAt) {
        Instant truncated = startsAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        return new CreateEventSessionRequest(truncated, truncated.plusSeconds(7200), null, null, null);
    }

    @Test
    void organizerCreatesTwoSessionsWithDistinctIds() {
        UUID eventId = createDraftEvent();
        Instant base = Instant.now().plusSeconds(10 * 86400);

        EventSessionResponse first = eventSessionService.createSession(eventId, sessionRequest(base));
        EventSessionResponse second = eventSessionService.createSession(eventId, sessionRequest(base.plusSeconds(86400)));

        assertThat(first.id()).isNotNull();
        assertThat(second.id()).isNotNull();
        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(eventSessionService.listSessionsForAdmin(eventId)).hasSize(2);
    }

    @Test
    void publishFailsWithoutFutureSessionAndSucceedsWithOne() {
        UUID eventId = createDraftEvent();
        configurePricing(eventId);

        assertThatThrownBy(() -> eventService.updateEvent(eventId,
                new UpdateEventRequest(null, null, null, null, null, EventStatus.PUBLISHED)))
                .isInstanceOf(ValidationException.class);

        eventSessionService.createSession(eventId, sessionRequest(Instant.now().plusSeconds(10 * 86400)));

        EventDetailResponse published = eventService.updateEvent(eventId,
                new UpdateEventRequest(null, null, null, null, null, EventStatus.PUBLISHED));

        assertThat(published.status()).isEqualTo(EventStatus.PUBLISHED);
        assertThat(published.sessions()).hasSize(1);
    }

    @Test
    void completionDoesNotCompleteEventWhileLaterSessionRemains() {
        UUID eventId = createDraftEvent();
        configurePricing(eventId);
        Instant now = Instant.now();
        Event event = eventRepository.findById(eventId).orElseThrow();
        eventSessionRepository.saveAndFlush(EventSession.builder()
                .event(event)
                .startsAt(now.minusSeconds(7200))
                .endsAt(now.minusSeconds(3600))
                .status(EventSessionStatus.SCHEDULED)
                .legacyBackfill(false)
                .build());
        eventSessionRepository.saveAndFlush(EventSession.builder()
                .event(event)
                .startsAt(now.plusSeconds(86400))
                .endsAt(now.plusSeconds(86400 + 7200))
                .status(EventSessionStatus.SCHEDULED)
                .legacyBackfill(false)
                .build());
        eventService.updateEvent(eventId,
                new UpdateEventRequest(null, null, null, null, null, EventStatus.PUBLISHED));

        int completed = eventService.completeExpiredEvents(Instant.now(), 50);

        assertThat(completed).isZero();
        assertThat(eventRepository.findById(eventId).orElseThrow().getStatus())
                .isEqualTo(EventStatus.PUBLISHED);
    }

    @Test
    void completionCompletesEventOnceAllSessionsEnded() {
        UUID eventId = createDraftEvent();
        configurePricing(eventId);
        Event event = eventRepository.findById(eventId).orElseThrow();
        Instant now = Instant.now();
        EventSession session = eventSessionRepository.saveAndFlush(EventSession.builder()
                .event(event)
                .startsAt(now.plusSeconds(86400))
                .endsAt(now.plusSeconds(86400 + 7200))
                .status(EventSessionStatus.SCHEDULED)
                .legacyBackfill(false)
                .build());
        eventService.updateEvent(eventId,
                new UpdateEventRequest(null, null, null, null, null, EventStatus.PUBLISHED));

        session.setStartsAt(now.minusSeconds(7200));
        session.setEndsAt(now.minusSeconds(3600));
        eventSessionRepository.saveAndFlush(session);

        int completed = eventService.completeExpiredEvents(Instant.now(), 50);

        assertThat(completed).isEqualTo(1);
        assertThat(eventRepository.findById(eventId).orElseThrow().getStatus())
                .isEqualTo(EventStatus.COMPLETED);
        assertThat(eventSessionRepository.findById(session.getId()).orElseThrow().getStatus())
                .isEqualTo(EventSessionStatus.COMPLETED);
    }

    @Test
    void legacyBackfilledEventRemainsVisibleWithItsOneSession() {
        Instant future = Instant.now().plusSeconds(10 * 86400).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Event legacy = eventRepository.saveAndFlush(Event.builder()
                .venueId(VENUE_ID)
                .title("Legacy Gig")
                .description("desc")
                .category(EventCategory.CONCERT)
                .eventDate(future)
                .status(EventStatus.PUBLISHED)
                .build());
        eventSessionRepository.saveAndFlush(EventSession.builder()
                .event(legacy)
                .startsAt(future)
                .endsAt(future.plusSeconds(7200))
                .status(EventSessionStatus.SCHEDULED)
                .legacyBackfill(true)
                .build());

        EventDetailResponse adminView = eventService.getEventForAdministration(legacy.getId());
        EventDetailResponse customerView = eventService.getPublishedEvent(legacy.getId());

        assertThat(adminView.sessions()).hasSize(1);
        assertThat(adminView.sessions().getFirst().startsAt()).isEqualTo(future);
        assertThat(customerView.sessions()).hasSize(1);
        assertThat(eventSessionService.listSessionsForCustomer(legacy.getId())).hasSize(1);
    }

    @Test
    void bookingContextDerivesParentEventServerSide() {
        UUID eventId = createDraftEvent();
        Instant startsAt = Instant.now().plusSeconds(10 * 86400).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        EventSessionResponse created = eventSessionService.createSession(eventId, sessionRequest(startsAt));

        SessionBookingContextResponse context = eventSessionService.getBookingContext(created.id());

        assertThat(context.eventSessionId()).isEqualTo(created.id());
        assertThat(context.eventId()).isEqualTo(eventId);
        assertThat(context.eventStatus()).isEqualTo(EventStatus.DRAFT);
        assertThat(context.sessionStatus()).isEqualTo(EventSessionStatus.SCHEDULED);
        assertThat(context.startsAt()).isEqualTo(startsAt);
        assertThat(context.endsAt()).isEqualTo(startsAt.plusSeconds(7200));
        assertThat(context.venueId()).isEqualTo(VENUE_ID);
    }
}
