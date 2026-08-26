package com.seatflow.reservation.web.controller;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ConflictException;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.observability.handler.GlobalExceptionHandler;
import com.seatflow.reservation.config.SecurityConfig;
import com.seatflow.reservation.model.enums.ReservationStatus;
import com.seatflow.reservation.model.enums.SeatHoldStatus;
import com.seatflow.reservation.service.ReservationService;
import com.seatflow.reservation.web.dto.response.EventSeatStatusResponse;
import com.seatflow.reservation.web.dto.response.ReservationResponse;
import com.seatflow.reservation.web.dto.response.SeatAvailabilityResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ReservationController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class ReservationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationService reservationService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private com.seatflow.common.security.converter.JwtRoleConverter jwtRoleConverter;

    private ReservationResponse sampleResponse(UUID id, UUID eventId, UUID userId) {
        return new ReservationResponse(id, eventId, userId, "guest@example.com", ReservationStatus.PENDING,
                Instant.now().plus(Duration.ofMinutes(15)), new BigDecimal("50.00"), 1, List.of(), Instant.now());
    }

    private String validBody(UUID eventId, UUID seatId, String idempotencyKey) {
        return """
                {
                  "eventId": "%s",
                  "customerEmail": "guest@example.com",
                  "seatIds": ["%s"],
                  "seatPrices": ["50.00"],
                  "idempotencyKey": "%s"
                }
                """.formatted(eventId, seatId, idempotencyKey);
    }

    @Test
    void createReservation_unauthenticatedGuest_returns201() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        when(reservationService.createReservation(any(), any())).thenReturn(sampleResponse(reservationId, eventId, null));

        mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(eventId, seatId, "idem-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(reservationId.toString()));
    }

    @Test
    void createReservation_authenticatedUser_returns201() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        when(reservationService.createReservation(any(), any())).thenReturn(sampleResponse(reservationId, eventId, userId));

        mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(eventId, seatId, "idem-2"))
                        .with(jwt().jwt(j -> j.subject(userId.toString()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(reservationId.toString()));
    }

    @Test
    void createReservation_withoutGuestEmail_returns400() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        String body = """
                {
                  "eventId": "%s",
                  "seatIds": ["%s"],
                  "seatPrices": ["50.00"],
                  "idempotencyKey": "idem-3"
                }
                """.formatted(eventId, seatId);

        mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createReservation_withMoreThanTenSeats_returns400() throws Exception {
        UUID eventId = UUID.randomUUID();
        String seatIdsJson = IntStream.range(0, 11)
                .mapToObj(i -> "\"" + UUID.randomUUID() + "\"")
                .collect(Collectors.joining(","));
        String seatPricesJson = IntStream.range(0, 11)
                .mapToObj(i -> "\"10.00\"")
                .collect(Collectors.joining(","));
        String body = """
                {
                  "eventId": "%s",
                  "customerEmail": "guest@example.com",
                  "seatIds": [%s],
                  "seatPrices": [%s],
                  "idempotencyKey": "idem-4"
                }
                """.formatted(eventId, seatIdsJson, seatPricesJson);

        mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createReservation_whenConflict_returns409() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        when(reservationService.createReservation(any(), any()))
                .thenThrow(new ConflictException("One or more seats are already held or sold", ErrorCode.SEAT_ALREADY_RESERVED));

        mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(eventId, seatId, "idem-5")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.SEAT_ALREADY_RESERVED.getCode()));
    }

    @Test
    void getReservation_found_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        when(reservationService.getReservationById(any(), any())).thenReturn(sampleResponse(id, eventId, null));

        mockMvc.perform(get("/api/reservations/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void getReservation_notFound_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(reservationService.getReservationById(any(), any()))
                .thenThrow(new ResourceNotFoundException("Reservation", id));

        mockMvc.perform(get("/api/reservations/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelReservation_returns204() throws Exception {
        UUID id = UUID.randomUUID();
        doNothing().when(reservationService).cancelReservation(any(), any());

        mockMvc.perform(post("/api/reservations/{id}/cancel", id))
                .andExpect(status().isNoContent());
    }

    @Test
    void getSeatAvailability_returns200WithStatuses() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        when(reservationService.getSeatAvailability(any()))
                .thenReturn(new SeatAvailabilityResponse(eventId,
                        List.of(new EventSeatStatusResponse(seatId, SeatHoldStatus.HELD))));

        mockMvc.perform(get("/api/reservations/events/{eventId}/availability", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.seatStatuses[0].seatId").value(seatId.toString()));
    }
}
