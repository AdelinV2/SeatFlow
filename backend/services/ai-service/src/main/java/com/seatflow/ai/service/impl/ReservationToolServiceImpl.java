package com.seatflow.ai.service.impl;

import com.seatflow.ai.client.ReservationServiceClient;
import com.seatflow.ai.client.dto.ReservationServiceReservationDto;
import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.exception.AiToolError;
import com.seatflow.ai.exception.AiToolException;
import com.seatflow.ai.service.ReservationToolService;
import com.seatflow.ai.tool.dto.GetReservationRequest;
import com.seatflow.ai.tool.dto.ReservationToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Ownership-safe {@code getReservation} implementation.
 *
 * <p>Maps the authoritative Reservation Service response to the compact customer-safe tool
 * result. A {@code 403/404} from downstream surfaces the corresponding safe tool error — never
 * a guessed reservation. Currency comes from the authoritative pricing snapshot when the
 * reservation response carries per-seat pricing context; otherwise the single-currency invariant
 * of the priced seats is used, falling back to omission-safe {@code null} rather than a guess.
 * Email/name and all sensitive internals are dropped unconditionally.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationToolServiceImpl implements ReservationToolService {

    private final ReservationServiceClient reservationServiceClient;

    @Override
    public ReservationToolResult getReservation(GetReservationRequest request, AiRequestContext context) {
        requireAuthenticated(context);
        if (request == null || request.reservationId() == null || request.reservationId().isBlank()) {
            throw new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                    "A reservationId is required to look up a reservation.");
        }
        UUID reservationId = parseUuid(request.reservationId().trim());
        ReservationServiceReservationDto dto =
                reservationServiceClient.getReservation(reservationId, context);
        if (dto == null || dto.id() == null) {
            throw new AiToolException(AiToolError.NOT_FOUND, "Reservation not found.");
        }
        if (!reservationId.equals(dto.id())) {
            throw new AiToolException(AiToolError.UNEXPECTED_TOOL_FAILURE,
                    "Reservation lookup returned data for a different reservation.");
        }
        List<ReservationToolResult.ReservationSeatDisplay> seats =
                dto.seats() == null ? List.of() : dto.seats().stream()
                        .filter(seat -> seat != null && seat.seatId() != null)
                        .map(seat -> new ReservationToolResult.ReservationSeatDisplay(
                                seat.seatId(),
                                seatLabel(seat.rowNumber(), seat.seatNumber()),
                                seat.rowNumber(),
                                seat.seatNumber()))
                        .toList();
        log.info("AI reservation lookup completed: reservationId={}, status={}",
                reservationId, dto.status());
        return new ReservationToolResult(
                dto.id(), dto.eventId(), dto.eventSessionId(), dto.status(), dto.expiresAt(),
                dto.totalAmount(), null, seats,
                dto.sessionStartsAt(), dto.sessionEndsAt(), dto.sessionTimezone());
    }

    private String seatLabel(String rowNumber, Integer seatNumber) {
        String row = rowNumber == null || rowNumber.isBlank() ? "?" : rowNumber.trim();
        String seat = seatNumber == null ? "?" : seatNumber.toString();
        return ("Row " + row + " Seat " + seat).trim();
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new AiToolException(AiToolError.INVALID_TOOL_ARGUMENT,
                    "The reservationId must be a valid UUID.", ex);
        }
    }

    private void requireAuthenticated(AiRequestContext context) {
        if (context == null || !context.isAuthenticated()) {
            throw new AiToolException(AiToolError.UNAUTHENTICATED,
                    "Authentication is required to look up a reservation.");
        }
    }
}
