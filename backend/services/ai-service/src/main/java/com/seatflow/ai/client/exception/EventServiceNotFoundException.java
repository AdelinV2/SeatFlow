package com.seatflow.ai.client.exception;

import com.seatflow.ai.exception.AiToolError;
import com.seatflow.ai.exception.AiToolException;

import java.util.UUID;

/**
 * The Event Service answered {@code 404} for a validated identifier. Never a reason to fabricate
 * a substitute event or session.
 */
public class EventServiceNotFoundException extends AiToolException {

    public EventServiceNotFoundException(String resource, UUID id) {
        super(AiToolError.NOT_FOUND, resource + " not found: " + id);
    }
}
