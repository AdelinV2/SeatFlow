package com.seatflow.reservation.migration;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;

/**
 * Staged-migration harness for reservation-service V8 verification.
 *
 * <p>Spins up a throwaway database inside the running Testcontainers PostgreSQL,
 * applies the checked-in {@code V1..V7} scripts with Spring's {@code ScriptUtils}
 * (so the checked-in SQL files themselves are executed), and applies the
 * {@code V8} fail-closed gate as one statement batch like Flyway does (V8
 * contains a dollar-quoted {@code DO} block that script splitters would break).
 * Tests insert representative legacy rows, drive the backfill-equivalent session
 * assignment, and assert gate/relationship behavior on the resulting database.
 * The application database used by any surrounding test is never touched.
 *
 * <p>Single source of truth for the staged migration list and whole-script
 * execution behavior shared by {@code V8SessionGateMigrationTest} and
 * {@code SessionMigrationDeterminismRegressionTest} (P12-008 REV-007).
 */
public final class StagedMigrationSupport {

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

    private StagedMigrationSupport() {
    }

    public record FreshDatabase(JdbcTemplate jdbc, String jdbcUrl) {
    }

    public static FreshDatabase migrateToV7(PostgreSQLContainer<?> postgres, String dbName) {
        try (Connection admin = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            admin.createStatement().execute("CREATE DATABASE \"" + dbName + "\"");
        } catch (Exception ex) {
            throw new IllegalStateException("Could not create staged migration database " + dbName, ex);
        }
        String baseUrl = postgres.getJdbcUrl();
        String jdbcUrl = baseUrl.substring(0, baseUrl.lastIndexOf('/')) + "/" + dbName;
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
                new org.postgresql.Driver(), jdbcUrl, postgres.getUsername(), postgres.getPassword()));
        for (String script : STAGED) {
            executeScript(jdbc, script);
        }
        return new FreshDatabase(jdbc, jdbcUrl);
    }

    public static void applyV8(FreshDatabase database) {
        executeWholeScript(database.jdbc(), V8);
    }

    public static void executeScript(JdbcTemplate jdbc, String classpathLocation) {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
                    connection, new ClassPathResource(classpathLocation));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not execute migration script " + classpathLocation, ex);
        }
    }

    public static void executeWholeScript(JdbcTemplate jdbc, String classpathLocation) {
        try (Connection connection = jdbc.getDataSource().getConnection();
                java.sql.Statement statement = connection.createStatement();
                java.io.InputStream in = new ClassPathResource(classpathLocation).getInputStream()) {
            String sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            statement.execute(sql);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not execute migration script " + classpathLocation, ex);
        }
    }
}
