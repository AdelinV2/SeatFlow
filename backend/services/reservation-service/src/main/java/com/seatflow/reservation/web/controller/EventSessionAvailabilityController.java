package com.seatflow.reservation.web.controller;

import com.seatflow.common.domain.dto.ApiErrorResponse;
import com.seatflow.reservation.service.ReservationService;
import com.seatflow.reservation.web.dto.response.SeatAvailabilityResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Session-explicit seat availability (P12-003 / ADR-011).
 *
 * <p>Availability is partitioned by {@code eventSessionId}, never by {@code eventId}.
 * The legacy ambiguous {@code /api/reservations/events/{id}/availability} route was
 * removed: an event id is no longer a valid inventory key.
 */
@RestController
@RequestMapping("/api/event-sessions")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Event Session Availability", description = "Session-scoped real-time seat availability APIs")
public class EventSessionAvailabilityController {

    private final ReservationService reservationService;

    @GetMapping("/{eventSessionId}/seats/availability")
    @Operation(
        summary = "Get real-time seat availability for an event session",
        description = "Returns list of currently held and sold seat statuses for the specified event session only. "
                + "Holds in other sessions of the same event never affect this response."
    )
    @ApiResponse(responseCode = "200", description = "Seat availability list retrieved",
        content = @Content(schema = @Schema(implementation = SeatAvailabilityResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid session identifier",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<SeatAvailabilityResponse> getSeatAvailability(@PathVariable UUID eventSessionId) {
        SeatAvailabilityResponse response = reservationService.getSeatAvailability(eventSessionId);
        return ResponseEntity.ok(response);
    }
}
