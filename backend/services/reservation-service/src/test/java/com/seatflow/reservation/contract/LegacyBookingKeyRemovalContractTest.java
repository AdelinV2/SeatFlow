package com.seatflow.reservation.contract;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.seatflow.reservation.web.dto.request.CreateReservationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P12-007 regression gate: the event session is the sole booking key.
 *
 * <p>No reservation/availability API accepts {@code eventId} as a booking key or
 * infers a session from it. The parent event id survives only as a
 * non-authoritative audit/display reference derived from the trusted session
 * booking context. V8 is a fail-closed migrate-time orphan gate (hard NOT NULL
 * is tracked follow-up TASK-P12-009); {@code SessionIntegrityStartupCheck}
 * alerts on NULL session rows at boot.
 */
class LegacyBookingKeyRemovalContractTest {

    @Test
    @DisplayName("CreateReservationRequest exposes no eventId booking key")
    void requestHasNoLegacyEventIdField() {
        boolean hasEventId = Arrays.stream(CreateReservationRequest.class.getRecordComponents())
                .anyMatch(c -> c.getName().equals("eventId"));
        assertThat(hasEventId).isFalse();
        boolean hasSession = Arrays.stream(CreateReservationRequest.class.getRecordComponents())
                .anyMatch(c -> c.getName().equals("eventSessionId"));
        assertThat(hasSession).isTrue();
    }

    @Test
    @DisplayName("Legacy eventId JSON is rejected, never silently ignored or mapped")
    void legacyEventIdJsonIsRejected() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        String legacy = """
                {"eventSessionId":"123e4567-e89b-12d3-a456-426614174000",
                 "eventId":"123e4567-e89b-12d3-a456-426614174001",
                 "seatIds":["123e4567-e89b-12d3-a456-426614174002"],
                 "seatPrices":[50.00],"idempotencyKey":"idem-legacy"}""";
        assertThatThrownBy(() -> mapper.readValue(legacy, CreateReservationRequest.class))
                .hasMessageContaining("eventId");
    }

    @Test
    @DisplayName("No compatibility mismatch guard remains in the service")
    void noCompatGuardRemains() throws Exception {
        Path service = Path.of(
                "src/main/java/com/seatflow/reservation/service/impl/ReservationServiceImpl.java");
        String code = Files.readString(service);
        assertThat(code).doesNotContain("rejectCompatEventMismatch");
        assertThat(code).doesNotContain("request.eventId()");
    }

    @Test
    @DisplayName("V8 migration gates on orphan session refs without dropping audit columns")
    void v8MigrationGatesOrphanSessionRefs() throws Exception {
        Path migration = Path.of("src/main/resources/db/migration/V8__enforce_session_inventory_key.sql");
        assertThat(Files.exists(migration)).isTrue();
        String sql = Files.readString(migration);
        assertThat(sql).contains("event_session_id IS NULL");
        assertThat(sql).contains("run SessionInventoryBackfillService before V8");
        // The NOT NULL deferral is a tracked risk, not a silent leftover.
        assertThat(sql).contains("TASK-P12-009");
        // Audit/display retention is explicit: event_id columns are never dropped here.
        assertThat(sql).doesNotContain("DROP COLUMN");
    }
}
