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
 * P12-009: the V9 migration promotes the V8 fail-closed gate to
 * database-enforced {@code NOT NULL} on
 * {@code reservations.event_session_id} and
 * {@code seat_holds.event_session_id}.
 *
 * <p>Staged on throwaway databases running the checked-in scripts; the
 * application database is never touched.
 */
@Testcontainers
class V9SessionNotNullMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_reservation_test")
            .withUsername("test")
            .withPassword("test");

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
    @DisplayName("V9 refuses while orphans remain, then enforces NOT NULL on both tables")
    void v9GateRefusesOrphansThenEnforcesNotNull() {
        StagedMigrationSupport.FreshDatabase staged =
                StagedMigrationSupport.migrateToV7(postgres, "seatflow_res_v9_gate_it");
        JdbcTemplate jdbc = staged.jdbc();
        UUID legacyId = insertLegacyReservation(jdbc);

        assertThatThrownBy(() -> StagedMigrationSupport.applyV9(staged))
                .hasMessageContaining("V9__enforce_session_not_null.sql")
                .hasStackTraceContaining("P12-009 gate");

        UUID sessionId = UUID.randomUUID();
        jdbc.update("UPDATE seat_holds SET event_session_id = ? WHERE reservation_id = ?", sessionId, legacyId);
        jdbc.update("UPDATE reservations SET event_session_id = ? WHERE id = ?", sessionId, legacyId);

        StagedMigrationSupport.applyV9(staged);

        assertThat(isNullable(jdbc, "reservations", "event_session_id")).isFalse();
        assertThat(isNullable(jdbc, "seat_holds", "event_session_id")).isFalse();

        // NULL inserts are rejected at the DB level on both tables.
        UUID reservationId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO reservations (id, customer_email, event_id, status, expires_at,"
                        + " idempotency_key, total_amount, seat_count)"
                        + " VALUES (?, ?, ?, 'PENDING', now() + INTERVAL '15 minutes', ?, 10.00, 1)",
                reservationId, "null-session@seatflow.com", UUID.randomUUID(), "null-" + UUID.randomUUID()))
                .hasStackTraceContaining("event_session_id");

        UUID holdReservationId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO reservations (id, customer_email, event_id, event_session_id, status, expires_at,"
                        + " idempotency_key, total_amount, seat_count)"
                        + " VALUES (?, ?, ?, ?, 'PENDING', now() + INTERVAL '15 minutes', ?, 10.00, 1)",
                holdReservationId, "holder@seatflow.com", UUID.randomUUID(), UUID.randomUUID(),
                "holder-" + UUID.randomUUID());
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO seat_holds (id, reservation_id, event_id, seat_id, status, price)"
                        + " VALUES (?, ?, ?, ?, 'HELD', 10.00)",
                UUID.randomUUID(), holdReservationId, UUID.randomUUID(), UUID.randomUUID()))
                .hasStackTraceContaining("event_session_id");

        // Re-running V9 is a safe no-op.
        StagedMigrationSupport.applyV9(staged);
        assertThat(isNullable(jdbc, "reservations", "event_session_id")).isFalse();
        assertThat(isNullable(jdbc, "seat_holds", "event_session_id")).isFalse();
    }

    @Test
    @DisplayName("V9 lands NOT NULL on a clean V8 database")
    void v9LandsNotNullOnCleanDatabase() {
        StagedMigrationSupport.FreshDatabase staged =
                StagedMigrationSupport.migrateToV8(postgres, "seatflow_res_v9_clean_it");
        StagedMigrationSupport.applyV9(staged);

        assertThat(isNullable(staged.jdbc(), "reservations", "event_session_id")).isFalse();
        assertThat(isNullable(staged.jdbc(), "seat_holds", "event_session_id")).isFalse();
    }

    private static boolean isNullable(JdbcTemplate jdbc, String table, String column) {
        String nullable = jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                String.class, table, column);
        return "YES".equals(nullable);
    }
}
