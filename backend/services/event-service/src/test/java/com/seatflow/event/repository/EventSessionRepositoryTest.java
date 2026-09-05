package com.seatflow.event.repository;

import com.seatflow.event.model.entity.Event;
import com.seatflow.event.model.entity.EventSession;
import com.seatflow.event.model.enums.EventCategory;
import com.seatflow.event.model.enums.EventSessionStatus;
import com.seatflow.event.model.enums.EventStatus;
import com.seatflow.event.support.StagedMigrationSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
class EventSessionRepositoryTest {

    private static final Instant PAST = Instant.parse("2025-06-15T19:00:00Z");
    private static final Instant FUTURE = Instant.parse("2027-03-20T20:30:00Z");
    private static final Instant FAR_FUTURE = Instant.parse("2027-11-05T18:00:00Z");

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
    private EventSessionRepository sessionRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EntityManager entityManager;

    private Event savedEvent(EventStatus status, Instant eventDate) {
        return eventRepository.saveAndFlush(Event.builder()
                .venueId(UUID.randomUUID())
                .title("Session Show")
                .description("A compelling description")
                .category(EventCategory.CONCERT)
                .eventDate(eventDate)
                .status(status)
                .build());
    }

    private EventSession.EventSessionBuilder baseSession(Event event, Instant startsAt, Instant endsAt) {
        return EventSession.builder()
                .event(event)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .status(EventSessionStatus.SCHEDULED)
                .legacyBackfill(false);
    }

