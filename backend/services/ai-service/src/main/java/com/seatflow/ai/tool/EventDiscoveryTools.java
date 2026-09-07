package com.seatflow.ai.tool;

import com.seatflow.ai.context.AiRequestContextFactory;
import com.seatflow.ai.service.EventToolService;
import com.seatflow.ai.tool.dto.EventSessionsToolResult;
import com.seatflow.ai.tool.dto.EventToolResult;
import com.seatflow.ai.tool.dto.GetEventRequest;
import com.seatflow.ai.tool.dto.GetEventSessionsRequest;
import com.seatflow.ai.tool.dto.SearchEventsRequest;
import com.seatflow.ai.tool.dto.SearchEventsResult;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Controlled read-only Spring AI tools for customer event discovery (TASK-P15-002).
 *
 * <p>Methods are thin delegates: authentication comes from the server-side request context (never
 * from model input), validation and normalization live in {@link EventToolService}, and HTTP
 * calls live in the dedicated client layer. Authorization is enforced in code and by the
 * downstream Event Service, never by prompt wording alone.
 */
@Component
@RequiredArgsConstructor
public class EventDiscoveryTools {

    private final EventToolService eventToolService;
    private final AiRequestContextFactory requestContexts;

    @Tool(name = "searchEvents",
            description = "Search published SeatFlow events by title text, category, and next-session "
                    + "date bounds for customer discovery. Returns an authoritative snapshot of up to "
                    + "20 matching events with IDs. Never invent events; report failures instead of guessing.")
    public SearchEventsResult searchEvents(
            @ToolParam(description = "Typed search criteria; all fields optional", required = false)
            SearchEventsRequest request) {
        return eventToolService.searchEvents(request, requestContexts.requireAuthenticated());
    }

    @Tool(name = "getEvent",
            description = "Get the authoritative customer-safe snapshot of one published event by its "
                    + "eventId UUID string, normally taken from searchEvents. Preserves upstream status "
                    + "semantics. Report NOT_FOUND instead of substituting another event.")
    public EventToolResult getEvent(
            @ToolParam(description = "Event lookup key holding the eventId UUID string")
            GetEventRequest request) {
        return eventToolService.getEvent(request, requestContexts.requireAuthenticated());
    }

    @Tool(name = "getEventSessions",
            description = "List booking-visible sessions (showings) for one eventId UUID string with "
                    + "exact start/end instants and status. Session IDs are the only valid inventory "
                    + "keys for later seat tools. Results are authoritative snapshots; report failures "
                    + "instead of guessing.")
    public EventSessionsToolResult getEventSessions(
            @ToolParam(description = "Session lookup key with eventId UUID string plus optional bounds")
            GetEventSessionsRequest request) {
        return eventToolService.getEventSessions(request, requestContexts.requireAuthenticated());
    }
}
