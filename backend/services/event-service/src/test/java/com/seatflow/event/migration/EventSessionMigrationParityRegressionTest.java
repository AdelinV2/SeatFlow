package com.seatflow.event.migration;

import com.seatflow.event.support.StagedMigrationSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P12-008 scenario G (event-service leg): Flyway-level determinism of the
 * pre-P12 -&gt; session migration chain on a throwaway database running the
 * checked-in {@code V1..V2} scripts, representative legacy fixtures, then
 * {@code V3} and {@code V4}.
 *
 * <p>Proves, against real PostgreSQL:
 * <ul>
 *   <li>one session per legacy event, with exact timestamp parity
 *       ({@code starts_at} equals the legacy {@code event_date} instant);</li>
 *   <li>deterministic derived state ({@code ends_at} is exactly
 *       {@code starts_at + 2 hours}; terminal statuses preserved, everything
 *       else becomes {@code SCHEDULED}; no invented timezone or sales
 *       window);</li>
 *   <li>a second legacy backfill for the same event cannot silently duplicate
 *       (the partial unique index rejects it loudly);</li>
 *   <li>{@code V4} drops the legacy column behind the parity gate and is
 *       safely re-runnable (idempotent no-op via {@code IF EXISTS}).</li>
 * </ul>
 *
 * <p>The application database is never touched.
 */