    @Test
    void shouldPersistSessionWithLegacyBackfillDefaultingToFalse() {
        Event event = savedEvent(EventStatus.PUBLISHED, FUTURE);

        EventSession saved = sessionRepository.saveAndFlush(
                baseSession(event, FUTURE, FUTURE.plusSeconds(7200)).build());
        entityManager.clear();

        EventSession found = sessionRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getLegacyBackfill()).isFalse();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
        assertThat(found.getStatus()).isEqualTo(EventSessionStatus.SCHEDULED);
    }

    @Test
    void shouldRejectSecondLegacySessionForSameEvent() {
        Event event = savedEvent(EventStatus.PUBLISHED, FUTURE);
        sessionRepository.saveAndFlush(baseSession(event, FUTURE, FUTURE.plusSeconds(7200))
                .legacyBackfill(true)
                .build());

        assertThatThrownBy(() -> sessionRepository.saveAndFlush(
                baseSession(event, FUTURE.plusSeconds(86400), FUTURE.plusSeconds(86400 + 7200))
                        .legacyBackfill(true)
                        .build()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void shouldAllowMultipleNonLegacySessionsForSameEvent() {
        Event event = savedEvent(EventStatus.PUBLISHED, FUTURE);
        sessionRepository.saveAndFlush(baseSession(event, FUTURE, FUTURE.plusSeconds(7200)).build());
        sessionRepository.saveAndFlush(
                baseSession(event, FUTURE.plusSeconds(86400), FUTURE.plusSeconds(86400 + 7200)).build());

        assertThat(sessionRepository.findByEvent_IdOrderByStartsAtAscIdAsc(event.getId())).hasSize(2);
    }

    @Test
    void shouldRejectSessionWhenEndsAtEqualsStartsAt() {
        Event event = savedEvent(EventStatus.PUBLISHED, FUTURE);

        assertThatThrownBy(() -> sessionRepository.saveAndFlush(
                baseSession(event, FUTURE, FUTURE).build()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void shouldRejectSessionWhenEndsAtIsBeforeStartsAt() {
        Event event = savedEvent(EventStatus.PUBLISHED, FUTURE);

        assertThatThrownBy(() -> sessionRepository.saveAndFlush(
                baseSession(event, FUTURE, FUTURE.minusSeconds(3600)).build()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void shouldRejectSessionWhenSaleStartsAtIsNotBeforeSaleEndsAt() {
        Event event = savedEvent(EventStatus.PUBLISHED, FUTURE);

        assertThatThrownBy(() -> sessionRepository.saveAndFlush(
                baseSession(event, FUTURE, FUTURE.plusSeconds(7200))
                        .saleStartsAt(FUTURE.minusSeconds(3600))
                        .saleEndsAt(FUTURE.minusSeconds(3600))
                        .build()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void shouldRejectSessionWhenSaleEndsAtIsAfterStartsAt() {
        Event event = savedEvent(EventStatus.PUBLISHED, FUTURE);

        assertThatThrownBy(() -> sessionRepository.saveAndFlush(
                baseSession(event, FUTURE, FUTURE.plusSeconds(7200))
                        .saleStartsAt(FUTURE.minusSeconds(7200))
                        .saleEndsAt(FUTURE.plusSeconds(60))
                        .build()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void shouldRejectSessionForUnknownEvent() {
        Event ghost = entityManager.getReference(Event.class, UUID.randomUUID());

        assertThatThrownBy(() -> sessionRepository.saveAndFlush(
                baseSession(ghost, FUTURE, FUTURE.plusSeconds(7200)).build()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void shouldOrderSessionsByStartsAtThenIdDeterministically() {
        Event event = savedEvent(EventStatus.PUBLISHED, FUTURE);
        EventSession later = sessionRepository.saveAndFlush(
                baseSession(event, FUTURE.plusSeconds(7200), FUTURE.plusSeconds(14400)).build());
        EventSession earlyA = sessionRepository.saveAndFlush(
                baseSession(event, FUTURE, FUTURE.plusSeconds(3600)).build());
        EventSession earlyB = sessionRepository.saveAndFlush(
                baseSession(event, FUTURE, FUTURE.plusSeconds(3600)).build());
        entityManager.clear();

        List<EventSession> ordered = sessionRepository.findByEvent_IdOrderByStartsAtAscIdAsc(event.getId());

        assertThat(ordered).hasSize(3);
        assertThat(ordered.get(2).getId()).isEqualTo(later.getId());
        assertThat(List.of(ordered.get(0).getId(), ordered.get(1).getId()))
                .containsExactlyInAnyOrder(earlyA.getId(), earlyB.getId());
        List<EventSession> reread = sessionRepository.findByEvent_IdOrderByStartsAtAscIdAsc(event.getId());
        assertThat(reread.stream().map(EventSession::getId).toList())
                .isEqualTo(ordered.stream().map(EventSession::getId).toList());
    }

    @Test
    void shouldFindSessionOnlyForMatchingEventPair() {        Event eventA = savedEvent(EventStatus.PUBLISHED, FUTURE);
        Event eventB = savedEvent(EventStatus.PUBLISHED, FAR_FUTURE);
        EventSession session = sessionRepository.saveAndFlush(
                baseSession(eventA, FUTURE, FUTURE.plusSeconds(7200)).build());

        assertThat(sessionRepository.findByIdAndEvent_Id(session.getId(), eventA.getId())).isPresent();
        assertThat(sessionRepository.findByIdAndEvent_Id(session.getId(), eventB.getId())).isEmpty();
        assertThat(sessionRepository.findByIdAndEvent_Id(UUID.randomUUID(), eventA.getId())).isEmpty();
    }

    @Test
    void shouldCountLegacySessionsForMigrationVerification() {
        Event legacyA = savedEvent(EventStatus.PUBLISHED, PAST);
        Event legacyB = savedEvent(EventStatus.PUBLISHED, FUTURE);
        Event regular = savedEvent(EventStatus.PUBLISHED, FAR_FUTURE);
        sessionRepository.saveAndFlush(baseSession(legacyA, PAST, PAST.plusSeconds(7200))
                .legacyBackfill(true).build());
        sessionRepository.saveAndFlush(baseSession(legacyB, FUTURE, FUTURE.plusSeconds(7200))
                .legacyBackfill(true).build());
        sessionRepository.saveAndFlush(
                baseSession(regular, FAR_FUTURE, FAR_FUTURE.plusSeconds(7200)).build());

        assertThat(sessionRepository.countByLegacyBackfillTrue()).isEqualTo(2);
        assertThat(sessionRepository.countByEvent_IdAndLegacyBackfillTrue(legacyA.getId())).isEqualTo(1);
        assertThat(sessionRepository.existsByEvent_IdAndLegacyBackfillTrue(regular.getId())).isFalse();
        assertThat(sessionRepository.findByLegacyBackfillTrueOrderByStartsAtAscIdAsc()).hasSize(2);
    }

    @Test
    void v3BackfillCreatesExactlyOneInstantPreservingSessionPerLegacyEvent() {
        StagedMigrationSupport.FreshDatabase staged =
                StagedMigrationSupport.migrateToV2(postgres, "seatflow_event_backfill_p12001");
        JdbcTemplate jdbc = staged.jdbc();

        UUID pastPublished = StagedMigrationSupport.insertLegacyEvent(jdbc, "Past Gig", PAST, "PUBLISHED");
        UUID futurePublished = StagedMigrationSupport.insertLegacyEvent(jdbc, "Future Gig", FUTURE, "PUBLISHED");
        UUID futureDraft = StagedMigrationSupport.insertLegacyEvent(jdbc, "Draft Gig", FAR_FUTURE, "DRAFT");
        UUID pastCompleted = StagedMigrationSupport.insertLegacyEvent(
                jdbc, "Completed Gig", Instant.parse("2024-12-01T19:00:00Z"), "COMPLETED");
        UUID cancelled = StagedMigrationSupport.insertLegacyEvent(
                jdbc, "Cancelled Gig", Instant.parse("2027-01-10T19:00:00Z"), "CANCELLED");
        UUID boundary = StagedMigrationSupport.insertLegacyEvent(
                jdbc, "Boundary Gig", Instant.parse("2020-01-01T00:00:00Z"), "PUBLISHED");

        StagedMigrationSupport.applyV3(staged);

        Integer eventCount = jdbc.queryForObject("SELECT COUNT(*) FROM events", Integer.class);
        Integer sessionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_sessions WHERE legacy_backfill = TRUE", Integer.class);
        assertThat(eventCount).isEqualTo(6);
        assertThat(sessionCount).isEqualTo(eventCount);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM events e LEFT JOIN event_sessions s"
                        + " ON s.event_id = e.id AND s.legacy_backfill = TRUE WHERE s.id IS NULL",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_sessions s JOIN events e ON e.id = s.event_id"
                        + " WHERE s.legacy_backfill = TRUE AND s.starts_at IS DISTINCT FROM e.event_date",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_sessions"
                        + " WHERE legacy_backfill = TRUE AND ends_at IS DISTINCT FROM starts_at + INTERVAL '2 hours'",
                Integer.class)).isZero();

        Map<UUID, String> expectedStatus = Map.of(
                pastPublished, "SCHEDULED",
                futurePublished, "SCHEDULED",
                futureDraft, "SCHEDULED",
                pastCompleted, "COMPLETED",
                cancelled, "CANCELLED",
                boundary, "SCHEDULED");
        Map<UUID, Instant> expectedStarts = Map.of(
                pastPublished, PAST,
                futurePublished, FUTURE,
                futureDraft, FAR_FUTURE,
                pastCompleted, Instant.parse("2024-12-01T19:00:00Z"),
                cancelled, Instant.parse("2027-01-10T19:00:00Z"),
                boundary, Instant.parse("2020-01-01T00:00:00Z"));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT event_id, starts_at, ends_at, sale_starts_at, sale_ends_at, status, timezone,"
                        + " legacy_backfill FROM event_sessions WHERE legacy_backfill = TRUE");
        assertThat(rows).hasSize(6);
        for (Map<String, Object> row : rows) {
            UUID eventId = (UUID) row.get("event_id");
            Instant startsAt = ((Timestamp) row.get("starts_at")).toInstant();
            Instant endsAt = ((Timestamp) row.get("ends_at")).toInstant();
            assertThat(startsAt).isEqualTo(expectedStarts.get(eventId));
            assertThat(endsAt).isEqualTo(expectedStarts.get(eventId).plusSeconds(7200));
            assertThat(row.get("status")).isEqualTo(expectedStatus.get(eventId));
            assertThat(row.get("timezone")).isNull();
            assertThat(row.get("sale_starts_at")).isNull();
            assertThat(row.get("sale_ends_at")).isNull();
            assertThat(row.get("legacy_backfill")).isEqualTo(Boolean.TRUE);
        }

        String deleteRule = jdbc.queryForObject(
                "SELECT rc.delete_rule FROM information_schema.referential_constraints rc"
                        + " JOIN information_schema.table_constraints tc ON tc.constraint_name = rc.constraint_name"
                        + " WHERE tc.constraint_name = 'fk_event_sessions_event'",
                String.class);
        assertThat(deleteRule).isEqualTo("CASCADE");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO event_sessions (event_id, starts_at, ends_at, status, legacy_backfill)"
                        + " VALUES (?, ?, ?, 'SCHEDULED', TRUE)",
                pastPublished, Timestamp.from(FUTURE), Timestamp.from(FUTURE.plusSeconds(7200))))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO event_sessions (event_id, starts_at, ends_at, status, legacy_backfill)"
                        + " VALUES (?, ?, ?, 'SCHEDULED', TRUE)",
                UUID.randomUUID(), Timestamp.from(FUTURE), Timestamp.from(FUTURE.plusSeconds(7200))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void shouldDetectFutureValidSessionForPublishPrecondition() {
        Event withFuture = savedEvent(EventStatus.DRAFT, FUTURE);
        Event pastOnly = savedEvent(EventStatus.DRAFT, PAST);
        sessionRepository.saveAndFlush(baseSession(withFuture, FUTURE, FUTURE.plusSeconds(7200)).build());
        sessionRepository.saveAndFlush(baseSession(pastOnly, PAST, PAST.plusSeconds(7200)).build());

        assertThat(sessionRepository.existsByEvent_IdAndStatusAndStartsAtAfter(
                withFuture.getId(), EventSessionStatus.SCHEDULED, Instant.parse("2026-01-01T00:00:00Z"))).isTrue();
        assertThat(sessionRepository.existsByEvent_IdAndStatusAndStartsAtAfter(
                pastOnly.getId(), EventSessionStatus.SCHEDULED, Instant.parse("2026-01-01T00:00:00Z"))).isFalse();
        assertThat(sessionRepository.existsByEvent_IdAndStatusAndStartsAtAfter(
                withFuture.getId(), EventSessionStatus.CANCELLED, Instant.parse("2026-01-01T00:00:00Z"))).isFalse();
    }

    @Test
    void shouldListCustomerVisibleSessionsExcludingPastAndCancelled() {
        Event event = savedEvent(EventStatus.PUBLISHED, FUTURE);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        EventSession future = sessionRepository.saveAndFlush(
                baseSession(event, FUTURE, FUTURE.plusSeconds(7200)).build());
        sessionRepository.saveAndFlush(
                baseSession(event, PAST, PAST.plusSeconds(7200)).build());
        EventSession cancelledFuture = sessionRepository.saveAndFlush(
                baseSession(event, FUTURE.plusSeconds(86400), FUTURE.plusSeconds(86400 + 7200))
                        .status(EventSessionStatus.CANCELLED).build());

        List<EventSession> visible = sessionRepository
                .findByEvent_IdAndStatusAndEndsAtAfterOrderByStartsAtAscIdAsc(
                        event.getId(), EventSessionStatus.SCHEDULED, now);

        assertThat(visible).extracting(EventSession::getId).containsExactly(future.getId());
        assertThat(visible).extracting(EventSession::getId).doesNotContain(cancelledFuture.getId());
    }

    @Test
    void shouldFindSessionsBlockingCompletion() {
        Event event = savedEvent(EventStatus.PUBLISHED, FUTURE);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        EventSession blocking = sessionRepository.saveAndFlush(
                baseSession(event, FUTURE, FUTURE.plusSeconds(7200)).build());
        sessionRepository.saveAndFlush(
                baseSession(event, PAST, PAST.plusSeconds(7200)).build());
        sessionRepository.saveAndFlush(
                baseSession(event, FUTURE.plusSeconds(86400), FUTURE.plusSeconds(86400 + 7200))
                        .status(EventSessionStatus.CANCELLED).build());

        List<EventSession> blockers = sessionRepository.findByEvent_IdAndStatusNotAndEndsAtAfter(
                event.getId(), EventSessionStatus.CANCELLED, now);

        assertThat(blockers).extracting(EventSession::getId).containsExactly(blocking.getId());
    }

    @Test
    void shouldFindEndedScheduledSessionsForCompletionMarking() {
        Event event = savedEvent(EventStatus.PUBLISHED, FUTURE);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        EventSession ended = sessionRepository.saveAndFlush(
                baseSession(event, PAST, PAST.plusSeconds(7200)).build());
        sessionRepository.saveAndFlush(
                baseSession(event, FUTURE, FUTURE.plusSeconds(7200)).build());

        List<EventSession> endedSessions = sessionRepository.findByEvent_IdAndStatusAndEndsAtLessThanEqual(
                event.getId(), EventSessionStatus.SCHEDULED, now);

        assertThat(endedSessions).extracting(EventSession::getId).containsExactly(ended.getId());
    }
}
