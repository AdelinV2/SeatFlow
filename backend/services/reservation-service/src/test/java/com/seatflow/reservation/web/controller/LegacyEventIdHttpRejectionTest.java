package com.seatflow.reservation.web.controller;

import com.seatflow.common.observability.handler.GlobalExceptionHandler;
import com.seatflow.reservation.config.SecurityConfig;
import com.seatflow.reservation.service.ReservationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P12-008 scenario J (reservation HTTP leg): a pre-P12 event-scoped booking
 * body carrying {@code eventId} is rejected with 400 at the HTTP boundary —
 * never silently ignored, never mapped, and never forwarded to the service
 * where it could infer or choose a session.
 *
 * <p>Record-shape proof lives in {@code LegacyBookingKeyRemovalContractTest};
 * this slice proves the running Spring stack actually enforces
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} end to end.
 *
 * <p>P12-008 scenario J (legacy availability route): the removed pre-P12
 * route {@code GET /api/reservations/events/{eventId}/availability} is
 * unmapped (404) and performs no booking-context/inventory lookup. Route
 * inventory (P12-008 REV-009): every historical version of
 * {@code ReservationController} (commits {@code f75423d}, {@code 98c00f9},
 * {@code 019ae09}, {@code 7d4b2d5}, up to its removal in {@code a488aa5}
 * for TASK-P12-003) exposes exactly one event-scoped alias — this
 * availability route. No other {@code /events/} mapping ever existed on the
 * reservation controller, so this single 404 oracle exhausts the inventory.
 */
@WebMvcTest(controllers = {ReservationController.class, EventSessionAvailabilityController.class})
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class LegacyEventIdHttpRejectionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationService reservationService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private com.seatflow.common.security.converter.JwtRoleConverter jwtRoleConverter;

    @Test
    @DisplayName("Legacy eventId booking body is rejected with 400 and never reaches the service")
    void legacyEventIdBodyIsRejectedWith400() throws Exception {
        String legacyBody = """
                {
                  "eventSessionId": "%s",
                  "eventId": "%s",
                  "customerEmail": "guest@example.com",
                  "seatIds": ["%s"],
                  "seatPrices": ["50.00"],
                  "idempotencyKey": "idem-legacy-http"
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(legacyBody))
                .andExpect(status().isBadRequest());

        verify(reservationService, never()).createReservation(any(), any());
    }

    @Test
    @DisplayName("Removed legacy event-scoped availability route is unmapped (404) with zero service interaction")
    void legacyEventScopedAvailabilityRouteIsUnmapped() throws Exception {
        // P12-008 scenario J / REV-009: the pre-P12
        // GET /api/reservations/events/{eventId}/availability route was removed
        // by TASK-P12-003 (commit a488aa5) and replaced by the session-explicit
        // GET /api/event-sessions/{eventSessionId}/seats/availability. A
        // compatibility mapping that silently inferred a session would answer
        // 200 here; the correct post-cutover answer is 404 (no handler), never
        // a redirect and never a booking-context/inventory lookup.
        mockMvc.perform(get("/api/reservations/events/{eventId}/availability", UUID.randomUUID()))
                .andExpect(status().isNotFound());

        verifyNoInteractions(reservationService);
    }
}
