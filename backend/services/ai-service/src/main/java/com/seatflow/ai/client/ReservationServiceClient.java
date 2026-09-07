package com.seatflow.ai.client;

import com.seatflow.ai.client.dto.ReservationServiceReservationDto;
import com.seatflow.ai.context.AiRequestContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Dedicated Reservation Service boundary for the Phase 15 AI confirmation flow
 * (TASK-P15-005 section 8).
 *
 * <p>Used for both the ownership-safe read-only {@code getReservation} lookup and the confirmed
 * reservation creation after explicit user confirmation. All calls go through Eureka/LoadBalancer
 * to the logical {@code reservation-service} name with the authenticated user's Bearer JWT and
 * the ambient correlation ID. Reservation Service remains authoritative for the 10-seat rule,
 * 15-minute expiration, concurrency/double booking, ownership, idempotency, and status.
 *
 * <p>Implementations must never switch to an internal privileged identity after a {@code 403},
 * must never send guest proof headers, and must surface typed failures so callers can map them
 * to the canonical P15-005 failure contract without leaking raw downstream bodies.
 */
public interface ReservationServiceClient {

    /**
     * Ownership-safe read-only lookup ({@code GET /api/reservations/{id}}) with the caller's
     * JWT. A {@code 403/404} surfaces a safe tool error — never a guessed reservation and never
     * a privileged-identity retry.
     */
    ReservationServiceReservationDto getReservation(UUID reservationId, AiRequestContext context);

    /**
     * Creates a normal 15-minute hold ({@code POST /api/reservations}) with only server-derived
     * values: exact session, exact seat IDs in proposal order, server-derived per-seat prices in
     * the same order, and the proposal's server-generated idempotency key.
     */
    ReservationServiceReservationDto createReservation(
            UUID eventSessionId,
            List<UUID> seatIds,
            List<BigDecimal> seatPrices,
            String idempotencyKey,
            AiRequestContext context);
}
