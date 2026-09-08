package com.seatflow.ai.client;

import com.seatflow.ai.client.dto.SeatAvailabilityClientDto;
import com.seatflow.ai.context.AiRequestContext;

import java.util.UUID;

/**
 * Read-only Reservation Service boundary for AI seat tools (TASK-P15-003 section 3).
 *
 * <p>All calls go through Eureka/LoadBalancer to the logical {@code reservation-service} name with
 * the caller's Bearer JWT and the ambient correlation ID. The service is authoritative for
 * session-scoped seat availability; implementations must throw typed tool errors on failure and
 * must never return cached or invented availability.
 */
public interface ReservationAvailabilityClient {

    SeatAvailabilityClientDto getSeatAvailability(UUID eventSessionId, AiRequestContext context);
}
