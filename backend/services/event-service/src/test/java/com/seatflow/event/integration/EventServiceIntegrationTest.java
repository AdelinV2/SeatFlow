package com.seatflow.event.integration;

import com.seatflow.event.client.SeatMapClient;
import com.seatflow.event.client.SeatMapVenueLayout;
import com.seatflow.event.client.SeatMapVenueSection;
import com.seatflow.event.client.SeatMapVenueSeat;
import com.seatflow.event.model.entity.Event;
import com.seatflow.event.model.entity.EventSession;
import com.seatflow.event.model.entity.OutboxEvent;
import com.seatflow.event.model.enums.EventCategory;
import com.seatflow.event.model.enums.EventSessionStatus;
import com.seatflow.event.model.enums.EventStatus;
import com.seatflow.event.repository.EventPricingTierRepository;
import com.seatflow.event.repository.EventRepository;
import com.seatflow.event.repository.EventSessionRepository;
import com.seatflow.event.repository.OutboxEventRepository;
import com.seatflow.event.service.EventPricingService;
import com.seatflow.event.service.EventService;
import com.seatflow.event.messaging.producer.OutboxEventPublisher;
import com.seatflow.event.support.StagedMigrationSupport;
import com.seatflow.event.web.dto.request.ConfigurePricingRequest;
import com.seatflow.event.web.dto.request.CreateEventRequest;
import com.seatflow.event.web.dto.request.PricingTierItemRequest;
import com.seatflow.event.web.dto.request.UpdateEventRequest;
import com.seatflow.event.web.dto.response.EventDetailResponse;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class EventServiceIntegrationTest {

    private static final UUID SECTION_ID = UUID.randomUUID();
    private static final UUID VENUE_ID = UUID.randomUUID();

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
    private EventPricingService eventPricingService;
    @Autowired
    private OutboxEventPublisher outboxEventPublisher;
    @Autowired
    private OutboxEventRepository outboxRepository;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private EventPricingTierRepository pricingTierRepository;
    @Autowired
    private EventSessionRepository eventSessionRepository;
    @Autowired
    private Flyway flyway;
    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        pricingTierRepository.deleteAll();
        outboxRepository.deleteAll();
        eventRepository.deleteAll();
    }

    @Test
    void flywayAppliesV1V2V3V4ChainOnEmptyDatabase() {
        List<String> appliedVersions = java.util.Arrays.stream(flyway.info().applied())
                .map(m -> m.getVersion().getVersion())
                .toList();

        // P12-007: V4 drops the legacy event_date column after the parity gate.
        assertThat(appliedVersions).containsExactly("1", "2", "3", "4");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM event_sessions", Integer.class))
                .isZero();
    }

    @Test
    void backfillParityForPastFutureAndBoundaryEvents() {
        StagedMigrationSupport.FreshDatabase staged =
                StagedMigrationSupport.migrateToV2(postgres, "seatflow_event_backfill_it");
        JdbcTemplate jdbc = staged.jdbc();

        Instant past = Instant.parse("2025-06-15T19:00:00Z");
        Instant future = Instant.parse("2027-03-20T20:30:00Z");
        Instant boundary = Instant.parse("2026-01-01T00:00:00Z");
        Instant completedPast = Instant.parse("2024-12-01T19:00:00Z");
        UUID pastId = StagedMigrationSupport.insertLegacyEvent(jdbc, "Past Gig", past, "PUBLISHED");
        UUID futureId = StagedMigrationSupport.insertLegacyEvent(jdbc, "Future Gig", future, "PUBLISHED");
        UUID boundaryId = StagedMigrationSupport.insertLegacyEvent(jdbc, "Boundary Gig", boundary, "DRAFT");
        UUID completedId = StagedMigrationSupport.insertLegacyEvent(
                jdbc, "Completed Gig", completedPast, "COMPLETED");

        StagedMigrationSupport.applyV3(staged);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(DISTINCT event_id) FROM event_sessions WHERE legacy_backfill = TRUE",
                Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_sessions s JOIN events e ON e.id = s.event_id"
                        + " WHERE s.legacy_backfill = TRUE AND s.starts_at IS DISTINCT FROM e.event_date",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_sessions"
                        + " WHERE legacy_backfill = TRUE AND ends_at IS DISTINCT FROM starts_at + INTERVAL '2 hours'",
                Integer.class)).isZero();

        assertThat(sessionStart(jdbc, pastId)).isEqualTo(past);
        assertThat(sessionStart(jdbc, futureId)).isEqualTo(future);
        assertThat(sessionStart(jdbc, boundaryId)).isEqualTo(boundary);
        assertThat(sessionStatus(jdbc, completedId)).isEqualTo("COMPLETED");
        assertThat(sessionStatus(jdbc, pastId)).isEqualTo("SCHEDULED");
        assertThat(sessionStatus(jdbc, boundaryId)).isEqualTo("SCHEDULED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_sessions WHERE legacy_backfill = TRUE AND timezone IS NOT NULL",
                Integer.class)).isZero();
    }

    private static Instant sessionStart(JdbcTemplate jdbc, UUID eventId) {
        return ((java.sql.Timestamp) jdbc.queryForMap(
                "SELECT starts_at FROM event_sessions WHERE event_id = ? AND legacy_backfill = TRUE",
                eventId).get("starts_at")).toInstant();
    }

    private static String sessionStatus(JdbcTemplate jdbc, UUID eventId) {
        return (String) jdbc.queryForMap(
                "SELECT status FROM event_sessions WHERE event_id = ? AND legacy_backfill = TRUE",
                eventId).get("status");
    }

    @Test
    void deletingEventRemovesSessionsThroughDatabaseCascade() {
        UUID eventId = eventService.createEvent(new CreateEventRequest(
                VENUE_ID, "Cascade Show", "desc", EventCategory.CONCERT,
                null)).id();
        Event event = eventRepository.findById(eventId).orElseThrow();
        Instant startsAt = Instant.now().plusSeconds(86400);
        eventSessionRepository.saveAndFlush(EventSession.builder()
                .event(event)
                .startsAt(startsAt)
                .endsAt(startsAt.plusSeconds(7200))
                .status(EventSessionStatus.SCHEDULED)
                .legacyBackfill(false)
                .build());
        eventSessionRepository.saveAndFlush(EventSession.builder()
                .event(event)
                .startsAt(startsAt.plusSeconds(86400))
                .endsAt(startsAt.plusSeconds(86400 + 7200))
                .status(EventSessionStatus.SCHEDULED)
                .legacyBackfill(false)
                .build());
        assertThat(eventSessionRepository.findByEvent_IdOrderByStartsAtAscIdAsc(eventId)).hasSize(2);

        eventRepository.deleteById(eventId);
        eventRepository.flush();

        assertThat(eventSessionRepository.findByEvent_IdOrderByStartsAtAscIdAsc(eventId)).isEmpty();
        String deleteRule = jdbcTemplate.queryForObject(
                "SELECT rc.delete_rule FROM information_schema.referential_constraints rc"
                        + " JOIN information_schema.table_constraints tc"
                        + " ON tc.constraint_name = rc.constraint_name"
                        + " WHERE tc.constraint_name = 'fk_event_sessions_event'",
                String.class);
        assertThat(deleteRule).isEqualTo("CASCADE");
    }

    @Test
    void existingEventReadsUnchangedAfterV4() {
        // P12-007: events carry no schedule instant; detail exposes sessions only.
        UUID eventId = eventService.createEvent(new CreateEventRequest(
                VENUE_ID, "Hamlet", "A play", EventCategory.THEATRE,
                "https://cdn.example.com/h.png")).id();

        EventDetailResponse detail = eventService.getEventForAdministration(eventId);

        assertThat(detail.id()).isEqualTo(eventId);
        assertThat(detail.title()).isEqualTo("Hamlet");
        assertThat(detail.status()).isEqualTo(EventStatus.DRAFT);
        assertThat(detail.category()).isEqualTo(EventCategory.THEATRE);
        assertThat(eventSessionRepository.findByEvent_IdOrderByStartsAtAscIdAsc(eventId)).isEmpty();
    }

    @Test
    void createConfigureAndPublish_marksOutboxRowsPublished() {
        CreateEventRequest createReq = new CreateEventRequest(VENUE_ID, "Hamlet", "A play",
                EventCategory.CONCERT, "https://cdn.example.com/h.png");
        EventDetailResponse created = eventService.createEvent(createReq);
        UUID eventId = created.id();
        assertThat(eventId).isNotNull();

        ConfigurePricingRequest pricingReq = new ConfigurePricingRequest(List.of(
                new PricingTierItemRequest(SECTION_ID, "VIP", new BigDecimal("129.90"), "USD")));
        eventPricingService.configurePricing(eventId, pricingReq);

        Event event = eventRepository.findById(eventId).orElseThrow();
        Instant sessionStart = Instant.now().plusSeconds(86400);
        eventSessionRepository.saveAndFlush(EventSession.builder()
                .event(event)
                .startsAt(sessionStart)
                .endsAt(sessionStart.plusSeconds(7200))
                .status(EventSessionStatus.SCHEDULED)
                .legacyBackfill(false)
                .build());

        eventService.updateEvent(eventId, new UpdateEventRequest(null, null, null, null, EventStatus.PUBLISHED));

        List<OutboxEvent> before = outboxRepository.findAll();
        assertThat(before).hasSize(2);
        for (OutboxEvent e : before) {
            assertThat(e.getPublishedAt()).isNull();
            assertThat(e.getAggregateId()).isEqualTo(eventId);
            assertThat(e.getPayload()).containsKeys("eventId", "eventType", "aggregateId", "payload", "version", "occurredAt");
            assertThat(e.getEventType()).isIn("EVENT_CREATED", "EVENT_PUBLISHED");
        }

        outboxEventPublisher.publishPendingEvents();

        verify(kafkaTemplate, times(2)).send(anyString(), eq(eventId.toString()), anyString());

        List<OutboxEvent> after = outboxRepository.findAll();
        assertThat(after).hasSize(2);
        for (OutboxEvent e : after) {
            assertThat(e.getPublishedAt()).isNotNull();
            assertThat(e.getRetryCount()).isZero();
            assertThat(e.getEventType()).isIn("EVENT_CREATED", "EVENT_PUBLISHED");
            assertThat(e.getAggregateId()).isEqualTo(eventId);
        }
    }

    @Test
    void configurePricing_replacesExistingTierWithoutUniqueConstraintViolation() {
        UUID eventId = eventService.createEvent(new CreateEventRequest(
                VENUE_ID, "Pricing Update", "Test event", EventCategory.CONCERT,
                null)).id();

        eventPricingService.configurePricing(eventId, new ConfigurePricingRequest(List.of(
                new PricingTierItemRequest(SECTION_ID, "Standard", new BigDecimal("20.00"), "USD"))));

        var updatedTiers = eventPricingService.configurePricing(eventId, new ConfigurePricingRequest(List.of(
                new PricingTierItemRequest(SECTION_ID, "Standard", new BigDecimal("25.00"), "USD"))));

        assertThat(updatedTiers).hasSize(1);
        assertThat(updatedTiers.getFirst().categoryName()).isEqualTo("Standard");
        assertThat(updatedTiers.getFirst().price()).isEqualByComparingTo("25.00");
        assertThat(pricingTierRepository.findByEvent_IdOrderByPriceAsc(eventId)).hasSize(1);
    }

    @Test
    void cancelEvent_emitsEventCancelledOutbox() {
        UUID eventId = eventService.createEvent(new CreateEventRequest(VENUE_ID, "Othello", "desc",
                EventCategory.OTHER, null)).id();
        eventService.updateEvent(eventId, new UpdateEventRequest(null, null, null, null, EventStatus.CANCELLED));

        List<OutboxEvent> rows = outboxRepository.findAll();
        assertThat(rows).anyMatch(e -> "EVENT_CANCELLED".equals(e.getEventType())
                && e.getAggregateId().equals(eventId)
                && e.getPublishedAt() == null);
    }
}

