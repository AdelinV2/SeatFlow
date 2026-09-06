package com.seatflow.reservation.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P12-007: the V8 session-integrity gate refuses migration while orphan rows
 * without {@code event_session_id} exist, and passes once every dependent row
 * carries session identity. Staged on a throwaway database running the
 * checked-in V1..V7 scripts; the application database is never touched.
 */
@Testcontainers
class V8SessionGateMigrationTest {

    private static final String V8 = "db/migration/V8__enforce_session_inventory_key.sql";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_reservation_test")
            .withUsername("test")
            .withPassword("test");

    private static StagedMigrationSupport.FreshDatabase stagedDatabase(String dbName) {
        return StagedMigrationSupport.migrateToV7(postgres, dbName);
    }

    private static void applyV8(JdbcTemplate jdbc) {
        StagedMigrationSupport.executeWholeScript(jdbc, V8);
    }

    private UUID insertLegacyReservation(JdbcTemplate jdbc) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO reservations (id, customer_email, event_id, status, expires_at,"
                        + " idempotency_key, total_amount, seat_count)"
                        + " VALUES (?, ?, ?, 'PENDING', now() + INTERVAL '15 minutes', ?, 10.00, 1)",
                id, "legacy@seatflow.com", UUID.randomUUID(), "legacy-" + UUID.randomUUID());
        UUID holdId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO seat_holds (id, reservation_id, event_id, seat_id, status, price)"
                        + " VALUES (?, ?, (SELECT event_id FROM reservations WHERE id = ?), ?, 'HELD', 10.00)",
                holdId, id, id, UUID.randomUUID());
        return id;
    }

    @Test
    @DisplayName("V8 refuses migration while orphan session refs exist, passes after backfill")
    void v8GateRefusesOrphansAndPassesAfterBackfill() {
        JdbcTemplate jdbc = stagedDatabase("seatflow_res_v8_gate_it").jdbc();
        UUID legacyId = insertLegacyReservation(jdbc);

        assertThatThrownBy(() -> applyV8(jdbc))
                .hasMessageContaining("V8__enforce_session_inventory_key.sql")
                .hasStackTraceContaining("P12-007 gate");

        UUID sessionId = UUID.randomUUID();
        jdbc.update("UPDATE seat_holds SET event_session_id = ? WHERE reservation_id = ?", sessionId, legacyId);
        jdbc.update("UPDATE reservations SET event_session_id = ? WHERE id = ?", sessionId, legacyId);

        applyV8(jdbc);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reservations WHERE event_session_id IS NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat_holds WHERE event_session_id IS NULL", Integer.class)).isZero();
    }
}
