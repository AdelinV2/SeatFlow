package com.seatflow.reservation.web.controller;

import com.seatflow.common.domain.dto.ApiErrorResponse;
import com.seatflow.common.security.context.UserContext;
import com.seatflow.reservation.service.ReservationService;
import com.seatflow.reservation.web.dto.request.CreateReservationRequest;
import com.seatflow.reservation.web.dto.response.ReservationResponse;
import com.seatflow.reservation.web.dto.response.SeatAvailabilityResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Reservations", description = "Seat reservation hold and availability management APIs")
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping
    @Operation(
        summary = "Create a 15-minute seat reservation hold",
        description = "Places a temporary 15-minute hold on selected seats (max 10 seats). Supports authenticated " +
                "customers and unauthenticated guests (ADR-001)."
    )
    @ApiResponse(responseCode = "201", description = "Seat reservation hold created successfully",
        content = @Content(schema = @Schema(implementation = ReservationResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation error, invalid email, or seat limit exceeded",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "One or more seats are already held or sold (Conflict)",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "Event catalog service unavailable",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<ReservationResponse> createReservation(@Valid @RequestBody CreateReservationRequest request) {
        UUID authenticatedUserId = UserContext.getCurrentUserId().map(UUID::fromString).orElse(null);
        ReservationResponse response = reservationService.createReservation(request, authenticatedUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{reservationId}")
    @Operation(
        summary = "Get reservation details and hold expiration countdown",
        description = "Retrieves reservation hold status, expiry timestamp, and reserved seats."
    )
    @ApiResponse(responseCode = "200", description = "Reservation found",
        content = @Content(schema = @Schema(implementation = ReservationResponse.class)))
    @ApiResponse(responseCode = "404", description = "Reservation not found",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<ReservationResponse> getReservation(@PathVariable UUID reservationId,
            @RequestHeader(value = "X-Customer-Email", required = false) String customerEmailProof) {
        UUID authenticatedUserId = UserContext.getCurrentUserId().map(UUID::fromString).orElse(null);
        ReservationResponse response = reservationService.getReservationById(reservationId, authenticatedUserId, customerEmailProof);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{reservationId}/cancel")
    @Operation(
        summary = "Cancel an active seat reservation hold",
        description = "Releases held seats immediately before the 15-minute expiration timer expires."
    )
    @ApiResponse(responseCode = "204", description = "Reservation hold cancelled successfully")
    @ApiResponse(responseCode = "400", description = "Reservation is not in PENDING status",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Reservation not found",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<Void> cancelReservation(@PathVariable UUID reservationId,
            @RequestHeader(value = "X-Customer-Email", required = false) String customerEmailProof) {
        UUID authenticatedUserId = UserContext.getCurrentUserId().map(UUID::fromString).orElse(null);
        reservationService.cancelReservation(reservationId, authenticatedUserId, customerEmailProof);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/events/{eventId}/availability")
    @Operation(
        summary = "Get real-time seat availability for an event",
        description = "Returns list of currently held and sold seat statuses for the specified event."
    )
    @ApiResponse(responseCode = "200", description = "Seat availability list retrieved",
        content = @Content(schema = @Schema(implementation = SeatAvailabilityResponse.class)))
    public ResponseEntity<SeatAvailabilityResponse> getSeatAvailability(@PathVariable UUID eventId) {
        SeatAvailabilityResponse response = reservationService.getSeatAvailability(eventId);
        return ResponseEntity.ok(response);
    }
}
