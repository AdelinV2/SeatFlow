package com.seatflow.ai.service;

import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.tool.dto.EventSessionsToolResult;
import com.seatflow.ai.tool.dto.EventToolResult;
import com.seatflow.ai.tool.dto.GetEventRequest;
import com.seatflow.ai.tool.dto.GetEventSessionsRequest;
import com.seatflow.ai.tool.dto.SearchEventsRequest;
import com.seatflow.ai.tool.dto.SearchEventsResult;

/**
 * Read-only domain logic behind the event/session AI tools.
 *
 * <p>Validates untrusted model input at this boundary, delegates to the load-balanced Event
 * Service client, and normalizes responses into bounded customer-safe snapshots. The context must
 * carry an authenticated identity; anonymous calls fail instead of escalating privileges.
 */
public interface EventToolService {

    SearchEventsResult searchEvents(SearchEventsRequest request, AiRequestContext context);

    EventToolResult getEvent(GetEventRequest request, AiRequestContext context);

    EventSessionsToolResult getEventSessions(GetEventSessionsRequest request, AiRequestContext context);
}
