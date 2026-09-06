package com.seatflow.reservation.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P12-008 scenario G (reservation-service leg): determinism of the staged
 * {@code V1..V7} migration chain plus the backfill + {@code V8} gate on a
 * throwaway database, using a representative pre-P12 fixture (two legacy
 * events with active PENDING/HELD holds and a CONFIRMED/SOLD booking).
 *
 * <p>Proves, against real PostgreSQL:
 * <ul>
 *   <li>{@code V8} still refuses while ANY orphan row remains (one backfilled
 *       event does not excuse another);</li>
 *   <li>after the backfill-equivalent session assignment, {@code V8} passes
 *       with zero NULL dependent session IDs;</li>
 *   <li>the session mapping carried into reservation fixtures is an ACTUAL
 *       event-service {@code V3}-generated mapping (P12-008 REV-002): the
 *       checked-in {@code V1} + {@code V3} scripts are executed against a
 *       throwaway event database, legacy events are inserted before
 *       {@code V3}, and the {@code gen_random_uuid()} session rows
 *       {@code V3} itself backfills are read back and carried — by exact
 *       event UUID — into the reservation fixtures, backfill-equivalent
 *       UPDATEs and {@code V8}. No session UUID is invented; the
 *       starts/ends instants asserted here are the values PostgreSQL
 *       generated, additionally cross-checked against the documented
 *       {@code V3} rule (starts equals the legacy instant verbatim, ends is
 *       exactly starts + 2 hours);</li>
 *   <li>booking relationships are preserved (every hold still joins its
 *       reservation; statuses, seats, seat identity, session agreement, and
 *       row counts are unchanged);</li>
 *   <li>re-running {@code V8} is a safe no-op.</li>
 * </ul>
 *
 * <p>The application database is never touched. The live
 * {@code SessionInventoryBackfillService} path (validation against the trusted
 * event-service booking context, real backfill queries, zero-null gate, rerun
 * no-op) is proven separately by
 * {@code SessionScopedInventoryIntegrationTest} orders 11-13; payment/ticket
 * session columns are additive and NULLABLE by design (payment {@code V4},
 * ticket {@code V5}) with new pipelines always populating them, and their
 * failing-closed behavior is proven by the payment/ticket session suites, not
 * by a cross-database join here (see the review ledger REV-002 for the
 * backfill-fit analysis and the P12-009 follow-up).
 */
