package com.seatflow.reservation.migration;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P12-009: backfill verification on a staged pre-constraint schema.
 *
 * <p>Replaces {@code SessionScopedInventoryIntegrationTest} orders 11-13, which
 * intentionally persisted legacy-NULL {@code event_session_id} rows through JPA
 * on the production migration chain. After V9 those inserts are rejected by
 * {@code NOT NULL}, so the NULL-writing coverage lives here on a throwaway
 * database at V7 (NULLable session key) following the staged-schema pattern
 * from {@code V8SessionGateMigrationTest}. The live-schema concurrency oracle
 * keeps its single-database fidelity (no second DataSource is wired into the
 * Spring context).
 *
 * <p>The backfill-equivalent UPDATEs mirror the production queries in
 * {@code ReservationRepository.backfillSessionIdForLegacyEvent} and
 * {@code SeatHoldRepository.backfillSessionIdForLegacyEvent}; the trusted
 * session-context validation mirrors
 * {@code SessionInventoryBackfillService} (a session may only backfill the
 * legacy event it actually belongs to — the run never guesses). The
 * application database is never touched.
 */
@Testcontainers
class SessionInventoryBackfillStagedSchemaTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_reservation_test")
            .withUsername("test")
            .withPassword("test");

    private record StagedBooking(UUID reservationId, UUID eventId) {
    }

    private StagedBooking insertLegacyBooking(JdbcTemplate jdbc, UUID eventId, String status, String holdStatus) {
        UUID reservationId = UUID.randomUUID();
        UUID holdId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO reservations (id, customer_email, event_id, status, expires_at,"
                        + " idempotency_key, total_amount, seat_count)"
                        + " VALUES (?, ?, ?, ?, now() + INTERVAL '15 minutes', ?, 10.00, 1)",
                reservationId, "legacy@seatflow.com", eventId, status, "legacy-" + UUID.randomUUID());
        jdbc.update(
                "INSERT INTO seat_holds (id, reservation_id, event_id, seat_id, status, price)"
                        + " VALUES (?, ?, ?, ?, ?, 10.00)",
                holdId, reservationId, eventId, UUID.randomUUID(), holdStatus);
        return new StagedBooking(reservationId, eventId);
    }

    /**
     * Mirrors {@code SessionInventoryBackfillService.backfill}: every mapping is
     * validated against the trusted session truth BEFORE mutating anything, then
     * the production-equivalent UPDATEs run per legacy event, then the zero-null
     * gate verifies no orphan remains.
     *
     * @param sessionTruth trusted {@code sessionId -> parent eventId} (stands in
     *                     for {@code EventClient.getSessionBookingContext}).
     */
    private Map<UUID, UUID> backfillEquivalent(JdbcTemplate jdbc, Map<UUID, UUID> legacyEventToSession,
                                               Map<UUID, UUID> sessionTruth) {
        if (legacyEventToSession == null || legacyEventToSession.isEmpty()) {
            throw new ValidationException("A non-empty legacy event -> session mapping is required",
                    ErrorCode.INVALID_REQUEST);
        }
        for (Map.Entry<UUID, UUID> entry : legacyEventToSession.entrySet()) {
            UUID eventId = entry.getKey();
            UUID sessionId = entry.getValue();
            if (eventId == null || sessionId == null) {
                throw new ValidationException("Legacy mapping must not contain null event or session ids",
                        ErrorCode.INVALID_REQUEST);
            }
            UUID trustedParent = sessionTruth.get(sessionId);
            if (!eventId.equals(trustedParent) || trustedParent == null) {
                throw new ValidationException(
                        "Session " + sessionId + " does not resolve to legacy event " + eventId
                                + "; refusing to guess the backfill session",
                        ErrorCode.INVALID_REQUEST);
            }
        }
        Map<UUID, UUID> applied = new LinkedHashMap<>();
        for (Map.Entry<UUID, UUID> entry : legacyEventToSession.entrySet()) {
            UUID eventId = entry.getKey();
            UUID sessionId = entry.getValue();
            jdbc.update("UPDATE reservations SET event_session_id = ? WHERE event_id = ? AND event_session_id IS NULL",
                    sessionId, eventId);
            jdbc.update("UPDATE seat_holds SET event_session_id = ? WHERE event_id = ? AND event_session_id IS NULL",
                    sessionId, eventId);
            applied.put(eventId, sessionId);
        }
        verifyZeroNullSessions(jdbc);
        return applied;
    }

    private void verifyZeroNullSessions(JdbcTemplate jdbc) {
        Integer nullReservations =
                jdbc.queryForObject("SELECT COUNT(*) FROM reservations WHERE event_session_id IS NULL", Integer.class);
        Integer nullHolds =
                jdbc.queryForObject("SELECT COUNT(*) FROM seat_holds WHERE event_session_id IS NULL", Integer.class);
        if ((nullReservations != null && nullReservations > 0) || (nullHolds != null && nullHolds > 0)) {
            List<UUID> orphanReservationEvents = jdbc.queryForList(
                    "SELECT DISTINCT event_id FROM reservations WHERE event_session_id IS NULL", UUID.class);
            throw new IllegalStateException(
                    "Backfill incomplete: " + nullReservations + " reservation(s) and " + nullHolds
                            + " seat hold(s) still lack event_session_id for legacy event(s) "
                            + orphanReservationEvents + ". Refusing to proceed.");
        }
    }

    private static int countNulls(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE event_session_id IS NULL", Integer.class);
    }

    @Test
    @DisplayName("Staged backfill assigns sessions for PENDING/HELD and CONFIRMED/SOLD legs and reruns as no-op")
    void stagedBackfillAssignsSessionAndPassesZeroNullGate() {
        StagedMigrationSupport.FreshDatabase staged =
                StagedMigrationSupport.migrateToV7(postgres, "seatflow_res_p12_009_backfill");
        JdbcTemplate jdbc = staged.jdbc();

        UUID legacyEventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID legacyEventConfirmed = UUID.randomUUID();
        UUID sessionConfirmed = UUID.randomUUID();
        insertLegacyBooking(jdbc, legacyEventId, "PENDING", "HELD");
        insertLegacyBooking(jdbc, legacyEventConfirmed, "CONFIRMED", "SOLD");

        Map<UUID, UUID> sessionTruth = Map.of(sessionId, legacyEventId, sessionConfirmed, legacyEventConfirmed);

        Map<UUID, UUID> applied = backfillEquivalent(jdbc,
                Map.of(legacyEventId, sessionId, legacyEventConfirmed, sessionConfirmed), sessionTruth);
        assertThat(applied).containsEntry(legacyEventId, sessionId).containsEntry(legacyEventConfirmed, sessionConfirmed);
        assertThat(countNulls(jdbc, "reservations")).isZero();
        assertThat(countNulls(jdbc, "seat_holds")).isZero();

        String resSession = jdbc.queryForObject(
                "SELECT event_session_id FROM reservations WHERE event_id = ?", String.class, legacyEventId);
        assertThat(resSession).isEqualTo(sessionId.toString());
        String holdSession = jdbc.queryForObject(
                "SELECT event_session_id FROM seat_holds WHERE reservation_id IN"
                        + " (SELECT id FROM reservations WHERE event_id = ?)",
                String.class, legacyEventId);
        assertThat(holdSession).isEqualTo(sessionId.toString());
        String confirmedStatus = jdbc.queryForObject(
                "SELECT status FROM reservations WHERE event_id = ?", String.class, legacyEventConfirmed);
        assertThat(confirmedStatus).isEqualTo("CONFIRMED");
        String soldStatus = jdbc.queryForObject(
                "SELECT status FROM seat_holds WHERE reservation_id IN"
                        + " (SELECT id FROM reservations WHERE event_id = ?)",
                String.class, legacyEventConfirmed);
        assertThat(soldStatus).isEqualTo("SOLD");

        // Rerun is a safe no-op: same mapping applies zero additional rows and the gate still passes.
        int resUpdated = jdbc.update(
                "UPDATE reservations SET event_session_id = ? WHERE event_id = ? AND event_session_id IS NULL",
                sessionId, legacyEventId);
        int holdUpdated = jdbc.update(
                "UPDATE seat_holds SET event_session_id = ? WHERE event_id = ? AND event_session_id IS NULL",
                sessionId, legacyEventId);
        assertThat(resUpdated).isZero();
        assertThat(holdUpdated).isZero();
        verifyZeroNullSessions(jdbc);
    }

    @Test
    @DisplayName("Staged backfill fails closed on mismatched session without mutating anything")
    void stagedBackfillFailsClosedOnMismatchedSession() {
        StagedMigrationSupport.FreshDatabase staged =
                StagedMigrationSupport.migrateToV7(postgres, "seatflow_res_p12_009_mismatch");
        JdbcTemplate jdbc = staged.jdbc();

        UUID legacyEventId = UUID.randomUUID();
        UUID foreignEventId = UUID.randomUUID();
        UUID foreignSessionId = UUID.randomUUID();
        insertLegacyBooking(jdbc, legacyEventId, "PENDING", "HELD");

        Map<UUID, UUID> sessionTruth = Map.of(foreignSessionId, foreignEventId);

        assertThatThrownBy(() -> backfillEquivalent(jdbc, Map.of(legacyEventId, foreignSessionId), sessionTruth))
                .isInstanceOf(ValidationException.class);

        assertThat(countNulls(jdbc, "reservations")).isEqualTo(1);
        assertThat(countNulls(jdbc, "seat_holds")).isEqualTo(1);
    }

    @Test
    @DisplayName("Staged backfill gate fails on unmapped legacy event instead of guessing")
    void stagedBackfillGateFailsOnUnmappedLegacyEvent() {
        StagedMigrationSupport.FreshDatabase staged =
                StagedMigrationSupport.migrateToV7(postgres, "seatflow_res_p12_009_orphan");
        JdbcTemplate jdbc = staged.jdbc();

        UUID mappedEventId = UUID.randomUUID();
        UUID mappedSessionId = UUID.randomUUID();
        UUID orphanEventId = UUID.randomUUID();
        insertLegacyBooking(jdbc, orphanEventId, "PENDING", "HELD");

        Map<UUID, UUID> sessionTruth = Map.of(mappedSessionId, mappedEventId);

        // The mapped event has no rows; the orphan legacy event is not in the mapping,
        // so the zero-null gate must fail closed instead of guessing.
        assertThatThrownBy(() -> backfillEquivalent(jdbc, Map.of(mappedEventId, mappedSessionId), sessionTruth))
                .isInstanceOf(IllegalStateException.class);
    }
}
