package com.seatflow.reservation.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
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

    private static final String[] STAGED = {
            "db/migration/V1__create_reservations_and_seat_holds_tables.sql",
            "db/migration/V2__create_outbox_events_table.sql",
            "db/migration/V3__add_seat_checkout_details.sql",
            "db/migration/V4__add_seat_holds_pricing_tier_index.sql",
            "db/migration/V5__add_seat_holds_active_held_index.sql",
            "db/migration/V6__add_event_session_inventory_key.sql",
            "db/migration/V7__add_session_schedule_snapshot.sql"
    };
    private static final String V8 = "db/migration/V8__enforce_session_inventory_key.sql";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_reservation_test")
            .withUsername("test")
            .withPassword("test");

    private JdbcTemplate stagedDatabase(String dbName) {
        try (Connection admin = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            admin.createStatement().execute("CREATE DATABASE \"" + dbName + "\"");
        } catch (Exception ex) {
            throw new IllegalStateException("Could not create staged database " + dbName, ex);
        }
        String baseUrl = postgres.getJdbcUrl();
        String jdbcUrl = baseUrl.substring(0, baseUrl.lastIndexOf('/')) + "/" + dbName;
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
                new org.postgresql.Driver(), jdbcUrl, postgres.getUsername(), postgres.getPassword()));
        for (String script : STAGED) {
            executeScript(jdbc, script);
        }
        return jdbc;
    }

    private void executeScript(JdbcTemplate jdbc, String classpathLocation) {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
                    connection, new ClassPathResource(classpathLocation));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not execute migration script " + classpathLocation, ex);
        }
    }

    private void applyV8(JdbcTemplate jdbc) {
        // V8 contains a dollar-quoted DO gate block that Spring's ScriptUtils
        // would split on inner semicolons, so the whole file is executed as one
        // statement batch like Flyway does.
        try (Connection connection = jdbc.getDataSource().getConnection();
                java.sql.Statement statement = connection.createStatement();
                java.io.InputStream in = new ClassPathResource(V8).getInputStream()) {
            String sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            statement.execute(sql);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not execute migration script " + V8, ex);
        }
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
        JdbcTemplate jdbc = stagedDatabase("seatflow_res_v8_gate_it");
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
