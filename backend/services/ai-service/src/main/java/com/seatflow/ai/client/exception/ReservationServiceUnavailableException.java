package com.seatflow.ai.client.exception;

import com.seatflow.ai.exception.AiToolError;
import com.seatflow.ai.exception.AiToolException;

/**
 * The Reservation Service call failed (timeout, circuit open, 5xx, or unexpected transport error).
 * Callers must report this to the user and may offer a retry; they must never substitute stale
 * local data or invent availability.
 */
public class ReservationServiceUnavailableException extends AiToolException {

    public ReservationServiceUnavailableException(AiToolError error, String message) {
        super(error, message);
    }

    public ReservationServiceUnavailableException(AiToolError error, String message, Throwable cause) {
        super(error, message, cause);
    }
}
