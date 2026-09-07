package com.seatflow.ai.service;

import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.tool.dto.GetReservationRequest;
import com.seatflow.ai.tool.dto.ReservationToolResult;

/**
 * Ownership-safe read-only reservation lookup behind the {@code getReservation} AI tool.
 *
 * <p>Validates untrusted model input at this boundary, calls Reservation Service with the
 * authenticated user's JWT, and maps the response to a compact customer-safe result. Reservation
 * Service remains authoritative for ownership/access; this layer never switches to a privileged
 * identity after a {@code 403} and never exposes email/name, guest proofs, internal provider
 * IDs, payment tokens, or audit internals.
 */
public interface ReservationToolService {

    ReservationToolResult getReservation(GetReservationRequest request, AiRequestContext context);
}
