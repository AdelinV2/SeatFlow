package com.seatflow.ai.client.exception;

import com.seatflow.ai.exception.AiToolError;
import com.seatflow.ai.exception.AiToolException;

/**
 * The Event Service call failed (timeout, circuit open, 5xx, or unexpected transport error).
 * Callers must report this to the user and may offer a retry; they must never invent data.
 */
public class EventServiceUnavailableException extends AiToolException {

    public EventServiceUnavailableException(AiToolError error, String message) {
        super(error, message);
    }

    public EventServiceUnavailableException(AiToolError error, String message, Throwable cause) {
        super(error, message, cause);
    }
}