@Testcontainers
class EventSessionMigrationParityRegressionTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_event_test")
            .withUsername("test")
            .withPassword("test");

    @Test
    @DisplayName("V3 backfills exactly one parity-exact session per legacy event")
    void v3BackfillYieldsOneParityExactSessionPerEvent() {
        StagedMigrationSupport.FreshDatabase staged =
                StagedMigrationSupport.migrateToV2(postgres, "seatflow_event_p12_008_parity");
        JdbcTemplate jdbc = staged.jdbc();

        Instant publishedDate = Instant.parse("2027-05-01T19:30:00Z");
        Instant cancelledDate = Instant.parse("2027-06-02T20:00:00Z");
        Instant draftDate = Instant.parse("2027-07-03T18:00:00Z");
        UUID publishedId = StagedMigrationSupport.insertLegacyEvent(jdbc, "Published Gig", publishedDate, "PUBLISHED");
        UUID cancelledId = StagedMigrationSupport.insertLegacyEvent(jdbc, "Cancelled Gig", cancelledDate, "CANCELLED");
        UUID draftId = StagedMigrationSupport.insertLegacyEvent(jdbc, "Draft Gig", draftDate, "DRAFT");

        StagedMigrationSupport.applyV3(staged);

        // Exactly one session per legacy event — no more, no fewer.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_sessions", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                """
                        SELECT COUNT(*) FROM
                            (SELECT event_id FROM event_sessions GROUP BY event_id HAVING COUNT(*) <> 1) t
                        """,
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                """
                        SELECT COUNT(*) FROM events e LEFT JOIN event_sessions s ON s.event_id = e.id
                            AND s.legacy_backfill = TRUE WHERE s.id IS NULL
                        """,
                Integer.class)).isZero();

        // Exact timestamp parity: starts_at equals the legacy instant verbatim.
        assertThat(jdbc.queryForObject(
                """
                        SELECT COUNT(*) FROM event_sessions s JOIN events e ON e.id = s.event_id
                            WHERE s.legacy_backfill = TRUE AND s.starts_at IS DISTINCT FROM e.event_date
                        """,
                Integer.class)).isZero();

        // Deterministic derived duration: ends_at is exactly starts_at + 2 hours.
        assertThat(jdbc.queryForObject(
                """
                        SELECT COUNT(*) FROM event_sessions
                            WHERE legacy_backfill = TRUE AND ends_at IS DISTINCT FROM starts_at + INTERVAL '2 hours'
                        """,
                Integer.class)).isZero();

        // Explicit status mapping: terminal state preserved, rest SCHEDULED.
        assertThat(sessionStatus(jdbc, publishedId)).isEqualTo("SCHEDULED");
        assertThat(sessionStatus(jdbc, cancelledId)).isEqualTo("CANCELLED");
        assertThat(sessionStatus(jdbc, draftId)).isEqualTo("SCHEDULED");

        // No invented metadata: timezone and sales windows stay NULL.
        assertThat(jdbc.queryForObject(
                """
                        SELECT COUNT(*) FROM event_sessions WHERE legacy_backfill = TRUE
                            AND (timezone IS NOT NULL OR sale_starts_at IS NOT NULL OR sale_ends_at IS NOT NULL)
                        """,
                Integer.class)).isZero();

        // Per-event instant spot checks (starts + derived end).
        assertThat(sessionStartsAt(jdbc, publishedId)).isEqualTo(publishedDate);
        assertThat(sessionEndsAt(jdbc, publishedId)).isEqualTo(publishedDate.plusSeconds(7200));
    }

    @Test
    @DisplayName("A repeated legacy backfill is rejected loudly, never silently duplicated")
    void repeatedLegacyBackfillIsRejectedLoudly() {
        StagedMigrationSupport.FreshDatabase staged =
                StagedMigrationSupport.migrateToV2(postgres, "seatflow_event_p12_008_rerun");
        JdbcTemplate jdbc = staged.jdbc();

        UUID eventId = StagedMigrationSupport.insertLegacyEvent(jdbc, "Rerun Gig",
                Instant.parse("2027-08-04T19:30:00Z"), "PUBLISHED");
        StagedMigrationSupport.applyV3(staged);

        // The partial unique index permits at most one legacy session per event:
        // a repeated backfill raises instead of duplicating.
        assertThatThrownBy(() -> jdbc.update(
                """
                        INSERT INTO event_sessions (event_id, starts_at, ends_at, status, legacy_backfill)
                            VALUES (?, '2027-08-04T19:30:00Z', '2027-08-04T21:30:00Z', 'SCHEDULED', TRUE)
                        """,
                eventId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_sessions WHERE event_id = ?", Integer.class, eventId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("V4 drops the legacy column behind the gate and re-runs as a no-op")
    void v4DropIsGatedAndRerunnable() {
        StagedMigrationSupport.FreshDatabase staged =
                StagedMigrationSupport.migrateToV2(postgres, "seatflow_event_p12_008_v4");
        JdbcTemplate jdbc = staged.jdbc();

        StagedMigrationSupport.insertLegacyEvent(jdbc, "V4 Gig",
                Instant.parse("2027-09-05T19:30:00Z"), "PUBLISHED");
        StagedMigrationSupport.applyV3(staged);
        StagedMigrationSupport.applyV4(staged);

        assertThat(jdbc.queryForObject(
                """
                        SELECT COUNT(*) FROM information_schema.columns
                            WHERE table_name = 'events' AND column_name = 'event_date'
                        """,
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_sessions", Integer.class)).isEqualTo(1);

        // Flyway-style re-run safety: V4 uses IF EXISTS throughout.
        StagedMigrationSupport.applyV4(staged);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_sessions", Integer.class)).isEqualTo(1);
    }

    private static String sessionStatus(JdbcTemplate jdbc, UUID eventId) {
        return jdbc.queryForObject(
                "SELECT status FROM event_sessions WHERE event_id = ? AND legacy_backfill = TRUE",
                String.class, eventId);
    }

    private static Instant sessionStartsAt(JdbcTemplate jdbc, UUID eventId) {
        return jdbc.queryForObject(
                "SELECT starts_at FROM event_sessions WHERE event_id = ? AND legacy_backfill = TRUE",
                java.time.OffsetDateTime.class, eventId).toInstant();
    }

    private static Instant sessionEndsAt(JdbcTemplate jdbc, UUID eventId) {
        return jdbc.queryForObject(
                "SELECT ends_at FROM event_sessions WHERE event_id = ? AND legacy_backfill = TRUE",
                java.time.OffsetDateTime.class, eventId).toInstant();
    }
}