@Testcontainers
class SessionMigrationDeterminismRegressionTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_reservation_test")
            .withUsername("test")
            .withPassword("test");

    private record LegacyBooking(UUID reservationId, UUID holdId, UUID eventId, UUID seatId, String status) {
    }

    /**
     * An ACTUAL event-service V3 backfill product: the session row V3's own
     * {@code INSERT ... SELECT} generated (with PostgreSQL's
     * {@code gen_random_uuid()}) for one legacy event row, read back from the
     * throwaway event database.
     */
    private record V3GeneratedSession(UUID eventId, UUID sessionId, Instant startsAt, Instant endsAt) {
    }

    /**
     * Runs the CHECKED-IN event-service {@code V1} + {@code V3} migration
     * scripts against a throwaway event database and backfills real sessions
     * for the given legacy {@code eventId -> eventDate} inputs.
     *
     * <p>Sequence mirrors production deployment: {@code V1} first, then legacy
     * rows are inserted with explicit ids, then {@code V3} generates exactly
     * one session per legacy event. Fails fast if the checked-in scripts
     * cannot be located — silently restating the V3 rule instead would
     * reintroduce the invented-UUID hole this test closes.
     */
    private static Map<UUID, V3GeneratedSession> generateRealV3Sessions(Map<UUID, Instant> legacyEvents) {
        Path migrationDir = Path.of(System.getProperty("user.dir"),
                "..", "event-service", "src", "main", "resources", "db", "migration").normalize();
        Path v1 = migrationDir.resolve("V1__create_events_and_pricing_tables.sql");
        Path v3 = migrationDir.resolve("V3__create_event_sessions_and_backfill.sql");
        assertThat(v1).as("checked-in event-service V1 script must exist at %s", v1).exists();
        assertThat(v3).as("checked-in event-service V3 script must exist at %s", v3).exists();

        String dbName = "seatflow_evt_v3_p12_008_gen";
        try (Connection admin = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            admin.createStatement().execute("DROP DATABASE IF EXISTS \"" + dbName + "\"");
            admin.createStatement().execute("CREATE DATABASE \"" + dbName + "\"");
        } catch (Exception ex) {
            throw new IllegalStateException("Could not create throwaway event database " + dbName, ex);
        }
        String baseUrl = postgres.getJdbcUrl();
        String jdbcUrl = baseUrl.substring(0, baseUrl.lastIndexOf('/')) + "/" + dbName;
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
                new org.postgresql.Driver(), jdbcUrl, postgres.getUsername(), postgres.getPassword()));

        executeFileScript(jdbc, v1);
        for (Map.Entry<UUID, Instant> legacy : legacyEvents.entrySet()) {
            jdbc.update(
                    """
                            INSERT INTO events (id, venue_id, title, description, category, event_date, status)
                                VALUES (?, ?, ?, ?, ?, ?, ?)
                            """,
                    legacy.getKey(), UUID.randomUUID(), "Legacy Gig", "Pre-P12 event",
                    "CONCERT", Timestamp.from(legacy.getValue()), "PUBLISHED");
        }
        executeFileScript(jdbc, v3);

        Map<UUID, V3GeneratedSession> generated = new java.util.HashMap<>();
        for (Map.Entry<UUID, Instant> legacy : legacyEvents.entrySet()) {
            Map<String, Object> row = jdbc.queryForMap(
                    """
                            SELECT id, starts_at, ends_at FROM event_sessions
                                WHERE event_id = ? AND legacy_backfill = TRUE
                            """,
                    legacy.getKey());
            assertThat(row).as("V3 must backfill exactly one session for legacy event %s", legacy.getKey())
                    .isNotNull();
            V3GeneratedSession session = new V3GeneratedSession(
                    legacy.getKey(),
                    (UUID) row.get("id"),
                    ((Timestamp) row.get("starts_at")).toInstant(),
                    ((Timestamp) row.get("ends_at")).toInstant());
            // Rule cross-check on the REAL generated values: starts equals the
            // legacy instant verbatim, ends is exactly starts + 2 hours.
            assertThat(session.startsAt())
                    .as("V3-generated starts_at must equal the legacy instant verbatim")
                    .isEqualTo(legacy.getValue());
            assertThat(session.endsAt())
                    .as("V3-generated ends_at must equal starts_at + 2 hours")
                    .isEqualTo(legacy.getValue().plusSeconds(7200));
            generated.put(legacy.getKey(), session);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_sessions", Integer.class))
                .as("V3 must generate exactly one session per legacy event")
                .isEqualTo(legacyEvents.size());
        return generated;
    }

    private static void executeFileScript(JdbcTemplate jdbc, Path script) {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
                    connection, new FileSystemResource(script.toFile()));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not execute checked-in migration script " + script, ex);
        }
    }

    private LegacyBooking insertLegacyBooking(JdbcTemplate jdbc, UUID eventId, String status, String holdStatus) {
        UUID reservationId = UUID.randomUUID();
        UUID holdId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        jdbc.update(
                """
                        INSERT INTO reservations (id, customer_email, event_id, status, expires_at,
                            idempotency_key, total_amount, seat_count)
                            VALUES (?, ?, ?, ?, now() + INTERVAL '15 minutes', ?, 10.00, 1)
                        """,
                reservationId, "legacy@seatflow.com", eventId, status, "legacy-" + UUID.randomUUID());
        jdbc.update(
                """
                        INSERT INTO seat_holds (id, reservation_id, event_id, seat_id, status, price)
                            VALUES (?, ?, ?, ?, ?, 10.00)
                        """,
                holdId, reservationId, eventId, seatId, holdStatus);
        return new LegacyBooking(reservationId, holdId, eventId, seatId, status);
    }

    private void backfillEquivalent(JdbcTemplate jdbc, LegacyBooking booking, V3GeneratedSession session) {
        // Mirrors what SessionInventoryBackfillService assigns (session identity)
        // plus the V7 snapshot columns, carrying the REAL V3-generated session
        // id and schedule — never an invented UUID.
        assertThat(session.eventId())
                .as("backfill must carry the session V3 generated for this legacy event")
                .isEqualTo(booking.eventId());
        jdbc.update("UPDATE seat_holds SET event_session_id = ? WHERE reservation_id = ?",
                session.sessionId(), booking.reservationId());
        jdbc.update(
                """
                        UPDATE reservations SET event_session_id = ?, session_starts_at = ?, session_ends_at = ?
                            WHERE id = ?
                        """,
                session.sessionId(), Timestamp.from(session.startsAt()), Timestamp.from(session.endsAt()),
                booking.reservationId());
    }

    @Test
    @DisplayName("V8 refuses with any orphan, passes after full V3-rule backfill, preserves relationships, re-runs clean")
    void v8GateIsDeterministicAndRelationshipPreserving() {
        // Representative pre-P12 fixture: a PENDING/HELD hold and a
        // CONFIRMED/SOLD booking under two distinct legacy events with known
        // legacy instants (the V3 backfill input).
        Instant legacyDateA = Instant.parse("2027-05-01T19:30:00Z");
        Instant legacyDateB = Instant.parse("2027-06-02T20:00:00Z");
        UUID legacyEventA = UUID.randomUUID();
        UUID legacyEventB = UUID.randomUUID();

        // REAL V3-generated sessions for exactly these legacy events (UUIDs
        // come from PostgreSQL's gen_random_uuid() inside the checked-in V3
        // script — this test invents none).
        Map<UUID, V3GeneratedSession> generated =
                generateRealV3Sessions(Map.of(legacyEventA, legacyDateA, legacyEventB, legacyDateB));
        V3GeneratedSession sessionA = generated.get(legacyEventA);
        V3GeneratedSession sessionB = generated.get(legacyEventB);

        StagedMigrationSupport.FreshDatabase staged =
                StagedMigrationSupport.migrateToV7(postgres, "seatflow_res_v8_p12_008_det");
        JdbcTemplate jdbc = staged.jdbc();

        LegacyBooking pendingA = insertLegacyBooking(jdbc, legacyEventA, "PENDING", "HELD");
        LegacyBooking confirmedB = insertLegacyBooking(jdbc, legacyEventB, "CONFIRMED", "SOLD");

        // Backfilling only one legacy event must NOT satisfy the gate.
        backfillEquivalent(jdbc, pendingA, sessionA);

        assertThatThrownBy(() -> StagedMigrationSupport.applyV8(staged))
                .hasMessageContaining("V8__enforce_session_inventory_key.sql")
                .hasStackTraceContaining("P12-007 gate");

        // Backfill the remaining legacy event, then the gate passes.
        backfillEquivalent(jdbc, confirmedB, sessionB);

        StagedMigrationSupport.applyV8(staged);

        // Zero NULL dependent session IDs.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reservations WHERE event_session_id IS NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat_holds WHERE event_session_id IS NULL", Integer.class)).isZero();

        // Exact timestamp parity with the REAL V3-generated values — on both
        // the PENDING and the CONFIRMED leg.
        assertThat(instantOf(jdbc,
                "SELECT session_starts_at FROM reservations WHERE id = ?", pendingA.reservationId()))
                .isEqualTo(sessionA.startsAt());
        assertThat(instantOf(jdbc,
                "SELECT session_ends_at FROM reservations WHERE id = ?", pendingA.reservationId()))
                .isEqualTo(sessionA.endsAt());
        assertThat(instantOf(jdbc,
                "SELECT session_starts_at FROM reservations WHERE id = ?", confirmedB.reservationId()))
                .isEqualTo(sessionB.startsAt());
        assertThat(instantOf(jdbc,
                "SELECT session_ends_at FROM reservations WHERE id = ?", confirmedB.reservationId()))
                .isEqualTo(sessionB.endsAt());

        // Booking relationships preserved: every hold still joins its
        // reservation with the same seat, status, and session identity.
        assertThat(jdbc.queryForObject(
                """
                        SELECT COUNT(*) FROM seat_holds h JOIN reservations r ON r.id = h.reservation_id
                            WHERE h.event_session_id IS DISTINCT FROM r.event_session_id
                        """,
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                """
                        SELECT COUNT(*) FROM seat_holds h LEFT JOIN reservations r ON r.id = h.reservation_id
                            WHERE r.id IS NULL
                        """,
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM reservations WHERE id = ?", String.class, pendingA.reservationId()))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM reservations WHERE id = ?", String.class, confirmedB.reservationId()))
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM seat_holds WHERE id = ?", String.class, pendingA.holdId()))
                .isEqualTo("HELD");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM seat_holds WHERE id = ?", String.class, confirmedB.holdId()))
                .isEqualTo("SOLD");
        assertThat(jdbc.queryForObject(
                "SELECT event_session_id FROM seat_holds WHERE id = ?", String.class, pendingA.holdId()))
                .isEqualTo(sessionA.sessionId().toString());
        assertThat(jdbc.queryForObject(
                "SELECT event_session_id FROM seat_holds WHERE id = ?", String.class, confirmedB.holdId()))
                .isEqualTo(sessionB.sessionId().toString());
        // Seat identity preserved: the captured legacy seatId survives the
        // backfill + V8 chain on both legs (P12-008 REV-002 oracle).
        assertThat(UUID.fromString(jdbc.queryForObject(
                "SELECT seat_id FROM seat_holds WHERE id = ?", String.class, pendingA.holdId())))
                .isEqualTo(pendingA.seatId());
        assertThat(UUID.fromString(jdbc.queryForObject(
                "SELECT seat_id FROM seat_holds WHERE id = ?", String.class, confirmedB.holdId())))
                .isEqualTo(confirmedB.seatId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservations", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat_holds", Integer.class)).isEqualTo(2);

        // Re-running V8 is a safe no-op.
        StagedMigrationSupport.applyV8(staged);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reservations WHERE event_session_id IS NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat_holds WHERE event_session_id IS NULL", Integer.class)).isZero();
    }

    private static Instant instantOf(JdbcTemplate jdbc, String sql, UUID id) {
        return jdbc.queryForObject(sql, java.time.OffsetDateTime.class, id).toInstant();
    }
}
