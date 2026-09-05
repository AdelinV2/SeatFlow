package com.seatflow.event.support;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Staged-migration harness for V3 verification.
 *
 * <p>Spins up a throwaway database inside the running Testcontainers PostgreSQL,
 * applies {@code V1} and {@code V2} with Spring's {@code ScriptUtils} (so the
 * checked-in SQL files themselves are executed), inserts representative legacy
 * event rows, then applies {@code V3}. Tests assert 1:1 backfill parity on the
 * resulting database. The application database used by the surrounding test is
 * never touched.
 */
public final class StagedMigrationSupport {

    private static final String V1 = "db/migration/V1__create_events_and_pricing_tables.sql";
    private static final String V2 = "db/migration/V2__create_outbox_events_table.sql";
    private static final String V3 = "db/migration/V3__create_event_sessions_and_backfill.sql";

    private StagedMigrationSupport() {
    }

    public record FreshDatabase(JdbcTemplate jdbc, String jdbcUrl) {
    }

    public static FreshDatabase migrateToV2(PostgreSQLContainer<?> postgres, String dbName) {
        try (Connection admin = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            admin.createStatement().execute("CREATE DATABASE \"" + dbName + "\"");
        } catch (Exception ex) {
            throw new IllegalStateException("Could not create staged migration database " + dbName, ex);
        }
        String baseUrl = postgres.getJdbcUrl();
        String jdbcUrl = baseUrl.substring(0, baseUrl.lastIndexOf('/')) + "/" + dbName;
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(
                new org.postgresql.Driver(), jdbcUrl, postgres.getUsername(), postgres.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        executeScript(jdbc, V1);
        executeScript(jdbc, V2);
        return new FreshDatabase(jdbc, jdbcUrl);
    }

    public static void applyV3(FreshDatabase database) {
        executeScript(database.jdbc(), V3);
    }

    public static UUID insertLegacyEvent(JdbcTemplate jdbc, String title, Instant eventDate, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO events (id, venue_id, title, description, category, event_date, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, UUID.randomUUID(), title, "Legacy description", "CONCERT",
                Timestamp.from(eventDate), status);
        return id;
    }

    private static void executeScript(JdbcTemplate jdbc, String classpathLocation) {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
                    connection, new ClassPathResource(classpathLocation));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not execute migration script " + classpathLocation, ex);
        }
    }
}
