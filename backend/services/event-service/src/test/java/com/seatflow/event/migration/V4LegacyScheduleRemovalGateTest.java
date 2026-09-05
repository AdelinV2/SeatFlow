package com.seatflow.event.migration;

import com.seatflow.event.support.StagedMigrationSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P12-007: the V4 destructive gate refuses to drop {@code events.event_date}
 * while any event lacks a session, and completes once every event owns
 * session identity. Staged on a throwaway database running the checked-in
 * V1/V2 scripts; the application database is never touched.
 */
@Testcontainers
class V4LegacyScheduleRemovalGateTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_event_test")
            .withUsername("test")
            .withPassword("test");

    @Test
    @DisplayName("V4 refuses the drop while a sessionless event exists, passes once sessions exist")
    void v4GateRefusesSessionlessEventAndPassesAfterBackfill() {
        StagedMigrationSupport.FreshDatabase staged =
                StagedMigrationSupport.migrateToV2(postgres, "seatflow_event_v4_gate_it");
        JdbcTemplate jdbc = staged.jdbc();
        java.util.UUID eventId = StagedMigrationSupport.insertLegacyEvent(jdbc, "Orphan Gig",
                Instant.parse("2027-03-20T20:30:00Z"), "PUBLISHED");
        // V3 creates the sessions table and backfills; removing the backfilled
        // row reproduces the orphan state the V4 gate must refuse.
        StagedMigrationSupport.applyV3(staged);
        jdbc.update("DELETE FROM event_sessions WHERE event_id = ?", eventId);

        assertThatThrownBy(() -> StagedMigrationSupport.applyV4(staged))
                .hasMessageContaining("V4__remove_legacy_event_schedule.sql")
                .hasStackTraceContaining("P12-007 gate");

        jdbc.update(
                "INSERT INTO event_sessions (event_id, starts_at, ends_at, status, legacy_backfill)"
                        + " VALUES (?, '2027-03-20T20:30:00Z', '2027-03-20T22:30:00Z', 'SCHEDULED', TRUE)",
                eventId);
        StagedMigrationSupport.applyV4(staged);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns"
                        + " WHERE table_name = 'events' AND column_name = 'event_date'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_sessions", Integer.class)).isEqualTo(1);
    }
}
