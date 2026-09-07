package com.seatflow.ai.tool;

import com.seatflow.ai.context.AiRequestContextFactory;
import com.seatflow.ai.service.ReservationToolService;
import com.seatflow.ai.tool.dto.GetReservationRequest;
import com.seatflow.ai.tool.dto.ReservationToolResult;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Ownership-safe read-only reservation lookup tool (TASK-P15-005 section 3).
 *
 * <p>Thin delegate: authentication comes from the server-side request context (never from model
 * input), validation and customer-safe mapping live in {@link ReservationToolService}, and HTTP
 * calls live in the dedicated reservation client. A {@code 404/403} returns a safe tool error,
 * never a guessed reservation.
 */
@Component
@RequiredArgsConstructor
public class ReservationLookupTools {

    private final ReservationToolService reservationToolService;
    private final AiRequestContextFactory requestContexts;

    @Tool(name = "getReservation",
            description = "Look up one owned reservation by its reservationId UUID string. Returns "
                    + "a compact customer-safe snapshot (status, expiry, total, seat summary, "
                    + "session start when present). Ownership is enforced by the reservation "
                    + "service with the caller credentials; report NOT_FOUND/FORBIDDEN instead of "
                    + "guessing.")
    public ReservationToolResult getReservation(
            @ToolParam(description = "Reservation lookup key holding the reservationId UUID string")
            GetReservationRequest request) {
        return reservationToolService.getReservation(request, requestContexts.requireAuthenticated());
    }
}
